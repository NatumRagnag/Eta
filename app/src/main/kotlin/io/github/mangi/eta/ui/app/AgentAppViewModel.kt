package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.linuxPackageProfileReady
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.launch

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    val terminalStore = UserTerminalStore(
        context = application,
        scope = viewModelScope,
    )
    val consoleStore = ConsoleStore(
        context = application,
        scope = viewModelScope,
    )
    val kimiWebLauncher = KimiWebLauncher(
        context = application,
        daemonSupervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(application),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(application, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
    )

    /** Kimi Code profile 是否已就绪；未就绪时入口应引导到安装页。 */
    fun kimiWebReady(): Boolean {
        val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
        val rootfs = LinuxEnvironmentPaths.rootfsDir(getApplication(), distribution)
        return linuxPackageProfileReady(rootfs, LinuxPackageProfiles.KIMI)
    }

    fun launchKimiWeb(onFinished: (KimiWebLaunchResult) -> Unit) {
        viewModelScope.launch {
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            onFinished(kimiWebLauncher.launch(distribution.terminalEnvironment))
        }
    }

    override fun onCleared() {
        terminalStore.close()
        consoleStore.close()
        super.onCleared()
    }
}
