package dev.xd.fitnessbutler.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek(OpenAI 兼容)对话客户端:M2 大脑接线。
 * HttpURLConnection 直连,零第三方依赖;非流式(先跑通,M3 再议流式)。
 * 端点约定:apiBase 形如 https://xxx/v1(设置页填),实际请求 POST {apiBase}/chat/completions;
 * 若把完整 /chat/completions 地址整个填进来也认(2026-10-17 自测防呆,不再重复拼接)。
 * 密钥只从 settings 表经参数传入,本类绝不落日志/不缓存。
 */
object DeepSeekClient {

    /** 一条对话消息 */
    data class Msg(val role: String, val content: String)

    /** 结果:ok=false 时 error 为用户可读原因(不含密钥) */
    data class Reply(val ok: Boolean, val text: String, val error: String? = null)

    private const val TIMEOUT_MS = 60_000   // 非流式长回复可能超 30s,给足余量

    /**
     * 端点防呆(2026-10-17 自测):设置页常被填入完整 /chat/completions 地址,
     * 直接再拼会 404/401 误导成"密钥错"。已含完整路径就直用,其余补 /chat/completions。
     */
    private fun endpointOf(apiBase: String): String {
        val b = apiBase.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    /**
     * 发一轮对话。messages 含 system 在内的完整上下文(调用方组装)。
     * 网络/解析/服务端错误全部吞成 Reply(ok=false),绝不抛到 UI 线程。
     */
    fun chat(apiBase: String, model: String, apiKey: String, messages: List<Msg>): Reply {
        if (apiBase.isBlank()) return Reply(false, "", "API 地址未配置")
        if (model.isBlank()) return Reply(false, "", "模型名未配置")
        if (apiKey.isBlank()) return Reply(false, "", "密钥未配置")
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.7)
                put("max_tokens", 1024)
                put("messages", JSONArray().apply {
                    for (m in messages) {
                        put(JSONObject().put("role", m.role).put("content", m.content))
                    }
                })
            }.toString()

            val conn = URL(endpointOf(apiBase)).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }
                    .orEmpty()

                if (code !in 200..299) {
                    return Reply(false, "", "服务端返回 $code:${briefError(raw)}")
                }
                val content = JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                if (content.isNullOrBlank()) Reply(false, "", "回复为空")
                else Reply(true, content.trim())
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // 只带异常类名与简要信息,绝不拼接请求体/头(防密钥经异常回显)
            Reply(false, "", humanNetError(e))
        }
    }

    /**
     * 网络异常人话化(2026-10-17 实测映射):手机填 OpenAI 官方地址直连=连接被重置,
     * 不能只丢 SocketTimeoutException:xxx 让用户猜。
     */
    private fun humanNetError(e: Exception): String {
        val n = e.javaClass.simpleName
        val m = e.message ?: ""
        return when {
            n.contains("UnknownHost") ->
                "域名解析失败:地址拼写或网络不通(检查 API 地址)"
            n.contains("SocketTimeout") ->
                "网络超时无响应(${TIMEOUT_MS / 1000} 秒):检查网络;若填的是 api.openai.com,国内手机直连到不了,请改 https://api.deepseek.com/v1"
            n.contains("SSL") || n.contains("Certificate") ->
                "HTTPS 证书校验失败:检查网络是否被劫持/代理干扰"
            m.contains("Connection reset", true) || n.contains("ConnectException") || n.contains("PortUnreachable") ->
                "网络连接被拒:国内直连 api.openai.com 不通(实测连接被重置),请用 DeepSeek 官方地址 https://api.deepseek.com/v1 或国内可达的 OpenAI 兼容端点"
            else -> "$n:${m.take(300).ifBlank { "网络异常" }}"
        }
    }

    /** 从服务端错误体里抠一句人话(兼容 OpenAI 风格 error.message;2026-10-17 放开到 600 字供复制诊断) */
    private fun briefError(raw: String): String = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.take(600)
            ?: raw.take(600).ifBlank { "无错误详情" }
    } catch (_: Exception) {
        raw.take(600).ifBlank { "无错误详情" }
    }
}
