package fuck.andes.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AgentLogger
import fuck.andes.core.safeLogType
import fuck.andes.agent.xiaomi.XiaomiToolsBridgeEndpoint
import fuck.andes.agent.xiaomi.XiaomiToolsBridgeProtocol
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
) {
    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit
    ): AgentRuntimeWire.RunResult {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val effectiveRequest = if (entryToolExecutor == null) {
            request.copy(entryTools = emptyList())
        } else {
            request
        }
        val entryToolPool = if (effectiveRequest.entryTools.isNotEmpty()) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "agent-entry-tools").apply { isDaemon = true }
            }
        } else {
            null
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
            )
        )

        val lease = AgentRuntimeConnection.acquire(context, logger) ?: run {
            entryToolPool?.shutdownNow()
            return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务绑定失败")
        }
        val serviceMessenger = lease.messenger
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
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
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
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(
            DrainHandler { results ->
                resultRef.set(results)
                resultLatch.countDown()
            }
        )

        return withRuntimeMessenger(emptyList()) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            resultRef.get()
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

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 8L
        const val RUN_TIMEOUT_MINUTES = 30L
    }
}
