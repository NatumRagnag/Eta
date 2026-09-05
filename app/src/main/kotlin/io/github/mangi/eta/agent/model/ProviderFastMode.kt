package io.github.mangi.eta.agent.model

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject

/** 请求级尝试加速，不按模型名称限制，也不改变推理等级。 */
internal object ProviderFastMode {
    const val ANTHROPIC_BETA = "fast-mode-2026-02-01"
    private const val MAX_ERROR_BYTES = 64L * 1024
    private val fastError = Regex(
        "service[_ -]tier|\\bspeed\\b|\\bfast[ _-]mode\\b|\\bpriority[ _-](tier|processing)\\b",
        RegexOption.IGNORE_CASE,
    )

    private data class Fallback(val original: Request, val endpoint: EndpointKind)

    fun prepare(request: Request, enabled: Boolean, endpoint: EndpointKind): Request {
        if (!enabled) return request
        val originalBody = request.body ?: return request
        val body = Buffer().use { buffer ->
            originalBody.writeTo(buffer)
            JSONObject(buffer.readUtf8())
        }
        val key = if (endpoint == EndpointKind.ANTHROPIC_MESSAGES) "speed" else "service_tier"
        // 用户在高级请求体中显式指定的速度参数优先，不接管其回退语义。
        if (body.has(key)) return request
        body.put(key, if (endpoint == EndpointKind.ANTHROPIC_MESSAGES) "fast" else "priority")
        return request.newBuilder()
            .post(body.toString().toRequestBody(originalBody.contentType()))
            .apply {
                if (endpoint == EndpointKind.ANTHROPIC_MESSAGES) {
                    val betas = request.headers.values("anthropic-beta")
                        .flatMap { it.split(',') }
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    header("anthropic-beta", (betas + ANTHROPIC_BETA).distinct().joinToString(","))
                }
            }
            .tag(Fallback::class.java, Fallback(request, endpoint))
            .build()
    }

    val fallbackInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val fallback = request.tag(Fallback::class.java)
        if (fallback == null || !shouldFallback(response, fallback.endpoint)) {
            response
        } else {
            // 只在 HTTP 层明确拒绝加速时重试一次；不重放已开始输出的流。
            // 原始请求保留自定义参数、其他 beta 和推理设置，同一个 Call 继续负责取消。
            response.close()
            chain.proceed(fallback.original)
        }
    }

    private fun shouldFallback(response: Response, endpoint: EndpointKind): Boolean {
        if (response.code !in setOf(400, 403, 422, 429, 503, 529)) return false
        if (endpoint == EndpointKind.ANTHROPIC_MESSAGES && response.code == 429 &&
            response.headers.names().any { it.startsWith("anthropic-fast-", ignoreCase = true) }
        ) return true
        return fastError.containsMatchIn(response.peekBody(MAX_ERROR_BYTES).string())
    }
}
