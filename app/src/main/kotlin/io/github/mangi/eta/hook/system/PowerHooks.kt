package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.hook.hyperos.HyperOsPowerPolicy
import io.github.libxposed.api.XposedModule

internal object PowerHooks {
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    @Volatile
    private var lastInterceptUptime = 0L

    private enum class LaunchResult {
        LAUNCHED,
        ACTIVITY_FALLBACK_REQUIRED,
        NOT_HANDLED
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Power")
        return hooks.install {
            // 厂商快捷键路径都在热路径即时读取目标，切换后无需重启目标应用。
            hookOplusSpeechHandler(hooks, classLoader)
        }
    }

    internal fun hookHyperOsShortcutActions(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val logger = hooks.logger
        val shortcutClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.MIUI_SHORTCUT_ACTIONS_UTILS_CLASS,
        ) ?: run {
            hooks.skipped(
                "system.power-hyperos-shortcut",
                "ShortCutActionsUtils",
                "当前系统未提供 HyperOS 快捷动作分发器",
            )
            return
        }
        val parameters = arrayOf(
            String::class.java,
            String::class.java,
            Bundle::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        val triggerMethods = listOf(parameters, parameters + String::class.java).mapNotNull {
            HookSupport.findMethod(shortcutClass, "triggerFunction", *it)
                ?.takeIf { method -> method.returnType == Boolean::class.javaPrimitiveType }
        }
        if (triggerMethods.isEmpty()) {
            hooks.missing(
                id = "system.power-hyperos-shortcut",
                description = "ShortCutActionsUtils.triggerFunction",
                detail = "未找到 HyperOS ShortCutActionsUtils.triggerFunction 的四参数或五参数签名",
            )
        }

        triggerMethods.forEach { triggerMethod ->
            hooks.intercept(
                id = "system.power-hyperos-shortcut-${triggerMethod.parameterCount}",
                executable = triggerMethod,
                description = "HyperOS ShortCutActionsUtils.triggerFunction",
            ) { chain ->
                val shortcutActions = chain.getThisObject()
                val context = HookSupport.getFieldValue(shortcutActions, "mContext") as? Context
                val configuredTarget = Prefs.powerAssistantTarget()
                val target = if (context != null) {
                    resolveHyperOsAssistantTarget(context, configuredTarget)
                } else {
                    configuredTarget
                }
                val function = chain.getArg(0) as? String
                val shortcut = chain.getArg(1) as? String
                if (!shouldInterceptHyperOsVoiceAssistant(function, shortcut, target)) {
                    return@intercept chain.proceed()
                }

                val binding = assistantBindingFor(target) ?: return@intercept chain.proceed()
                if (context == null) {
                    logger.warnThrottled("hyperos_shortcut_missing_context") {
                        "HyperOS 电源键路由缺少 mContext，回退原逻辑"
                    }
                    return@intercept chain.proceed()
                }
                if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
                    logger.warnThrottled("hyperos_shortcut_${target.persistedValue}_missing") {
                        "HyperOS 电源键目标 ${binding.displayName} 未安装，阻止回退超级小爱"
                    }
                    return@intercept false
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
                    logger.debug { "HyperOSShortcut: 命中去重窗口，吞掉重复触发" }
                    return@intercept true
                }

                val launched = AssistantManager.showAssistantSession(
                    context = context,
                    target = target,
                    logger = logger,
                    source = "HyperOSShortcut",
                    logFailures = false,
                ) || tryStartHyperOsAssistantActivity(
                    context = context,
                    target = target,
                    binding = binding,
                    logger = logger,
                )

                if (!launched) {
                    logger.warnThrottled("hyperos_shortcut_${target.persistedValue}_launch_failed") {
                        "HyperOS 电源键无法启动 ${binding.displayName}，阻止回退超级小爱"
                    }
                    return@intercept false
                }

                markLaunchSuccess(now)
                performHyperOsShortcutHaptic(
                    shortcutActions = shortcutActions,
                    function = function.orEmpty(),
                    shortcut = shortcut.orEmpty(),
                    requested = chain.getArg(3) as? Boolean ?: false,
                    effectKey = if (triggerMethod.parameterCount > 4) chain.getArg(4) as? String else null,
                    logger = logger,
                )
                logger.debug { "HyperOSShortcut: 已把长按电源键路由到 ${binding.displayName}" }
                true
            }
        }

