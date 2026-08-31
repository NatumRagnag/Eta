package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** 一条守护任务记录。pid + token 用于跨 App 重启后的认领，以及停止前防止 PID 复用误杀。 */
internal data class DetachedTask(
    val id: String,
    val pid: Long,
    val token: String,
    val command: String,
    val cwd: String,
    val identity: String,
    val environment: TerminalEnvironment,
    val logPath: String,
    val startedAt: Long,
)

internal data class DetachedTaskStatus(
    val task: DetachedTask,
    val running: Boolean,
)

internal sealed interface DaemonStartResult {
    data class Started(val task: DetachedTask) : DaemonStartResult
    data class Failed(val code: String, val message: String) : DaemonStartResult
}

internal data class DaemonLogsResult(
    val ok: Boolean,
    val text: String = "",
    val truncated: Boolean = false,
    val code: String = "",
    val message: String = "",
)

/**
 * 守护任务（detached task）宿主：启动脱离任何命令会话进程组的长驻进程，并托管其生命周期。
 *
 * 与普通终端命令的差别在于回收语义：托管 shell 退出前会清理同组进程，守护任务通过
 * setsid 自立会话脱离这张回收网；输出重定向到工作区日志文件而非内存，任务记录落盘，
 * App 重启后按 pid + ownership token 认领仍存活的进程。
 *
 * 手机重启后任务全部失效；App 被强制停止时 user identity 任务会被系统连带终止，
 * root identity 任务不受影响。
 */
