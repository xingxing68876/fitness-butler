package dev.xd.fitnessbutler.rec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * 视觉识别双引擎实装(M1.5,系统 HttpURLConnection,零第三方依赖)。
 * - 豆包 Doubao(默认):火山方舟 ARK,OpenAI 兼容;image_url 必须带 data:image/…;base64, 前缀
 * - 智谱 GLM:open.bigmodel.cn;image_url 吃裸 base64
 * 密钥由调用方从 settings 表取好传进来,绝不落代码/日志。
 */
object VisionRecognizers {

    const val ENGINE_DOUBAO = "doubao"
    const val ENGINE_GLM = "glm"
    const val ENGINE_DEEPSEEK = "deepseek"

    /** 默认模型 ID(实测可用) */
    const val DEFAULT_MODEL_DOUBAO = "doubao-seed-2-1-pro-260915"
    const val DEFAULT_MODEL_GLM = "glm-4.5v"
    /** DeepSeek 最新模型(2026-11-05 官方 /models 实查:旧 v4-flash-vision-exp 已迭代并入,视觉直接支持) */
    const val DEFAULT_MODEL_DEEPSEEK = "deepseek-flash"

    const val URL_DOUBAO = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    const val URL_GLM = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    const val URL_DEEPSEEK = "https://api.deepseek.com/chat/completions"

    const val PROMPT_TEXT =
        "你是食物识别助手。看这张餐食照片,识别其中所有可吃的菜品和饮品," +
            "估算每样的可食克重、热量(千卡)与三大营养素(蛋白质/碳水/脂肪,克)。" +
            "拿不准菜名时:菜名给最可能的 1 个,并把另外最多 2 个候选放进\"候选\"数组,不要瞎编单一答案。" +
            "只输出一个 JSON 数组,不要输出任何其他文字(包括解释和 markdown 围栏)," +
            "格式:[{\"菜名\":\"番茄炒蛋\",\"克重\":280,\"热量kcal\":210,\"蛋白g\":12,\"碳水g\":8,\"脂肪g\":14,\"候选\":[\"虾仁炒蛋\"]}]。" +
            "克重按可食部分估计;热量与三宏按克重给出;不确定也要给合理估计值,不要留空、不要写 0。"

    fun defaultModel(engine: String): String = when (engine) {
        ENGINE_GLM -> DEFAULT_MODEL_GLM
        ENGINE_DEEPSEEK -> DEFAULT_MODEL_DEEPSEEK
        else -> DEFAULT_MODEL_DOUBAO
    }

    fun build(engine: String, apiKey: String, modelId: String): FoodRecognizer {
        val model = modelId.ifBlank { defaultModel(engine) }
        return when (engine) {
            ENGINE_GLM -> GlmRecognizer(apiKey, model)
            ENGINE_DEEPSEEK -> DeepseekRecognizer(apiKey, model)
            else -> DoubaoRecognizer(apiKey, model)
        }
    }

    /** 识别结果 → JSON(中文键含候选;服务存档与 UI 呈现共用一份序列化,2026-10-19) */
    fun itemsToJson(items: List<RecognizedFood>): String {
        val arr = org.json.JSONArray()
        for (it in items) {
            arr.put(org.json.JSONObject().apply {
                put("菜名", it.name)
                put("克重", it.grams)
                put("热量kcal", it.kcal)
                put("蛋白g", it.protein)
                put("碳水g", it.carbs)
                put("脂肪g", it.fat)
                if (it.candidates.isNotEmpty()) put("候选", org.json.JSONArray(it.candidates))
            })
        }
        return arr.toString()
    }
}

/** 豆包 Doubao(火山方舟 ARK,OpenAI 兼容;图带 data: 前缀) */
class DoubaoRecognizer(
    private val apiKey: String,
    private val modelId: String
) : FoodRecognizer {
    override fun recognize(
        photoPath: String, callback: (List<RecognizedFood>?, String?) -> Unit
    ) {
        OpenAiVision.request(
            url = VisionRecognizers.URL_DOUBAO,
            apiKey = apiKey,
            modelId = modelId,
            b64 = ImageUtil.base64Of(photoPath) ?: return callback(null, "图片读取失败"),
            dataUriPrefix = true,
            extraBody = null
        ).let { VisionRecognizersParse.handle(it, callback) }
    }
}

/** 智谱 GLM-4.5V(裸 base64;关思考模式求快) */
class GlmRecognizer(
    private val apiKey: String,
    private val modelId: String
) : FoodRecognizer {
    override fun recognize(
        photoPath: String, callback: (List<RecognizedFood>?, String?) -> Unit
    ) {
        OpenAiVision.request(
            url = VisionRecognizers.URL_GLM,
            apiKey = apiKey,
            modelId = modelId,
            b64 = ImageUtil.base64Of(photoPath) ?: return callback(null, "图片读取失败"),
            dataUriPrefix = false,
            extraBody = JSONObject().put("thinking", JSONObject().put("type", "disabled"))
        ).let { VisionRecognizersParse.handle(it, callback) }
    }
}

/** DeepSeek 官方视觉(2026-11-05 起:实测部分网络到火山引擎间歇断,DeepSeek 走腾讯 EdgeOne 更快,设为首选引擎) */
class DeepseekRecognizer(
    private val apiKey: String,
    private val modelId: String
) : FoodRecognizer {
    override fun recognize(
        photoPath: String, callback: (List<RecognizedFood>?, String?) -> Unit
    ) {
        OpenAiVision.request(
            url = VisionRecognizers.URL_DEEPSEEK,
            apiKey = apiKey,
            modelId = modelId,
            b64 = ImageUtil.base64Of(photoPath) ?: return callback(null, "图片读取失败"),
            dataUriPrefix = true,
            extraBody = null
        ).let { VisionRecognizersParse.handle(it, callback) }
    }
}

