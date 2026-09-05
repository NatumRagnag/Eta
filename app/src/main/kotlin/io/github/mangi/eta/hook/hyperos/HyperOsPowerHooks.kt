package io.github.mangi.eta.hook.hyperos

import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.system.PowerHooks

internal object HyperOsPowerHooks {
    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsPower")
        return hooks.install {
            // 共用分支已有的目标解析、去重和最终出口兜底，避免重复接管同一快捷动作。
            PowerHooks.hookHyperOsShortcutActions(hooks, loader)
        }
    }
}
