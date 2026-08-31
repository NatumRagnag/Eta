package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.libxposed.api.XposedModule

internal object ContextualSearchHooks {

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ContextualSearch")
        return hooks.install {
            // ContextualSearch 服务补齐是一圈即搜的底层依赖（被 ColorOS 砍掉），不可选。
            hookContextualSearchBootstrap(module, hooks, classLoader)
            hookContextualSearchPackage(hooks, classLoader)
            hookContextualSearchPermission(hooks, classLoader)
        }
    }

    private fun hookContextualSearchBootstrap(
        module: XposedModule,
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        // 当前设备 2026-03-26 的重启日志已经证明，真正稳定生效的是 startOtherServices 末尾的补启动兜底。
        // 这里直接守住系统服务启动尾段，不再把 deviceHasConfigString 当成唯一生效点。
        val systemServerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val timingsClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS)
        val startOtherServicesMethod = if (systemServerClass != null && timingsClass != null) {
            HookSupport.findMethod(systemServerClass, "startOtherServices", timingsClass)
        } else {
            null
        }
        if (startOtherServicesMethod == null) {
            hooks.missing(
                id = "system.contextual-search-bootstrap",
                description = "SystemServer.startOtherServices",
                detail = "未找到 SystemServer.startOtherServices(TimingsTraceAndSlog)"
            )
            return
        }

        HookSupport.deoptimize(
            module,
            logger,
            startOtherServicesMethod,
            "SystemServer.startOtherServices(TimingsTraceAndSlog)"
        )
        hooks.intercept(
            id = "system.contextual-search-bootstrap",
            executable = startOtherServicesMethod,
            description = "SystemServer.startOtherServices"
        ) { chain ->
            val result = chain.proceed()
            ensureContextualSearchService(module, logger, classLoader, chain.getThisObject(), "startOtherServices")
            result
        }
    }

    private fun hookContextualSearchPackage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "getContextualSearchPackageName") }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-package",
                description = "ContextualSearchManagerService.getContextualSearchPackageName",
                detail = "未找到 ContextualSearchManagerService.getContextualSearchPackageName()"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-package",
            executable = method,
            description = "ContextualSearchManagerService.getContextualSearchPackageName"
        ) { ModuleConfig.GOOGLE_PACKAGE }
    }

    private fun hookContextualSearchPermission(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "enforcePermission", String::class.java) }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-permission",
                description = "ContextualSearchManagerService.enforcePermission",
                detail = "未找到 ContextualSearchManagerService.enforcePermission(String)"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-permission",
            executable = method,
            description = "ContextualSearchManagerService.enforcePermission"
        ) { chain ->
            val functionName = chain.getArg(0) as? String
            if (functionName == "startContextualSearch" && isAllowedContextualSearchUid(chain.getThisObject())) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun isAllowedContextualSearchUid(serviceInstance: Any): Boolean {
        val context = HookSupport.invokeNoArgs(serviceInstance, "getContext") as? Context
            ?: HookSupport.getFieldValue(serviceInstance, "mContext") as? Context
            ?: return false
        // IContextualSearchManager 是 oneway AIDL，调用 PID 固定不可用；Android 权限主体本身也是 UID。
        // 因此这里只能按 UID 鉴权，getPackagesForUid 的结果代表整个 shared UID 安全边界。
        val callingUid = Binder.getCallingUid()
        val packages = try {
            context.packageManager.getPackagesForUid(callingUid)
        } catch (_: Exception) {
            null
        } ?: return false
        return packages.contains(ModuleConfig.SYSTEM_UI_PACKAGE) ||
            packages.contains(ModuleConfig.COLOR_DIRECT_PACKAGE)
    }

    private fun ensureContextualSearchService(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
        systemServerInstance: Any,
        source: String
    ) {
        if (isContextualSearchServiceAlive()) {
            logger.debug { "$source: contextual_search service 已存在" }
            return
        }

        val systemServiceManager = HookSupport.getFieldValue(systemServerInstance, "mSystemServiceManager")
        if (systemServiceManager == null) {
            logger.warn("$source: mSystemServiceManager 为空，无法补启动 contextual_search")
            return
        }

        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        if (serviceClass == null) {
            logger.warn("$source: 未找到 ContextualSearchManagerService class，无法补启动")
            return
        }

        val startServiceMethod = HookSupport.findMethod(
            systemServiceManager.javaClass,
            "startService",
            Class::class.java
        )
        if (startServiceMethod == null) {
            logger.warn("$source: 未找到 SystemServiceManager.startService(Class)")
            return
        }

        try {
            module.getInvoker(startServiceMethod).invoke(systemServiceManager, serviceClass)
            if (isContextualSearchServiceAlive()) {
                logger.debug { "$source: 已补启动 ContextualSearchManagerService" }
            } else {
                logger.warn("$source: 已调用 startService(Class)，但 contextual_search 仍不可用")
            }
        } catch (exception: Exception) {
            // XposedFrameworkError 属于 Error，必须继续交给框架处理。
            logger.error("$source: 补启动 ContextualSearchManagerService 失败", exception)
        }
    }

    private fun isContextualSearchServiceAlive(): Boolean =
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
            binder?.isBinderAlive == true
        }.getOrDefault(false)
}