internal class DetachedTaskSupervisor(
    private val logger: AgentLogger,
    private val recordsFile: File,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val daemonDir: String = DEFAULT_DAEMON_DIR,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
) {
    companion object {
        const val DEFAULT_DAEMON_DIR = "/data/local/tmp/eta/daemon"
        const val LINUX_DAEMON_DIR = "/workspace/daemon"
        const val MAX_TASKS = 8
        const val MAX_RETAINED_RECORDS = 32
        const val MAX_LOG_READ_BYTES = 64 * 1024

        // 进程内允许 AI 侧与 UI 侧各持一个实例；记录文件的读-改-写必须经同一把锁串行。
        private val RECORDS_LOCK = Any()

        /** AI 工具侧与 UI 侧共用同一份记录文件，路径只能在这里定义一次。 */
        fun defaultRecordsFile(context: Context): File =
            File(context.filesDir, "terminal-daemons.json")
    }

    private val oneShotSupervisor = ShellProcessSupervisor()

    /** command/cwd/identity/environment 必须已由调用方归一化。 */
    fun start(
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
    ): DaemonStartResult {
        if (list().count { it.running } >= MAX_TASKS) {
            return DaemonStartResult.Failed("MAX_TASKS_REACHED", "守护任务数量已达上限 $MAX_TASKS，请先停止不用的任务")
        }
        val id = "dm_" + UUID.randomUUID().toString().take(8)
        val token = UUID.randomUUID().toString().replace("-", "")
        val wireDir = wireDaemonDir(environment)
        val wirePidFile = "$wireDir/$id.pid"
        val wireLogFile = "$wireDir/$id.log"
        // 内层 sh 先落 pidfile 再 exec 目标命令，PID 在 exec 前后不变。
        val innerScript = "echo \$\$ > ${shellQuote(wirePidFile)}; " +
            "export $ETA_PROCESS_OWNER_ENV=${shellQuote(token)}; " +
            "exec sh -c ${shellQuote(command)} >> ${shellQuote(wireLogFile)} 2>&1"
        val launcherScript = buildString {
            appendLine("mkdir -p ${shellQuote(wireDir)} || exit 1")
            appendLine("rm -f ${shellQuote(wirePidFile)}")
            appendLine("cd ${shellQuote(cwd)} || exit 1")
            // 两个分支都必须后台化：setsid 只是脱离会话，调用方仍会前台等待长驻命令结束。
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("else")
            appendLine("  sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("fi")
            appendLine("i=0")
            appendLine("while [ \$i -lt 30 ]; do")
            appendLine("  [ -s ${shellQuote(wirePidFile)} ] && break")
            appendLine("  i=\$((i+1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            append("cat ${shellQuote(wirePidFile)} 2>/dev/null")
        }
        // 启动器必须裸跑：托管壳的 wait 会卡住已后台化的子进程，退出前的同组清理也会杀死它们。
        val result = launchRaw(identity, environment, launcherScript, timeoutSeconds = 10)
        val outputText = result.output.decodeToString().trim()
        val pid = outputText.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
        if (result.exitCode != 0 || pid == null || pid <= 1) {
            val message = outputText.ifBlank { "exit=${result.exitCode}" }
            logger.warn(
                "Agent terminal daemon action=start outcome=failed environment=${environment.wireName} " +
                    "exitCode=${result.exitCode} errorChars=${message.length}"
            )
            return DaemonStartResult.Failed("DAEMON_START_FAILED", message)
        }
        val task = DetachedTask(
            id = id,
            pid = pid,
            token = token,
            command = command,
            cwd = cwd,
            identity = identity,
            environment = environment,
            logPath = "$wireDir/$id.log",
            startedAt = System.currentTimeMillis(),
        )
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked() + task)
        }
        logger.info(
            "Agent terminal daemon action=start outcome=started taskId=$id " +
                "environment=${environment.wireName} identity=$identity commandChars=${command.length}"
        )
        return DaemonStartResult.Started(task)
    }

    /** 巡检全部记录：活着的重新认领，死掉的保留记录供查看日志，并清理超额的已退出记录。 */
    fun list(): List<DetachedTaskStatus> {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        if (tasks.isEmpty()) return emptyList()
        // 不同身份分组巡检：/proc/<pid>/environ 只有进程属主与 root 可读，用任务自身身份探测最稳。
        val aliveById = mutableMapOf<String, Boolean>()
        tasks.groupBy { it.identity }.forEach { (identity, group) ->
            val probeScript = aliveCheckFunction() + "\n" + group.joinToString("\n") { task ->
                "if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; " +
                    "then echo '${task.id} 1'; else echo '${task.id} 0'; fi"
            }
            val result = runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = probeScript,
                timeoutSeconds = 15,
            )
            if (result.exitCode == 0) {
                result.output.decodeToString().lineSequence().forEach { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) aliveById[parts[0]] = parts[1] == "1"
                }
            } else {
                logger.warn(
                    "Agent terminal daemon action=list outcome=probe_failed identity=$identity exitCode=${result.exitCode}"
                )
            }
        }
        // 探测失败的任务保守视为仍在运行，不误报死亡；确认死亡的才允许被 prune 清掉。
        val statuses = tasks.map { DetachedTaskStatus(it, running = aliveById[it.id] ?: true) }
        if (aliveById.isNotEmpty()) {
            pruneExitedRecords(statuses)
        }
        return statuses
    }

    fun readLogs(id: String, maxBytes: Int = MAX_LOG_READ_BYTES): DaemonLogsResult {
        val task = synchronized(RECORDS_LOCK) { loadTasksLocked() }.firstOrNull { it.id == id }
            ?: return DaemonLogsResult(ok = false, code = "TASK_NOT_FOUND", message = "未找到守护任务：$id")
        val limit = maxBytes.coerceIn(1_024, MAX_LOG_READ_BYTES)
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "tail -c $limit ${shellQuote(hostDaemonPath(task, task.logPath))} 2>/dev/null",
            timeoutSeconds = 15,
        )
        if (result.exitCode != 0) {
            return DaemonLogsResult(ok = false, code = "LOGS_UNAVAILABLE", message = "日志不可用：exit=${result.exitCode}")
        }
        return DaemonLogsResult(
            ok = true,
            text = result.output.decodeToString(),
            truncated = result.output.size >= limit,
        )
    }

    /** 停止并删除记录与日志；进程已退出时等价于清理记录。返回 false 表示任务不存在。 */
    fun stop(id: String): Boolean {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        val task = tasks.firstOrNull { it.id == id } ?: return false
        val stopScript = buildString {
            appendLine(aliveCheckFunction())
            appendLine("if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            // setsid 路径下 pgid==pid，按组一次收整棵树；无 setsid 的退化环境只杀主进程。
            appendLine("  if [ -d /proc ]; then kill -TERM -${task.pid} 2>/dev/null; else kill -TERM ${task.pid} 2>/dev/null; fi")
            appendLine("  sleep 1")
            appendLine("  if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            appendLine("    if [ -d /proc ]; then kill -9 -${task.pid} 2>/dev/null; else kill -9 ${task.pid} 2>/dev/null; fi")
            appendLine("  fi")
            appendLine("fi")
            append("rm -f ${shellQuote(hostDaemonPath(task, task.logPath))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".pid"))}")
        }
        runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = stopScript,
            timeoutSeconds = 10,
        )
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id == id })
        }
        logger.info(
            "Agent terminal daemon action=stop outcome=stopped taskId=$id " +
                "environment=${task.environment.wireName}"
        )
        return true
    }

    /** 存活判定校验 ownership token：PID 被系统复用时不会把无关进程当作本任务。无 /proc 的环境退化为存在性探测。 */
    private fun aliveCheckFunction(): String =
        "eta_alive() { " +
            "if [ -d /proc ]; then " +
                "[ -d /proc/\$1 ] && " +
                "tr '\\000' '\\n' < /proc/\$1/environ 2>/dev/null | grep -Fqx \"\$2\"; " +
            "else kill -0 \"\$1\" 2>/dev/null; fi; " +
            "}"

    private fun ownerProof(task: DetachedTask): String = "$ETA_PROCESS_OWNER_ENV=${task.token}"

    /**
     * 裸启动器：不经过 [ShellProcessSupervisor] 的托管壳（壳的 wait 与退出清理正是守护任务要逃离的回收语义），
     * 只做启动、输出收集与超时防御。Linux 环境仍复用同一套 unshare + chroot 包装。
     */
    private fun launchRaw(
        identity: String,
        environment: TerminalEnvironment,
        script: String,
        timeoutSeconds: Long,
    ): OneShotShellResult {
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> oneShotSupervisor.buildAndroidPayload(identity, script)
            else -> {
                val rootfs = rootfsPath(environment)
                    ?: return OneShotShellResult(-1, ByteArray(0), "Linux rootfs 未配置".toByteArray())
                oneShotSupervisor.buildLinuxPayload(rootfs, script, linuxSharedMountsProvider())
            }
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", payload)
            } else {
                ProcessBuilder("sh", "-c", payload)
            }
            builder.redirectErrorStream(true).start()
        }.getOrElse {
            return OneShotShellResult(-1, ByteArray(0), (it.message ?: "无法启动进程").toByteArray())
        }
        val output = ByteArrayOutputCollector()
        val reader = thread(name = "agent-daemon-launch-reader", isDaemon = true) {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val finished = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { reader.join(500) }
            return OneShotShellResult(-2, output.bytes(), "命令执行超时".toByteArray())
        }
        runCatching { reader.join(500) }
        return OneShotShellResult(process.exitValue(), output.bytes(), ByteArray(0))
    }

    private fun wireDaemonDir(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DAEMON_DIR else daemonDir

    /**
     * Linux 任务的 /workspace 是宿主工作目录的 bind 视图，日志与 pid 文件的物理位置仍在宿主。
     * 读写一律走宿主路径，避免为读日志专门进入 chroot。
     */
    internal fun hostDaemonPath(task: DetachedTask, wirePath: String): String =
        if (task.environment.isLinux && wirePath.startsWith(LINUX_DAEMON_DIR)) {
            DEFAULT_DAEMON_DIR + wirePath.removePrefix(LINUX_DAEMON_DIR)
        } else {
            wirePath
        }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun pruneExitedRecords(statuses: List<DetachedTaskStatus>) {
        val excess = statuses.size - MAX_RETAINED_RECORDS
        if (excess <= 0) return
        val victims = statuses
            .filter { !it.running }
            .sortedBy { it.task.startedAt }
            .take(excess)
        if (victims.isEmpty()) return
        victims.groupBy { it.task.identity }.forEach { (identity, group) ->
            val removeScript = group.joinToString("\n") {
                "rm -f ${shellQuote(hostDaemonPath(it.task, it.task.logPath))} " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".pid"))
            }
            runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = removeScript,
                timeoutSeconds = 10,
            )
        }
        val victimIds = victims.mapTo(mutableSetOf()) { it.task.id }
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id in victimIds })
        }
    }

    private fun loadTasksLocked(): MutableList<DetachedTask> {
        if (!recordsFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            (0 until array.length()).mapTo(mutableListOf()) { index ->
                array.getJSONObject(index).toTask()
            }
        }.getOrElse {
            logger.warn("Agent terminal daemon records corrupted, resetting")
            mutableListOf()
        }
    }

    private fun saveTasksLocked(tasks: List<DetachedTask>) {
        runCatching {
            recordsFile.parentFile?.mkdirs()
            val array = JSONArray()
            tasks.forEach { array.put(it.toJson()) }
            val tmp = File(recordsFile.parentFile, recordsFile.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(recordsFile)) {
                recordsFile.writeText(array.toString())
            }
        }.onFailure { logger.warn("Agent terminal daemon records save failed") }
    }

    private fun DetachedTask.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("pid", pid)
        .put("token", token)
        .put("command", command)
        .put("cwd", cwd)
        .put("identity", identity)
        .put("environment", environment.wireName)
        .put("log_path", logPath)
        .put("started_at", startedAt)

    private fun JSONObject.toTask(): DetachedTask = DetachedTask(
        id = getString("id"),
        pid = getLong("pid"),
        token = getString("token"),
        command = getString("command"),
        cwd = getString("cwd"),
        identity = getString("identity"),
        environment = when (optString("environment")) {
            TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
            TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
            else -> TerminalEnvironment.ANDROID
        },
        logPath = getString("log_path"),
        startedAt = getLong("started_at"),
    )
}
