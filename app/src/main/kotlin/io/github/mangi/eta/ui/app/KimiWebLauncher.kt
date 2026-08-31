package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal sealed interface KimiWebLaunchResult {
    data class Opened(val url: String) : KimiWebLaunchResult
    data class Failed(val code: String) : KimiWebLaunchResult
}

/**
 * 一键拉起 Kimi Web：确保 `kimi web` 以守护任务常驻（已在运行则复用，不重复起实例），
 * 从守护日志解析带 token 的本机地址后交给系统浏览器。
 */
internal class KimiWebLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    suspend fun launch(environment: TerminalEnvironment): KimiWebLaunchResult = withContext(Dispatchers.IO) {
        val existing = daemonSupervisor.list().firstOrNull { status ->
            status.running &&
                status.task.environment == environment &&
                status.task.command.trim() == KIMI_WEB_COMMAND
        }
        val taskId = when {
            existing != null -> existing.task.id
            else -> when (
                val started = daemonSupervisor.start(
                    command = KIMI_WEB_COMMAND,
                    cwd = "/workspace",
                    identity = "root",
                    environment = environment,
                )
            ) {
                is DaemonStartResult.Started -> started.task.id
                is DaemonStartResult.Failed -> return@withContext KimiWebLaunchResult.Failed("START_FAILED")
            }
        }
        val url = awaitWebUrl(taskId) ?: return@withContext KimiWebLaunchResult.Failed("URL_TIMEOUT")
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (opened) {
            KimiWebLaunchResult.Opened(url)
        } else {
            KimiWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
        }
    }

    /** kimi web 启动需要几秒；轮询守护日志直到出现带 token 的本机地址。 */
    private suspend fun awaitWebUrl(taskId: String): String? {
        repeat(WAIT_ATTEMPTS) {
            val logs = daemonSupervisor.readLogs(taskId)
            if (logs.ok) {
                WEB_URL_REGEX.find(logs.text)?.value?.let { return it }
            }
            delay(WAIT_INTERVAL_MS)
        }
        return null
    }

    private companion object {
        const val KIMI_WEB_COMMAND = "kimi web"
        const val WAIT_ATTEMPTS = 30
        const val WAIT_INTERVAL_MS = 500L

        // token 字符集收紧到 URL safe，避免把日志里的 ANSI 序列尾巴吃进来。
        val WEB_URL_REGEX = Regex("""http://127\.0\.0\.1:\d+/#token=[A-Za-z0-9_-]+""")
    }
}
