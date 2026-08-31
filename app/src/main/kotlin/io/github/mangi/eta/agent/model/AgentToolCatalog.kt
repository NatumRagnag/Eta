package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import org.json.JSONArray

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        entryTools: Set<String> = emptySet(),
        xiaomiToolsBridgeCatalog: XiaomiToolsBridgeProtocol.Catalog =
            XiaomiToolsBridgeProtocol.Catalog.EMPTY,
        hostCapabilities: Set<String> = emptySet(),
    ): JSONArray =
        JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) AgentBrowserToolCatalog.appendTo(tools)
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools)
            XiaomiUiAgentToolCatalog.appendTo(tools, entryTools)
            xiaomiToolsBridgeCatalog.visibleDefinitions(
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            ).forEach { definition -> tools.put(definition.asModelTool()) }
            XiaomiHostToolCatalog.appendTo(
                tools = tools,
                capabilities = hostCapabilities,
                directTools = deviceDirectTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
        }
}
