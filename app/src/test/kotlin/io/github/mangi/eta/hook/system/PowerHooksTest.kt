package io.github.mangi.eta.hook.system

import io.github.mangi.eta.config.PowerAssistantTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerHooksTest {
    @Test
    fun `hyperos intercepts only long press power voice assistant for managed targets`() {
        assertTrue(
            PowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.ETA,
            ),
        )
        assertTrue(
            PowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.GEMINI,
            ),
        )
    }

    @Test
    fun `hyperos preserves oem and unrelated shortcuts`() {
        assertFalse(
            PowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.OEM,
            ),
        )
        assertFalse(
            PowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "keyboard",
                target = PowerAssistantTarget.ETA,
            ),
        )
        assertFalse(
            PowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_xiaoai_screen_identification",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.ETA,
            ),
        )
    }

    @Test
    fun `hyperos final XiaoAi launch exit follows managed assistant target`() {
        assertTrue(PowerHooks.shouldInterceptHyperOsVoiceAssistantLaunch(PowerAssistantTarget.ETA))
        assertTrue(PowerHooks.shouldInterceptHyperOsVoiceAssistantLaunch(PowerAssistantTarget.GEMINI))
        assertFalse(PowerHooks.shouldInterceptHyperOsVoiceAssistantLaunch(PowerAssistantTarget.OEM))
    }
}
