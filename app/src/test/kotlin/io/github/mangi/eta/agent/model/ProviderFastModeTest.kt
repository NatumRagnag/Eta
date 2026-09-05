package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProviderFastModeTest(endpointName: String) {
    private val endpoint = EndpointKind.valueOf(endpointName)
    private val anthropic = endpoint == EndpointKind.ANTHROPIC_MESSAGES
    private val fastKey = if (anthropic) "speed" else "service_tier"
    private val fastValue = if (anthropic) "fast" else "priority"
    private val provider = when (endpoint) {
        EndpointKind.CHAT_COMPLETIONS -> OpenAiChatCompletionsProvider
        EndpointKind.RESPONSES -> OpenAiResponsesProvider
        EndpointKind.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider
    }

    @Test
    fun fastWorksWithUnknownModelsAndPreservesReasoningAndCustomParameters() {
        withServer { url, requests ->
            val standard = request(url, enabled = false)
            complete(standard)
            complete(standard.copy(config = standard.config.copy(fastModeEnabled = true)))

            assertEquals(2, requests.size)
            assertFalse(requests[0].body.has(fastKey))
            assertEquals(fastValue, requests[1].body.remove(fastKey))
            assertEquals(Json.parseToJsonElement(requests[0].body.toString()), Json.parseToJsonElement(requests[1].body.toString()))
            assertEquals("unlisted-future-model", requests[1].body.getString("model"))
            assertEquals("kept", requests[1].body.getString("custom_field"))
            assertEquals("test-beta", requests[0].beta)
            assertEquals(
                if (anthropic) "test-beta,${ProviderFastMode.ANTHROPIC_BETA}" else "test-beta",
                requests[1].beta,
            )
        }
    }

    @Test
    fun unsupportedFastFallsBackOnceAndRestoresOriginalBodyAndHeaders() {
        withServer(reply = { index ->
            if (index == 0) Reply(400, """{"error":{"param":"$fastKey","message":"Unsupported parameter"}}""")
            else Reply(200, successStream())
        }) { url, requests ->
            assertEquals("ok", complete(request(url)).assistantMessage.getString("content"))
            assertEquals(2, requests.size)
            assertEquals(fastValue, requests[0].body.remove(fastKey))
            assertEquals(Json.parseToJsonElement(requests[0].body.toString()), Json.parseToJsonElement(requests[1].body.toString()))
            assertEquals("test-beta", requests[1].beta)
            assertFalse(requests[1].body.has(fastKey))
        }
    }

    @Test
    fun customSpeedOverrideRemainsAuthoritative() {
        withServer { url, requests ->
            val standard = request(url)
            val explicitValue = if (anthropic) "standard" else "default"
            complete(standard.copy(config = standard.config.copy(
                customBody = standard.config.customBody + CustomBody(fastKey, JsonPrimitive(explicitValue)),
            )))
            assertEquals(explicitValue, requests.single().body.getString(fastKey))
            assertEquals("test-beta", requests.single().beta)
        }
    }

    @Test
    fun unrelatedFailuresDoNotTriggerFastFallback() {
        for (code in listOf(400, 401, 403, 404, 422, 429, 500)) {
            withServer(reply = { Reply(code, """{"error":{"message":"Unrelated failure"}}""") }) { url, requests ->
                assertTrue(runCatching { complete(request(url)) }.isFailure)
                assertEquals("HTTP $code", 1, requests.size)
            }
        }
    }

    @Test
    fun explicitFastCapacityFailureFallsBack() {
        for (code in listOf(403, 422, 429, 503, 529)) {
            withServer(reply = { index ->
                if (index == 0) Reply(code, """{"error":{"message":"Fast mode is unavailable"}}""")
                else Reply(200, successStream())
            }) { url, requests ->
                assertEquals("ok", complete(request(url)).assistantMessage.getString("content"))
                assertEquals("HTTP $code", 2, requests.size)
            }
        }
    }

    @Test
    fun secondFailureIsSurfacedWithoutAnotherRetry() {
        withServer(reply = { Reply(400, """{"error":{"message":"Unsupported $fastKey"}}""") }) { url, requests ->
            assertTrue(runCatching { complete(request(url)) }.isFailure)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun successfulHttpStreamIsNeverReplayedOnAnErrorEvent() {
        withServer(reply = {
            Reply(200, "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"Unsupported $fastKey\"}}\n\n")
        }) { url, requests ->
            assertTrue(runCatching { complete(request(url)) }.isFailure)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun cancellationStillCancelsTheFallbackCall() {
        val fallbackStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val controller = AgentRunController()
        try {
            withServer(reply = { index ->
                if (index == 0) Reply(400, "Unsupported $fastKey")
                else {
                    fallbackStarted.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    Reply(200, successStream())
                }
            }) { url, requests ->
                val task = worker.submit<Throwable?> {
                    runCatching { provider.complete(request(url), controller) }.exceptionOrNull()
                }
                try {
                    assertTrue(fallbackStarted.await(5, TimeUnit.SECONDS))
                    controller.cancel()
                    assertTrue(task.get(5, TimeUnit.SECONDS) is AgentRunCancelledException)
                    assertEquals(2, requests.size)
                } finally {
                    releaseServer.countDown()
                }
            }
        } finally {
            controller.cancel()
            releaseServer.countDown()
            worker.shutdownNow()
        }
    }

    private fun request(url: String, enabled: Boolean = true) = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            providerType = if (anthropic) ProviderTypes.ANTHROPIC else ProviderTypes.OPENAI_COMPATIBLE,
            baseUrl = url,
            apiKey = "test-key",
            model = "unlisted-future-model",
            systemPrompt = "system",
            openAiEndpointMode = if (endpoint == EndpointKind.RESPONSES) OpenAiEndpointMode.RESPONSES
                else OpenAiEndpointMode.CHAT_COMPLETIONS,
            fastModeEnabled = enabled,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
            reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.HIGH)),
            customHeaders = listOf(CustomHeader("anthropic-beta", "test-beta")),
            customBody = listOf(CustomBody("custom_field", JsonPrimitive("kept"))),
        ),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
        tools = JSONArray(),
    )

    private fun complete(request: ProviderRequest) = provider.complete(request, AgentRunController())

    private data class Captured(val body: JSONObject, val beta: String?)
    private data class Reply(val code: Int, val body: String)

    private fun withServer(
        reply: (Int) -> Reply = { Reply(200, successStream()) },
        block: (String, List<Captured>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = CopyOnWriteArrayList<Captured>()
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                requests += Captured(JSONObject(body), exchange.requestHeaders.getFirst("anthropic-beta"))
                val result = reply(requests.size - 1)
                val bytes = result.body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", if (result.code == 200) "text/event-stream" else "application/json")
                exchange.sendResponseHeaders(result.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1", requests)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun successStream(): String = when (endpoint) {
        EndpointKind.CHAT_COMPLETIONS ->
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        EndpointKind.RESPONSES ->
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n"
        EndpointKind.ANTHROPIC_MESSAGES -> listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun endpoints(): List<Array<String>> = EndpointKind.entries.map { arrayOf(it.name) }
    }
}
