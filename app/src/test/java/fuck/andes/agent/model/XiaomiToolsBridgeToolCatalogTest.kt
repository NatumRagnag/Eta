package fuck.andes.agent.model

import fuck.andes.agent.xiaomi.XiaomiToolsBridgeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiToolsBridgeToolCatalogTest {
    @Test
    fun `risk flags control Xiaomi bridge tool visibility`() {
        val catalog = XiaomiToolsBridgeProtocol.Catalog(
            listOf(
                definition(
                    "com.aios.tools.builtin.system.WifiInfoTool",
                    "wifi_info",
                ),
                definition(
                    "com.aios.tools.builtin.system.ReadSMSTool",
                    "read_sms",
                ),
                definition(
                    "com.aios.tools.builtin.system.SMSTool",
                    "send_sms",
                ),
            ),
        )

        val direct = tools(catalog, direct = true, reads = false, actions = false)
        val all = tools(catalog, direct = true, reads = true, actions = true)

        assertTrue("xiaomi_wifi_info" in direct)
        assertFalse("xiaomi_read_sms" in direct)
        assertFalse("xiaomi_send_sms" in direct)
        assertTrue("xiaomi_read_sms" in all)
        assertTrue("xiaomi_send_sms" in all)
        assertEquals(all.size, all.toSet().size)
    }

    private fun tools(
        catalog: XiaomiToolsBridgeProtocol.Catalog,
        direct: Boolean,
        reads: Boolean,
        actions: Boolean,
    ): List<String> {
        val array = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = direct,
            deviceSensitiveReadTools = reads,
            deviceSensitiveActionTools = actions,
            xiaomiToolsBridgeCatalog = catalog,
        )
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).getJSONObject("function").getString("name")
        }
    }

    private fun definition(targetClass: String, rawName: String) = requireNotNull(
        XiaomiToolsBridgeProtocol.definitionFromTarget(
            targetClassName = targetClass,
            rawName = rawName,
            description = "test",
            parametersJson = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .toString(),
        ),
    )
}
