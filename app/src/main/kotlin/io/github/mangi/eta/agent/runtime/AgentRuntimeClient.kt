package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeEndpoint
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * 入口进程侧的 Runtime 客户端。
 *
 * 它只负责把一次 Agent 请求交给模块进程，并把事件/结果带回入口适配层；
 * 不执行模型、不执行工具、不渲染 UI。
 */
internal class AgentRuntimeClient(
    private val context: Context,
    private val logger: AgentLogger,
    private val entryToolExecutor: AgentModelClient.ToolExecutor? = null,
    private val xiaomiToolsBridgeEndpoint: XiaomiToolsBridgeEndpoint? = null,
    private val hostBridge: AgentHostBridge? = null,
) {
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }

    sealed interface ActiveRunQuery {
        data class Known(val runId: String?) : ActiveRunQuery
        data object Unavailable : ActiveRunQuery
    }

    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit
    ): AgentRuntimeWire.RunResult {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val effectiveRequest = request.copy(
            entryTools = if (entryToolExecutor == null) emptyList() else request.entryTools,
            hostCapabilities = request.hostCapabilities + hostBridge?.capabilities.orEmpty(),
        )
        val entryToolPool = if (effectiveRequest.entryTools.isNotEmpty()) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "agent-entry-tools").apply { isDaemon = true }
            }
        } else {
            null
        }
        val serviceMessengerRef = AtomicReference<Messenger?>()
        val hostExecutor = hostBridge?.let {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "agent-host-bridge").apply { isDaemon = true }
            }
        }
        val clientMessenger = Messenger(
            ClientHandler(
                onEvent = onEvent,
                onResult = { result ->
                    resultRef.set(result)
                    resultLatch.countDown()
                },
                onRequestIngested = {
                    preparedImagesRef.getAndSet(null)?.close()
                },
                entryToolExecutor = entryToolExecutor,
                entryToolPool = entryToolPool,
                logger = logger,
                xiaomiToolsBridgeEndpoint = xiaomiToolsBridgeEndpoint,
                onHostCall = { data ->
                    executeHostCall(
                        data = data,
                        serviceMessenger = serviceMessengerRef.get(),
                        executor = hostExecutor,
                    )
                },
            )
        )

        val lease = AgentRuntimeConnection.acquire(context, logger) ?: run {
            entryToolPool?.shutdownNow()
            hostExecutor?.shutdownNow()
            runCatching { hostBridge?.close() }
            return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务绑定失败")
        }
        val serviceMessenger = lease.messenger
        serviceMessengerRef.set(serviceMessenger)
        val deathRecipient = IBinder.DeathRecipient {
            if (resultRef.get() == null) {
                resultRef.set(
                    AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务连接已断开")
                )
                resultLatch.countDown()
            }
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_START_RUN)
            msg.replyTo = clientMessenger
            val preparedImages = AgentRuntimeImageTransfer.prepare(context, effectiveRequest.images)
            preparedImagesRef.set(preparedImages)
            msg.data = AgentRuntimeWire.toBundle(effectiveRequest, preparedImages.images)
            serviceMessenger.send(msg)
            if (!resultLatch.await(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                runCatching {
                    val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                    cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                    serviceMessenger.send(cancelMessage)
                }
                return AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 执行超时",
                )
            }
            return resultRef.get() ?: AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 未返回结果")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching {
                val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                serviceMessenger.send(cancelMessage)
            }
            return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 等待被中断")
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime start request failed: type=${throwable.safeLogType()}")
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = when (throwable) {
                    is AgentRuntimeWire.PayloadTooLargeException -> throwable.message
                    is AgentRuntimeImageTransfer.ImageTransferException -> throwable.message
                    else -> "Agent Runtime 请求发送失败（${throwable.safeLogType()}）"
                },
            )
        } finally {
            preparedImagesRef.getAndSet(null)?.close()
            entryToolPool?.shutdownNow()
            serviceMessengerRef.set(null)
            hostExecutor?.shutdownNow()
            runCatching { hostBridge?.close() }
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun executeHostCall(
        data: android.os.Bundle,
        serviceMessenger: Messenger?,
        executor: ExecutorService?,
    ) {
        val bridge = hostBridge
        if (bridge == null || executor == null || serviceMessenger == null) {
            val callId = runCatching { AgentRuntimeWire.hostCallFromBundle(data).callId }.getOrDefault("")
            AgentRuntimeWire.closeHostAttachments(data)
            sendHostResult(
                serviceMessenger,
                AgentHostResult(
                    callId = callId,
                    ok = false,
                    payload = "{}",
                    errorCode = "HOST_CAPABILITY_UNAVAILABLE",
                    errorMessage = "入口进程未提供宿主能力",
                ),
            )
            return
        }
        val call = runCatching { AgentRuntimeWire.hostCallFromBundle(data) }
            .getOrElse { throwable ->
                AgentRuntimeWire.closeHostAttachments(data)
                sendHostResult(
                    serviceMessenger,
                    AgentHostResult(
                        callId = "",
                        ok = false,
                        payload = "{}",
                        errorCode = "INVALID_HOST_CALL",
                        errorMessage = throwable.message ?: "宿主调用格式无效",
                    ),
                )
                return
            }
        runCatching {
            executor.execute {
                val result = runCatching {
                    bridge.execute(call) { event -> sendHostEvent(serviceMessenger, event) }
                }.getOrElse { throwable ->
                    AgentHostResult(
                        callId = call.callId,
                        ok = false,
                        payload = "{}",
                        errorCode = "HOST_EXECUTION_FAILED",
                        errorMessage = throwable.message ?: throwable.safeLogType(),
                    )
                }
                call.attachments.forEach { attachment ->
                    runCatching { attachment.fileDescriptor?.close() }
                }
                sendHostResult(serviceMessenger, result)
            }
        }.onFailure { throwable ->
            call.attachments.forEach { attachment ->
                runCatching { attachment.fileDescriptor?.close() }
            }
            sendHostResult(
                serviceMessenger,
                AgentHostResult(
                    callId = call.callId,
                    ok = false,
                    payload = JSONObject().toString(),
                    errorCode = "HOST_EXECUTOR_REJECTED",
                    errorMessage = throwable.message ?: "宿主执行队列不可用",
                ),
            )
        }
    }

    private fun sendHostEvent(serviceMessenger: Messenger, event: AgentHostEvent) {
        runCatching {
            val message = Message.obtain(null, AgentRuntimeWire.MSG_HOST_EVENT)
            message.data = AgentRuntimeWire.hostEventToBundle(event)
            serviceMessenger.send(message)
        }.onFailure { throwable ->
            logger.warn("Agent host event delivery failed: type=${throwable.safeLogType()}")
        }
    }

    private fun sendHostResult(serviceMessenger: Messenger?, result: AgentHostResult) {
        if (serviceMessenger == null) {
            result.attachments.forEach { attachment -> runCatching { attachment.fileDescriptor?.close() } }
            return
        }
        try {
            val message = Message.obtain(null, AgentRuntimeWire.MSG_HOST_RESULT)
            message.data = AgentRuntimeWire.hostResultToBundle(result)
            serviceMessenger.send(message)
        } catch (throwable: Throwable) {
            logger.warn("Agent host result delivery failed: type=${throwable.safeLogType()}")
        } finally {
            result.attachments.forEach { attachment -> runCatching { attachment.fileDescriptor?.close() } }
        }
    }

    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ACK_RESULT)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> {
        return when (val query = queryCompletedRuns()) {
            is CompletedRunsQuery.Known -> query.runs
            CompletedRunsQuery.Unavailable -> emptyList()
        }
    }

    fun queryCompletedRuns(): CompletedRunsQuery {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(
            DrainHandler { results ->
                resultRef.set(results)
                resultLatch.countDown()
            }
        )

        return withRuntimeMessenger<CompletedRunsQuery>(CompletedRunsQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                CompletedRunsQuery.Known(resultRef.get())
            } else {
                CompletedRunsQuery.Unavailable
            }
        }
    }

    fun queryActiveRun(): ActiveRunQuery {
        val responseLatch = CountDownLatch(1)
        val runIdRef = AtomicReference("")
        val clientMessenger = Messenger(
            ActiveRunHandler { runId ->
                runIdRef.set(runId)
                responseLatch.countDown()
            }
        )

        return withRuntimeMessenger<ActiveRunQuery>(ActiveRunQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunQuery.Unavailable
            } else {
                ActiveRunQuery.Known(runIdRef.get().takeIf(String::isNotBlank))
            }
        }
    }

    /** 重新订阅一个仍存活的 run；Service 会先重放安全事件，再继续推送实时事件。 */
    fun attachRun(
        runId: String,
        onEvent: (AgentEvent) -> Unit,
    ): AttachOutcome {
        if (runId.isBlank()) return AttachOutcome.NotActive
        val terminalLatch = CountDownLatch(1)
        val attachedRef = AtomicReference<Boolean?>(null)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val clientMessenger = Messenger(
            AttachHandler(
                onEvent = onEvent,
                onAttachResponse = { attached ->
                    attachedRef.set(attached)
                    if (!attached) terminalLatch.countDown()
                },
                onResult = { result ->
                    resultRef.set(result)
                    terminalLatch.countDown()
                },
            )
        )
        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AttachOutcome.Unavailable
        val deathRecipient = IBinder.DeathRecipient { terminalLatch.countDown() }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN)
            msg.replyTo = clientMessenger
            msg.data = AgentRuntimeWire.ackBundle(runId)
            lease.messenger.send(msg)
            if (!terminalLatch.await(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                return AttachOutcome.Unavailable
            }
            resultRef.get()?.let { return AttachOutcome.Completed(it) }
            return if (attachedRef.get() == false) {
                AttachOutcome.NotActive
            } else {
                AttachOutcome.Unavailable
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttachOutcome.Unavailable
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime attach failed: type=${throwable.safeLogType()}")
            return AttachOutcome.Unavailable
        } finally {
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun <T> withRuntimeMessenger(defaultValue: T, block: (Messenger) -> T): T {
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return defaultValue
        try {
            return block(lease.messenger)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return defaultValue
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime service call failed: type=${throwable.safeLogType()}")
            return defaultValue
        } finally {
            lease.close()
        }
    }

    private class ClientHandler(
        private val onEvent: (AgentEvent) -> Unit,
        private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
        private val onRequestIngested: () -> Unit,
        private val entryToolExecutor: AgentModelClient.ToolExecutor?,
        private val entryToolPool: ExecutorService?,
        private val logger: AgentLogger,
        private val xiaomiToolsBridgeEndpoint: XiaomiToolsBridgeEndpoint?,
        private val onHostCall: (android.os.Bundle) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(onEvent)
                }

                AgentRuntimeWire.MSG_RESULT -> {
                    onResult(AgentRuntimeWire.runResultFromBundle(msg.data ?: return))
                }

                AgentRuntimeWire.MSG_REQUEST_INGESTED -> onRequestIngested()

                AgentRuntimeWire.MSG_ENTRY_TOOL_REQUEST -> executeEntryTool(msg)

                AgentRuntimeWire.MSG_XIAOMI_TOOLS_BRIDGE_CALL -> {
                    val data = msg.data ?: Bundle.EMPTY
                    val request = XiaomiToolsBridgeProtocol.callRequestFromBundle(data)
                    val resultTarget = msg.replyTo
                    if (request == null) {
                        sendXiaomiToolsBridgeResult(
                            resultTarget,
                            XiaomiToolsBridgeProtocol.CallResult(
                                callId = XiaomiToolsBridgeProtocol.callIdFromBundle(data),
                                status = XiaomiToolsBridgeProtocol.Status.INVALID_REQUEST,
                                error = "ToolsBridge 请求格式无效",
                            ),
                        )
                        return
                    }
                    val endpoint = xiaomiToolsBridgeEndpoint
                    if (endpoint == null) {
                        sendXiaomiToolsBridgeResult(
                            resultTarget,
                            XiaomiToolsBridgeProtocol.CallResult(
                                callId = request.callId,
                                status = XiaomiToolsBridgeProtocol.Status.UNAVAILABLE,
                                error = "当前入口没有超级小爱 ToolsBridge",
                            ),
                        )
                        return
                    }
                    endpoint.execute(request) { result ->
                        sendXiaomiToolsBridgeResult(resultTarget, result)
                    }
                }

                AgentRuntimeWire.MSG_HOST_CALL -> onHostCall(msg.data ?: return)
            }
        }

        private fun sendXiaomiToolsBridgeResult(
            target: Messenger?,
            result: XiaomiToolsBridgeProtocol.CallResult,
        ) {
            runCatching {
                val response = Message.obtain(
                    null,
                    AgentRuntimeWire.MSG_XIAOMI_TOOLS_BRIDGE_RESULT,
                )
                response.data = XiaomiToolsBridgeProtocol.callResultBundle(result)
                target?.send(response)
            }
        }

        private fun executeEntryTool(message: Message) {
            val replyTo = message.replyTo ?: return
            val call = runCatching {
                AgentRuntimeWire.entryToolCallFromBundle(message.data ?: error("缺少入口工具消息体"))
            }.getOrElse { throwable ->
                logger.warn("Entry tool request rejected: type=${throwable.safeLogType()}")
                sendEntryToolResult(
                    replyTo = replyTo,
                    callId = "invalid",
                    result = errorResult("INVALID_ENTRY_TOOL_REQUEST", "入口工具请求格式无效"),
                )
                return
            }
            val executor = entryToolExecutor
            val pool = entryToolPool
            if (executor == null || pool == null) {
                sendEntryToolResult(
                    replyTo,
                    call.callId,
                    errorResult("ENTRY_TOOL_NOT_AVAILABLE", "当前入口没有工具执行器"),
                )
                return
            }
            try {
                pool.execute {
                    val result = runCatching {
                        executor.execute(
                            AgentModelClient.ToolCall(
                                id = call.callId,
                                name = call.name,
                                argumentsJson = call.argumentsJson,
                            )
                        )
                    }.getOrElse { throwable ->
                        logger.warn("Entry tool execution failed: type=${throwable.safeLogType()}")
                        errorResult(
                            "ENTRY_TOOL_EXECUTION_ERROR",
                            "入口工具执行失败（${throwable.safeLogType()}）",
                        )
                    }
                    sendEntryToolResult(replyTo, call.callId, result)
                }
            } catch (_: RejectedExecutionException) {
                sendEntryToolResult(
                    replyTo,
                    call.callId,
                    errorResult("ENTRY_TOOL_CANCELLED", "入口工具执行队列已关闭"),
                )
            }
        }

        private fun sendEntryToolResult(
            replyTo: Messenger,
            callId: String,
            result: AgentModelClient.ToolResult,
        ) {
            runCatching {
                val response = Message.obtain(null, AgentRuntimeWire.MSG_ENTRY_TOOL_RESULT)
                response.data = AgentRuntimeWire.entryToolResultToBundle(callId, result)
                replyTo.send(response)
            }.onFailure { throwable ->
                logger.warn("Entry tool result delivery failed: type=${throwable.safeLogType()}")
            }
        }

        private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", code)
                    .put("message", message)
                    .toString(),
            )
    }

    private class DrainHandler(
        private val onResults: (List<AgentRuntimeWire.CompletedRun>) -> Unit
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE) {
                onResults(AgentRuntimeWire.completedRunsFromBundle(msg.data ?: return))
            }
        }
    }

    private class ActiveRunHandler(
        private val onResponse: (String) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE) {
                onResponse(AgentRuntimeWire.runIdFromBundle(msg.data ?: return))
            }
        }
    }

    private class AttachHandler(
        private val onEvent: (AgentEvent) -> Unit,
        private val onAttachResponse: (Boolean) -> Unit,
        private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT ->
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(onEvent)
                AgentRuntimeWire.MSG_RESULT ->
                    onResult(AgentRuntimeWire.runResultFromBundle(msg.data ?: return))
                AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE ->
                    onAttachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))
            }
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 8L
        const val RUN_TIMEOUT_MINUTES = 30L
    }
}