/** 两家共用的 OpenAI 兼容 chat/completions 调用 */
internal object OpenAiVision {

    /** 返回 (文本结果, 错误);二选一 */
    fun request(
        url: String,
        apiKey: String,
        modelId: String,
        b64: String,
        dataUriPrefix: Boolean,
        extraBody: JSONObject?
    ): Pair<String?, String?> {
        if (apiKey.isBlank()) return null to "未配置 API Key"
        val imageUrl = if (dataUriPrefix) "data:image/jpeg;base64,$b64" else b64
        val body = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 2000)
            put("temperature", 0.1)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", imageUrl))
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", VisionRecognizers.PROMPT_TEXT)
                    })
                })
            }))
            extraBody?.let { ex ->
                val it2 = ex.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    put(k, ex.get(k))
                }
            }
        }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                // 2026-11-05 提速:120s→60s。正常识别单次响应 30-45s 有余量;
                // 真网络死等 2 分钟才换路太慢(4 轮重试最坏 8 分钟),60s 快速失败快速换路
                conn.readTimeout = 60_000
                conn.doOutput = true
                // 2026-10-17 上传加固:OkHttp 桥写超时默认 10s 且不可经 HttpURLConnection 设置,
                // 慢链路上 350KB 请求体一次写入必撞写超时("timeout");chunked 分块写(16KB/块)
                // 绕开写超时。兼容风险=极少数网关不收 chunked,若报 411/400 再回退本行。
                conn.setChunkedStreamingMode(16384)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    // 2026-10-17:错误体放开到 800 字,用户长按复制全文贴出诊断
                    return null to "HTTP $code:${text.take(800)}"
                }
                val content = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .get("content")
                val s = when (content) {
                    is JSONArray -> (0 until content.length()).joinToString("") {
                        content.getJSONObject(it).optString("text")
                    }
                    else -> content?.toString() ?: ""
                }
                s to null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}

/** 把 OpenAiVision 的返回转成识别结果 */
internal object VisionRecognizersParse {

    fun handle(
        result: Pair<String?, String?>,
        callback: (List<RecognizedFood>?, String?) -> Unit
    ) {
        val (text, err) = result
        if (err != null) return callback(null, err)
        val items = parseItems(text ?: "")
        if (items == null) return callback(null, "识别结果解析失败")
        callback(items, null)
    }

    /** 容错解析:剥 markdown 围栏,截取首尾 [] 再 parse */
    fun parseItems(raw: String): List<RecognizedFood>? {
        var t = raw.trim()
        t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val arr = JSONArray(t.substring(start, end + 1))
            val out = mutableListOf<RecognizedFood>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("菜名").ifBlank { o.optString("name") }.trim()
                if (name.isBlank()) continue
                val grams = o.optDouble("克重", o.optDouble("grams", 0.0))
                val kcal = o.optDouble("热量kcal", o.optDouble("热量", o.optDouble("kcal", 0.0)))
                // 三宏+候选(2026-10-17):键名与 PROMPT_TEXT 对齐,英文键兜底
                val protein = o.optDouble("蛋白g", o.optDouble("蛋白", o.optDouble("protein", 0.0)))
                val carbs = o.optDouble("碳水g", o.optDouble("碳水", o.optDouble("carbs", 0.0)))
                val fat = o.optDouble("脂肪g", o.optDouble("脂肪", o.optDouble("fat", 0.0)))
                val candidates = o.optJSONArray("候选")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
                } ?: o.optJSONArray("candidates")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
                } ?: emptyList()
                out.add(RecognizedFood(name, grams, kcal, protein, carbs, fat, candidates))
            }
            out
        } catch (_: Exception) {
            null
        }
    }
}

/** 图片工具:压缩到 ~1024px JPEG(省流量且精度够)+ base64 */
object ImageUtil {

    fun base64Of(path: String): String? = try {
        Base64.getEncoder().encodeToString(File(path).readBytes())
    } catch (_: Exception) {
        null
    }

    /** 压缩到最长边 ~maxPx 的 JPEG,返回新文件路径;失败回退原图路径 */
    // 2026-10-17 上传加固:1024px/85 → 768px/70,请求体约 350KB→130KB,慢链路更易传完
    // 2026-10-19 用户反馈识别久:768→640px(食物辨识足够),上传再省 ~30%,配合服务并行
    fun compressForUpload(srcPath: String, outDir: File, maxPx: Int = 640, quality: Int = 70): String {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcPath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return srcPath

            var inSample = 1
            while (bounds.outWidth / inSample > maxPx * 2 || bounds.outHeight / inSample > maxPx * 2) {
                inSample *= 2
            }
            var bmp = BitmapFactory.decodeFile(srcPath, BitmapFactory.Options().apply {
                inSampleSize = inSample
            }) ?: return srcPath

            val longSide = maxOf(bmp.width, bmp.height)
            if (longSide > maxPx) {
                val scale = maxPx.toFloat() / longSide
                val scaled = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            outDir.mkdirs()
            val out = File(outDir, "upload_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            bmp.recycle()
            out.absolutePath
        } catch (_: Exception) {
            srcPath
        }
    }
}