        // 这台 HyperOS 的电源键路径会穿过 triggerFunction 的优化调用，导致上面的
        // 精确入口没有收到回调；launchVoiceAssistant 是真正写死并启动小爱的最终出口。
        // 仅当用户已选择 Eta/Gemini 时接管，OEM 模式仍完整保留原行为。
        val launchVoiceAssistantMethod = HookSupport.findMethod(
            shortcutClass,
            "launchVoiceAssistant",
            String::class.java,
            Bundle::class.java,
        )
        if (launchVoiceAssistantMethod == null) {
            hooks.missing(
                id = "system.power-hyperos-voice-launch",
                description = "ShortCutActionsUtils.launchVoiceAssistant",
                detail = "未找到 HyperOS ShortCutActionsUtils.launchVoiceAssistant(String,Bundle)",
            )
            return
        }
        hooks.intercept(
            id = "system.power-hyperos-voice-launch",
            executable = launchVoiceAssistantMethod,
            description = "HyperOS ShortCutActionsUtils.launchVoiceAssistant",
        ) { chain ->
            val shortcutActions = chain.getThisObject()
            val context = HookSupport.getFieldValue(shortcutActions, "mContext") as? Context
                ?: return@intercept chain.proceed()
            val target = resolveHyperOsAssistantTarget(context, Prefs.powerAssistantTarget())
            if (!shouldInterceptHyperOsVoiceAssistantLaunch(target)) {
                return@intercept chain.proceed()
            }
            val binding = assistantBindingFor(target) ?: return@intercept chain.proceed()
            if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
                logger.warnThrottled("hyperos_voice_launch_${target.persistedValue}_missing") {
                    "HyperOS 助理目标 ${binding.displayName} 未安装，阻止回退超级小爱"
                }
                return@intercept false
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
                logger.debug { "HyperOSVoiceLaunch: 命中去重窗口，吞掉重复触发" }
                return@intercept true
            }
            val launched = AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = "HyperOSVoiceLaunch",
                logFailures = false,
            ) || tryStartHyperOsAssistantActivity(
                context = context,
                target = target,
                binding = binding,
                logger = logger,
            )
            if (!launched) {
                logger.warnThrottled("hyperos_voice_launch_${target.persistedValue}_failed") {
                    "HyperOS 助理出口无法启动 ${binding.displayName}，阻止回退超级小爱"
                }
                return@intercept false
            }

            markLaunchSuccess(now)
            val shortcut = chain.getArg(0) as? String
            logger.debug {
                "HyperOSVoiceLaunch: 已把小爱启动出口路由到 ${binding.displayName}, shortcut=$shortcut"
            }
            true
        }
    }

    private fun resolveHyperOsAssistantTarget(
        context: Context,
        configuredTarget: PowerAssistantTarget,
    ): PowerAssistantTarget {
        if (configuredTarget != PowerAssistantTarget.OEM) return configuredTarget
        return when {
            AssistantManager.isAssistantConfigured(context, PowerAssistantTarget.ETA) ->
                PowerAssistantTarget.ETA
            AssistantManager.isAssistantConfigured(context, PowerAssistantTarget.GEMINI) ->
                PowerAssistantTarget.GEMINI
            else -> PowerAssistantTarget.OEM
        }
    }

    private fun tryStartHyperOsAssistantActivity(
        context: Context,
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
    ): Boolean {
        val actions = when (target) {
            PowerAssistantTarget.OEM -> return false
            PowerAssistantTarget.ETA -> listOf(Intent.ACTION_ASSIST)
            PowerAssistantTarget.GEMINI -> listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)
        }
        return actions.any { action ->
            val intent = Intent(action).apply {
                setPackage(binding.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!runCatching { HookSupport.resolvesActivity(context, intent) }.getOrDefault(false)) {
                return@any false
            }
            runCatching {
                context.startActivity(intent)
                true
            }.getOrElse { throwable ->
                logger.warnThrottled("hyperos_shortcut_${target.persistedValue}_${action}_failed") {
                    "HyperOSShortcut: $action 启动 ${binding.displayName} 失败，" +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        }
    }

    private fun performHyperOsShortcutHaptic(
        shortcutActions: Any,
        function: String,
        shortcut: String,
        requested: Boolean,
        effectKey: String?,
        logger: ModuleLogger,
    ) {
        if (!requested || effectKey.isNullOrBlank()) return
        val method = HookSupport.findMethod(
            shortcutActions.javaClass,
            "triggerHapticFeedback",
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
        ) ?: return
        runCatching {
            method.invoke(shortcutActions, true, shortcut, function, true, effectKey)
        }.onFailure { throwable ->
            logger.warnThrottled("hyperos_shortcut_haptic_failed") {
                "HyperOSShortcut: 原生震感补发失败，type=${throwable.safeLogType()}"
            }
        }
    }

    internal fun shouldInterceptHyperOsVoiceAssistant(
        function: String?,
        shortcut: String?,
        target: PowerAssistantTarget,
    ): Boolean = target != PowerAssistantTarget.OEM &&
        HyperOsPowerPolicy.isAssistantShortcut(function, shortcut)

    internal fun shouldInterceptHyperOsVoiceAssistantLaunch(
        target: PowerAssistantTarget,
    ): Boolean = target != PowerAssistantTarget.OEM

    private fun hookOplusSpeechHandler(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            hooks.missing(
                id = "system.power-assist-message",
                description = "OplusSpeechHandler.handleMessage",
                detail = "未找到 OplusSpeechHandler.handleMessage(Message)"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-message",
            executable = handleMessageMethod,
            description = "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@intercept chain.proceed()
            }

            val target = Prefs.powerAssistantTarget()
            val binding = assistantBindingFor(target)
            if (binding == null) {
                return@intercept chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled("oplus_speech_missing_pwm") {
                    "OplusSpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                }
                return@intercept chain.proceed()
            }

            when (tryLaunchAssistant(
                target = target,
                binding = binding,
                logger = logger,
                phoneWindowManager = pwm,
                source = "OplusSpeechHandler"
            )) {
                LaunchResult.LAUNCHED -> null
                LaunchResult.ACTIVITY_FALLBACK_REQUIRED -> {
                    val activityStarted = tryStartAssistantActivityFallback(
                        target = target,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    // Activity 兜底能处理本次触发，但仍需后台修复首选 voiceinteraction 路径。
                    scheduleBackgroundRecovery(
                        handler = handler,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    if (activityStarted) {
                        null
                    } else {
                        // 当前触发不等待后台修复；所有快速路径失败后立即回退小布。
                        chain.proceed()
                    }
                }
                LaunchResult.NOT_HANDLED -> chain.proceed()
            }
        }
    }

    private fun tryLaunchAssistant(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context") {
                "$source 缺少 mContext，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
            logger.warnThrottled("${source}_${target.persistedValue}_missing") {
                "$source: ${binding.displayName} 未安装，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$source: 命中去重窗口，直接吞掉重复触发" }
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = source,
                logFailures = false
            )) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 voiceinteraction 启动 ${binding.displayName}" }
            return LaunchResult.LAUNCHED
        }

        return LaunchResult.ACTIVITY_FALLBACK_REQUIRED
    }

    private fun tryStartAssistantActivityFallback(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false
        val now = SystemClock.uptimeMillis()
        return when (target) {
            PowerAssistantTarget.OEM -> false
            PowerAssistantTarget.GEMINI -> startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_ASSIST,
            ) || startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_VOICE_COMMAND,
            )
            PowerAssistantTarget.ETA -> {
                if (!AssistantManager.isAssistantConfigured(context, target)) {
                    false
                } else {
                    startAssistantActivity(
                        context = context,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = phoneWindowManager,
                        source = source,
                        now = now,
                        action = Intent.ACTION_ASSIST,
                    )
                }
            }
        }
    }

    private fun startAssistantActivity(
        context: Context,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(binding.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolves = runCatching { HookSupport.resolvesActivity(context, intent) }
            .getOrElse { throwable ->
                logger.warnThrottled("${source}_${action}_resolve_failed") {
                    "$source: 查询 ${binding.displayName} $action 入口失败，" +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        if (!resolves) {
            logger.warnThrottled("${source}_${action}_missing") {
                "$source: ${binding.displayName} 未暴露 $action，回退原逻辑"
            }
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 $action 启动 ${binding.displayName}" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_${action}_failed") {
                "$source: $action 启动失败，回退原逻辑，type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug { "$source: 已补发 Oplus 原生助理震感" }
            return
        }

        logger.warnThrottled("${source}_assistant_haptic_missing") {
            "$source: 未找到 Oplus 原生长按助理震感入口"
        }
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun scheduleBackgroundRecovery(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG) ||
            Prefs.powerAssistantTarget() == PowerAssistantTarget.OEM
        ) {
            return
        }
        if (handler == null) {
            logger.warnThrottled("${source}_recovery_missing_handler") {
                "$source: 无法取得 OplusSpeechHandler 实例，跳过后台配置修复"
            }
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_recovery_missing_context") {
                "$source: 无法取得 mContext，跳过后台配置修复"
            }
            return
        }

        val scheduled = AssistantManager.scheduleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
        )
        if (!scheduled) {
            logger.warnThrottled("${source}_configuration_schedule_failed") {
                "$source: 默认助理后台修复无法入队"
            }
        } else {
            logger.warnThrottled("${source}_assistant_recovery_pending") {
                "$source: voiceinteraction 失败，已在后台修复默认助理配置"
            }
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
