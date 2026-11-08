package dev.xd.fitnessbutler.ui

import android.app.AlertDialog
import android.app.Fragment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.chat.ChatPrompt
import dev.xd.fitnessbutler.chat.DeepSeekClient
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.rec.ImageUtil
import dev.xd.fitnessbutler.rec.RecognizeBus
import dev.xd.fitnessbutler.rec.RecognizeService
import dev.xd.fitnessbutler.rec.RecognizedFood
import dev.xd.fitnessbutler.rec.VisionRecognizers
import dev.xd.fitnessbutler.rec.VisionRecognizersParse
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页 · 对话(M2 真聊天 + 2026-10-17 对话式识别主线):
 * 统计条 + 对话流 + 输入栏 + 📷发图识别。
 * - 发送 → 存 chat_messages → DeepSeek(OpenAI 兼容)后台线程 → 回存 + 气泡;
 *   接线三键(ds_api_base/ds_model/ds_api_key)在设置页维护,密钥只走本机 settings 表。
 * - 📷 → 自建相册选一张 → 图片进对话流(📷<photoPath> 前缀) → 视觉识别 →
 *   AI 消息人话呈现(含三宏/候选)+ 隐藏 ```meal``` 块 → extractMeal 自动入账;
 *   用户文字纠正 → DeepSeek 按纠正协议回新 ```meal``` 块 → 更新同一笔(不重复记账)。
 * - ```plan``` 块照旧:extractPlan 落 workout_plans。
 */
@Suppress("DEPRECATION")
class HomeFragment : Fragment() {

    companion object {
        /** 图片消息在 chat_messages 里的存法:content = "📷<绝对路径>" */
        const val PHOTO_PREFIX = "📷<"

        /** 最近一次识别入账的账单(纠正时更新同一笔);Fragment 会被 replace 重建,故放 companion */
        var lastMealId: Long = -1L
        var lastMealPhoto: String? = null

        /** 识别在途去重(Fragment 重建也不丢):同图识别中时防重复点爆 */
        private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private var thinkingBubble: TextView? = null
    private var currentStyle = "嘴毒"
    private var lastChatHeight = 0

    // ---------- 识别后台化(2026-10-19):每图独立「识别中」气泡 + 计时,结果走服务总线 ----------

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 每张在途照片的占位气泡(批量多图并存;thinkingBubble 只归文字对话用) */
    private val thinkingBubbles = HashMap<String, TextView>()

    /** 各图识别起始时刻(气泡里显示已等秒数) */
    private val bubbleStartAt = HashMap<String, Long>()
    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (thinkingBubbles.isEmpty()) { ticking = false; return }
            val now = System.currentTimeMillis()
            for ((p, b) in thinkingBubbles) {
                val s = ((now - (bubbleStartAt[p] ?: now)) / 1000).toInt()
                b.text = "识别中… ${s}s(缩到后台也会继续识别,回来就能看到)"
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    /** 服务回执(主线程投递):当场呈现;Fragment 重建/后台归来走 onStart 认领 */
    private val recogListener: (RecognizeBus.Done) -> Unit = { d -> onRecognitionDone(d) }

    /** 图左重试标记实例(按 photoPath 索引,识别成功后淡出用) */
    private val photoBadges = HashMap<String, TextView>()

    /** 图片气泡容器(按 photoPath 索引,识别失败清空照片时整块移除) */
    private val photoBubbleViews = HashMap<String, View>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home, container, false)

        val act = activity as MainActivity
        val db = DbHelper.get(activity)

        // 设置入口(头像可自定义,点击也进设置)
        v.findViewById<TextView>(R.id.home_settings_btn).setOnClickListener { act.openSettings() }
        v.findViewById<AvatarView>(R.id.home_avatar).apply {
            bind(false, null)
            setOnClickListener { act.openSettings() }
        }

        // 统计条(有真数据显示真数据,没数据给占位)
        val today = dateFmt.format(Date())
        val weight = db.latestWeighing()?.weightKg
        v.findViewById<TextView>(R.id.stat_weight).text =
            if (weight != null) "%.2f".format(Locale.US, weight) else "69.05"
        v.findViewById<TextView>(R.id.stat_intake).text =
            formatKcal(db.intakeKcalOn(today), 1280)
        v.findViewById<TextView>(R.id.stat_burn).text =
            formatKcal(dev.xd.fitnessbutler.health.CaloricBalance.totalBurn(db, today).total, 2100)
        val sleepToday = db.dailyStatsSince(today).firstOrNull { it.metric == "sleep_hours" }?.value
        v.findViewById<TextView>(R.id.stat_sleep).text =
            // 没数据显 "--"(2026-10-18 设计决策砍假数据:旧占位 6h25 曾被当成真睡眠)
            if (sleepToday != null) "%.1fh".format(Locale.US, sleepToday) else "--"

        // 当前风格(决定 AI 消息头像)
        val style = db.getSetting("style", "嘴毒")
        currentStyle = style
        val nick = db.getSetting("nickname", "老板").ifBlank { "老板" }
        v.findViewById<TextView>(R.id.home_name).text = nick
        v.findViewById<TextView>(R.id.home_style).text = "当前风格 · $style"
        v.findViewById<AvatarView>(R.id.home_ai_avatar).bind(true, style)

        // 对话流:回放当前会话历史(v7 会话隔离);图片消息渲染缩略图,meal/plan 块剥掉不上屏
        chat = v.findViewById(R.id.home_chat)
        chatScroll = v.findViewById(R.id.home_chat_scroll)
        val session = chatSession(db)
        val history = db.chatMessagesOf(session)
        // 摘要提示条置顶(压缩只影响 LLM 上下文,UI 历史始终全量可翻)
        val sumN = db.getSetting("chat_summary_n", "0").toIntOrNull() ?: 0
        if (db.getSetting("chat_summary", "").isNotBlank() && sumN > 0) addSummaryNote(sumN)
        if (history.isEmpty()) {
            addBubble(
                chat, true,
                "我是你的健身管家。可以问我今天的数据、怎么练、怎么吃;点 📷 拍餐食我帮你记账;要排周计划直接说「帮我排周计划」。"
            )
        } else {
            for (m in history) renderHistoryMessage(m.role, m.content)
        }
        scrollToBottom()

        // 对话管理:压缩当前对话 / 开新对话(2026-10-18 用户,参考 Claude Code 的 compact/clear)
        v.findViewById<TextView>(R.id.home_chatmenu_btn).setOnClickListener { showChatMenu(db) }

        // 📷 快捷按钮:直接进自建相册(单选),选完图回对话流识别(2026-10-17 对话式识别)
        v.findViewById<TextView>(R.id.home_cam_btn).setOnClickListener { act.openAlbumPickForChat() }

        // 输入栏:发送按钮 + 键盘发送键都走 send()
        input = v.findViewById(R.id.home_input)
        v.findViewById<TextView>(R.id.home_send).setOnClickListener { send(db) }
        input.setOnEditorActionListener { _, _, _ ->
            send(db)
            true
        }
        // 键盘弹起时对话区变矮 → 滚到最新一条,避免最新内容被盖住(2026-10-18 用户)
        input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) scrollToBottom() }
        chatScroll.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.height != lastChatHeight) {
                lastChatHeight = view.height
                scrollToBottom()
            }
        }

        // 相册/拍照/补识回程挂着照片(可多张) → 逐张进对话流识别(2026-10-18 全入口汇入)
        act.pendingChatPhotos.let { ps ->
            if (ps.isNotEmpty()) {
                act.pendingChatPhotos = emptyList()
                for (p in ps) sendPhotoAndRecognize(db, p)
            }
        }

        // 全文字可复制(2026-10-17):静态布局统一扫描;对话气泡在 addBubble 里单独设
        TextCopyUtil.apply(v)
        return v
    }

    // ---------- 历史回放 ----------

    private fun renderHistoryMessage(role: String, content: String) {
        if (role == "user" && content.startsWith(PHOTO_PREFIX) && content.endsWith(">")) {
            addPhotoBubble(chat, content.removePrefix(PHOTO_PREFIX).removeSuffix(">"))
        } else if (role == "assistant") {
            // 回放剥 meal/plan 块(JSON 不上屏);含 meal 块=已识别入账过,补绿标
            val (body, hasMeal) = stripBlocksForDisplay(content)
            addBubble(chat, true, body.ifBlank { "✓" })
            if (hasMeal) addSavedTag()
        } else {
            addBubble(chat, false, content)
        }
    }

    /** 纯显示用剥块:去掉 ```meal```、```mealops``` 与 ```plan``` 块,返回(正文, 是否含 meal 块) */
    private fun stripBlocksForDisplay(text: String): Pair<String, Boolean> {
        val mealRe = Regex("```meal\\s*\\n[\\s\\S]*?\\n```")
        val opsRe = Regex("```mealops\\s*\\n[\\s\\S]*?\\n```")
        val planRe = Regex("```plan\\s*\\n[\\s\\S]*?\\n```")
        val hasMeal = mealRe.containsMatchIn(text)
        val body = text.replace(mealRe, "").replace(opsRe, "").replace(planRe, "").trim()
        return body to hasMeal
    }

    // ---------- 发送 / 接收 ----------

    private fun send(db: DbHelper) {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        val act = activity as? MainActivity ?: return

        // 先落库再请求(用户消息不丢)
        db.insertChatMessage("user", text, timeFmt.format(Date()), chatSession(db))
        addBubble(chat, false, text)
        input.setText("")
        scrollToBottom()

        // 接线检查:三键齐了才发请求
        val base = db.getSetting("ds_api_base", "")
        val model = db.getSetting("ds_model", "")
        val key = db.getSetting("ds_api_key", "")
        if (base.isBlank() || model.isBlank() || key.isBlank()) {
            addBubble(
                chat, true,
                "接线还没配:去「设置 · AI 大脑接线」填 API 地址 / 模型名 / 密钥,填好我就能开口了。"
            )
            scrollToBottom()
            return
        }

        // 思考中占位气泡
        val thinking = addBubble(chat, true, "想一想…")
        thinkingBubble = thinking
        scrollToBottom()

        val sys = ChatPrompt.buildSystem(db)
        val msgs = mutableListOf(DeepSeekClient.Msg("system", sys))
        // 压缩摘要置顶(2026-10-18 对话压缩):更早对话的要点,AI 依然记得结论
        val sum = db.getSetting("chat_summary", "")
        if (sum.isNotBlank()) {
            msgs.add(DeepSeekClient.Msg("system", "【此前对话摘要(更早逐条消息已省略)】\n$sum"))
        }
        // 最近 20 条原文做上下文(当前会话;6 条以前的消息剥 meal/mealops/plan 隐藏块省 token,
        // 账本真相在 system 的餐食账本里,不靠历史块)
        val session = chatSession(db)
        val hist = db.chatMessagesOf(session)
        val tail = hist.takeLast(20)
        for ((i, m) in tail.withIndex()) {
            val content =
                if (i < tail.size - 6) stripBlocksForDisplay(m.content).first.ifBlank { m.content }
                else m.content
            msgs.add(DeepSeekClient.Msg(m.role, content))
        }

        Thread {
            val reply = DeepSeekClient.chat(base, model, key, msgs)
            act.runOnUiThread {
                thinkingBubble?.let { removeBubbleRow(chat, it) }
                thinkingBubble = null
                if (reply.ok) {
                    db.insertChatMessage("assistant", reply.text, timeFmt.format(Date()), chatSession(db))
                    // ```plan 块:训练计划;```mealops 块:聊天删改账本(2026-10-18);```meal 块:识别纠正兜底
                    val (body1, planApplied) = extractPlan(db, reply.text)
                    val (body2, opsDone, opsNote) = applyMealOps(db, body1)
                    val (body3, mealId) =
                        if (opsDone > 0) body2 to null else extractMeal(db, body2, isCorrection = true)
                    addBubble(
                        chat, true,
                        body3.ifBlank { if (opsDone > 0) "账本已更新 ✓" else "新训练计划已生成 ✓" }
                    )
                    if (planApplied) {
                        act.toast("新训练计划已应用,去锻炼页看")
                    }
                    if (opsDone > 0) {
                        refreshIntakeStat(db)
                        act.toast("账本已更新:$opsNote")
                    } else if (opsNote.isNotBlank()) {
                        act.toast("账本未改:$opsNote")
                    }
                    if (mealId != null) {
                        addSavedTag()
                        act.toast("账单已按你的修正更新")
                    }
                } else {
                    // 失败不入库,给可读原因(不含密钥)
                    addBubble(chat, true, "接线未通:${reply.error ?: "未知错误"}")
                }
                scrollToBottom()
                if (reply.ok) maybeAutoCompact(db)
            }
        }.start()
    }

    // ---------- 对话式识别(2026-10-17 主线) ----------

    /** 图片消息进对话流并发起识别:📷<path> 入库 + 缩略图气泡 → 后台视觉识别 → AI 消息(含隐藏 meal 块) */
    private fun sendPhotoAndRecognize(db: DbHelper, photoPath: String) {
        if (activity !is MainActivity) return
        lastMealPhoto = photoPath   // 新一笔的照片(纠正更新时沿用库里原值)
        // 补识重提交不重复上屏图片气泡(2026-10-18);新图照常入库上屏
        if (!db.chatHasMessage("$PHOTO_PREFIX$photoPath>")) {
            db.insertChatMessage("user", "$PHOTO_PREFIX$photoPath>", timeFmt.format(Date()), chatSession(db))
            addPhotoBubble(chat, photoPath)
        }
        scrollToBottom()
        recognizeInChat(db, photoPath)
    }

    /** 对图发起识别(2026-10-19 后台化):移交前台服务跑,UI 挂计时气泡等总线回执 */
    private fun recognizeInChat(db: DbHelper, photoPath: String) {
        val act = activity as? MainActivity ?: return

        // 在途去重:同图识别中/已排队,点标记只提示不重发
        if (!inFlight.add(photoPath)) {
            act.toast("这张图正在识别,稍候")
            return
        }

        val recognizer = act.buildRecognizerFor()
        if (recognizer == null) {
            inFlight.remove(photoPath)
            // 2026-10-18 设计决策:识别失败即丢弃照片,不留待补识
            discardFailedPhoto(db, photoPath, -1L)
            addBubble(chat, true, "视觉 Key 还没配,照片已丢弃(未保存)。去「设置 → 视觉识别」填好 Key 再拍。")
            scrollToBottom()
            return
        }

        // 图片消息上屏(服务失败时会删消息+文件);识别本体交前台服务,缩后台不中断
        if (!db.chatHasMessage("$PHOTO_PREFIX$photoPath>")) {
            db.insertChatMessage("user", "$PHOTO_PREFIX$photoPath>", timeFmt.format(Date()), chatSession(db))
            addPhotoBubble(chat, photoPath)
        }
        addThinkingBubble(photoPath)
        scrollToBottom()
        RecognizeService.start(act, listOf(photoPath))
    }

    // ---------- 识别结果呈现(总线回执 / onStart 认领共用,均在主线程) ----------

    /** 服务回执:当场呈现;若结果已被认领过(状态已转「已识别」)则跳过防重 */
    private fun onRecognitionDone(d: RecognizeBus.Done) {
        val act = activity as? MainActivity ?: return
        val db = DbHelper.get(act)
        inFlight.remove(d.photoPath)
        removeThinkingBubble(d.photoPath)

        if (d.error != null || d.resultJson == null) {
            // 服务已删图片消息/照片并落失败消息进库;这里只收尾屏幕:移除图片气泡+补显失败气泡
            photoBubbleViews.remove(d.photoPath)?.let { chat.removeView(it) }
            photoBadges.remove(d.photoPath)
            addBubble(
                chat, true,
                "❌ 识别失败:${(d.error ?: "没认出菜品").take(120)}\n照片已丢弃(不保存),重新拍一张再试。"
            )
            scrollToBottom()
            return
        }

        if (db.recognitionStatusById(d.queueId) != "已识别待呈现") return   // 已被认领,不重呈现
        val items = VisionRecognizersParse.parseItems(d.resultJson)
        if (items == null || items.isEmpty()) {
            addBubble(chat, true, "❌ 识别结果解析失败,照片已丢弃。")
            scrollToBottom()
            return
        }
        presentResult(db, d.photoPath, d.queueId, items)
    }

    /** onStart/进程重建后认领服务在后台跑完的结果(「已识别待呈现」行) */
    private fun drainPendingPresentations(db: DbHelper) {
        val rows = db.pendingPresentations()
        if (rows.isEmpty()) return
        for (r in rows) {
            inFlight.remove(r.photoPath)
            removeThinkingBubble(r.photoPath)
            val items = r.resultJson?.let { VisionRecognizersParse.parseItems(it) }
            if (items != null && items.isNotEmpty()) {
                presentResult(db, r.photoPath, r.id, items)
            } else {
                db.markRecognition(r.id, "已识别", r.resultJson ?: "[]")
            }
        }
        scrollToBottom()
    }

    /** 呈现一张识别成功的结果:转存档态 + AI 消息落库上屏 + 自动入账 + ✅ 标记 */
    private fun presentResult(db: DbHelper, photoPath: String, queueId: Long, items: List<RecognizedFood>) {
        db.markRecognition(queueId, "已识别", itemsResultJson(items))
        val text = recognitionText(items)
        db.insertChatMessage("assistant", text, timeFmt.format(Date()), chatSession(db))
        lastMealPhoto = photoPath   // 多图并发以本图为准,防串图入账
        val (body, mealId) = extractMeal(db, text, isCorrection = false)
        addBubble(chat, true, body)
        if (mealId != null) {
            addSavedTag()
            (activity as? MainActivity)?.toast("已入账 ≈ ${items.sumOf { it.kcal }.toInt()} kcal,不对就说,我改")
        }
        // 图左标记短暂 ✅ 后隐(2026-10-18 用户:识别成功的图不留重试标记)
        photoBadges[photoPath]?.let { b ->
            b.text = "✅"
            b.postDelayed({ b.visibility = View.GONE }, 1500)
        }
    }

    /** 每图「识别中」气泡:带秒数计时,批量多图并存 */
    private fun addThinkingBubble(photoPath: String) {
        bubbleStartAt[photoPath] = System.currentTimeMillis()
        val b = addBubble(chat, true, "识别中…")
        thinkingBubbles[photoPath] = b
        if (!ticking) {
            ticking = true
            uiHandler.postDelayed(ticker, 1000)
        }
    }

    private fun removeThinkingBubble(photoPath: String) {
        thinkingBubbles.remove(photoPath)?.let { removeBubbleRow(chat, it) }
        bubbleStartAt.remove(photoPath)
    }

    override fun onStart() {
        super.onStart()
        RecognizeBus.add(recogListener)
        // 认领后台/进程被杀期间跑完的结果(与总线回执同在主线程,不重不漏)
        activity?.let { drainPendingPresentations(DbHelper.get(it)) }
    }

    override fun onStop() {
        super.onStop()
        RecognizeBus.remove(recogListener)
    }

    /** 识别结果 → queue.result_json(2026-10-19 起委托 VisionRecognizers 共用序列化,含候选) */
    private fun itemsResultJson(items: List<RecognizedFood>): String =
        VisionRecognizers.itemsToJson(items)

    /**
     * 识别失败即丢弃(2026-10-18 设计决策:不成功不保存):
     * 删队列行、删对话里的图片消息、移除屏幕图片气泡、删私有照片文件。
     * 相册里的拍照副本由 MainActivity 另行保存,不受影响。
     */
    private fun discardFailedPhoto(db: DbHelper, photoPath: String, queueId: Long) {
        if (queueId > 0) db.deleteRecognition(queueId)
        db.deleteChatMessage("$PHOTO_PREFIX$photoPath>")
        photoBubbleViews.remove(photoPath)?.let { chat.removeView(it) }
        photoBadges.remove(photoPath)
        runCatching { File(photoPath).delete() }
    }

    /** 识别结果人话文本 + 尾部隐藏 ```meal``` 块(入 chat_messages,渲染时剥掉) */
    private fun recognitionText(items: List<RecognizedFood>): String {
        val lines = items.map {
            "识别到:${it.name} 约${it.grams.toInt()}g · ${it.kcal.toInt()}kcal\n" +
                "蛋白${it.protein.toInt()}g · 碳水${it.carbs.toInt()}g · 脂肪${it.fat.toInt()}g" +
                (if (it.candidates.isNotEmpty())
                    "\n拿不准?候选:${it.candidates.joinToString(" / ")}" else "")
        }
        val total = items.sumOf { it.kcal }.toInt()
        val body = lines.joinToString("\n") +
            "\n合计约 $total kcal\n不对就告诉我(说菜名/克重都行),改对了说声「记上」。"
        return body + "\n" + mealBlockOf(items)
    }

    /** 中文键 meal 块(与 ChatPrompt 纠正协议、extractMeal 解析三方对齐) */
    private fun mealBlockOf(items: List<RecognizedFood>): String {
        val arr = org.json.JSONArray()
        for (it in items) {
            arr.put(org.json.JSONObject().apply {
                put("菜名", it.name)
                put("克重", it.grams)
                put("热量kcal", it.kcal)
                put("蛋白g", it.protein)
                put("碳水g", it.carbs)
                put("脂肪g", it.fat)
            })
        }
        return "```meal\n$arr\n```"
    }

    /**
     * 从 AI 消息中抽 ```meal``` 块入账(2026-10-17 对话式识别):
     * - isCorrection=false(图片识别直出)→ 新记一笔(insert)
     * - isCorrection=true(用户文字纠正后 AI 回块)→ 更新 lastMealId 那一笔(不重复记账)
     * 受「AI 识别后自动入账」开关联动;返回(剥块正文, 入账 mealId 或 null)。
     */
    private fun extractMeal(db: DbHelper, text: String, isCorrection: Boolean): Pair<String, Long?> {
        val regex = Regex("```meal\\s*\\n([\\s\\S]*?)\\n```")
        val m = regex.find(text) ?: return text to null
        val items = parseMealBlock(m.groupValues[1]) ?: return text to null
        if (items.isEmpty()) return text to null

        val body = text.removeRange(m.range).trim()
        if (db.getSetting("auto_save_meal", "1") != "1") {
            (activity as? MainActivity)?.toast("自动入账已关,这笔没进账本")
            return body to null
        }

        return try {
            val id: Long
            if (isCorrection) {
                // 纠正:更新同一笔,保留原时间戳/照片,餐次沿用原值。
                // 2026-10-18:内存里没有目标(APP 重启/那笔已删)就放弃,绝不再插一笔造成重复账。
                val old = if (lastMealId > 0) db.mealById(lastMealId) else null
                if (old == null) return body to null
                db.updateMeal(
                    lastMealId,
                    old.ts,
                    old.photoPath,
                    old.mealType ?: MealEditFragment.guessMealType(),
                    old.note ?: "对话识别入账",
                    items
                )
                id = lastMealId
            } else {
                id = db.insertMeal(
                    timeFmt.format(Date()), lastMealPhoto, MealEditFragment.guessMealType(),
                    "对话识别入账", items
                )
                lastMealId = id
                lastMealPhoto = null
            }
            body to id
        } catch (_: Exception) {
            body to null
        }
    }

    /** ```meal``` 块 JSON(中文键,单对象自动包数组)→ MealItem 列表;解析失败返回 null */
    private fun parseMealBlock(raw: String): List<DbHelper.MealItem>? {
        return try {
            val s = raw.trim().let { if (it.startsWith("[")) it else "[$it]" }
            val arr = org.json.JSONArray(s)
            val out = mutableListOf<DbHelper.MealItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("菜名").ifBlank { o.optString("name") }.trim()
                if (name.isBlank()) continue
                out.add(
                    DbHelper.MealItem(
                        name = name,
                        grams = o.optDouble("克重", o.optDouble("grams", 0.0)),
                        kcal = o.optDouble("热量kcal", o.optDouble("热量", o.optDouble("kcal", 0.0))),
                        source = DbHelper.SRC_AI_VISION,
                        protein = o.optDouble("蛋白g", o.optDouble("protein", 0.0)),
                        carbs = o.optDouble("碳水g", o.optDouble("carbs", 0.0)),
                        fat = o.optDouble("脂肪g", o.optDouble("fat", 0.0))
                    )
                )
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * ```mealops``` 账本改动块(2026-10-18 用户:聊天要能改日历里的餐食):
     * {"ops":[{"op":"replace_item","id":12,"name":"肉蟹煲","items":[...]},
     *         {"op":"remove_item","id":12,"name":"米饭"},
     *         {"op":"add_item","id":12,"items":[...]},
     *         {"op":"update","id":12,"items":[...]},
     *         {"op":"delete","id":13},
     *         {"op":"insert","date":"2026-10-18","time":"12:30","mealType":"午餐","items":[...]}]}
     * replace/remove/add 按菜名局部改,没提到的菜自动保留(不会误删);
     * update 整餐重写;insert 新增一餐。保留原时间/照片/餐次。
     * 返回(剥块正文, 实际执行条数, 人话摘要)。
     */
    private fun applyMealOps(db: DbHelper, text: String): Triple<String, Int, String> {
        val regex = Regex("```mealops\\s*\\n([\\s\\S]*?)\\n```")
        val m = regex.find(text) ?: return Triple(text, 0, "")
        val body = text.removeRange(m.range).trim()
        val ops = runCatching {
            org.json.JSONObject(m.groupValues[1]).optJSONArray("ops")
        }.getOrNull() ?: return Triple(body, 0, "")

        var done = 0
        val notes = mutableListOf<String>()
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            val kind = op.optString("op")
            val items = op.optJSONArray("items")
                ?.let { parseMealBlock(it.toString()) } ?: emptyList()
            try {
                when (kind) {
                    "update", "replace_item", "remove_item", "add_item" -> {
                        val id = op.optLong("id", -1L)
                        val old = if (id > 0L) db.mealById(id) else null
                        if (old == null) {
                            notes.add("没找到 #$id")
                            continue
                        }
                        val target = op.optString("name").trim()
                        if ((kind == "replace_item" || kind == "remove_item") && target.isBlank()) {
                            notes.add("没说要改哪道菜")
                            continue
                        }
                        if ((kind == "replace_item" || kind == "remove_item") &&
                            old.items.none { nameMatches(it.name, target) }
                        ) {
                            notes.add("这餐里没找到「$target」")
                            continue
                        }
                        if (kind == "add_item" && items.isEmpty()) continue
                        if (kind == "update" && items.isEmpty()) {
                            notes.add("没给新明细 #$id")
                            continue
                        }
                        val newItems = when (kind) {
                            "update" -> items
                            "add_item" -> old.items + items
                            "remove_item" -> old.items.filterNot { nameMatches(it.name, target) }
                            else -> old.items.filterNot { nameMatches(it.name, target) } + items
                        }
                        if (newItems.isEmpty()) {
                            // 把该餐最后一道菜删掉 = 整餐撤销
                            db.deleteMeal(id)
                            done++
                            notes.add("删${old.mealType ?: "一餐"}")
                            continue
                        }
                        db.updateMeal(
                            id, old.ts, old.photoPath,
                            old.mealType ?: MealEditFragment.guessMealType(),
                            old.note ?: "对话改动", newItems
                        )
                        done++
                        notes.add("改${old.mealType ?: "一餐"}")
                    }
                    "delete" -> {
                        val id = op.optLong("id", -1L)
                        val old = if (id > 0L) db.mealById(id) else null
                        if (old == null) {
                            notes.add("没找到 #$id")
                            continue
                        }
                        db.deleteMeal(id)
                        done++
                        notes.add("删${old.mealType ?: "一餐"}")
                    }
                    "insert" -> {
                        if (items.isEmpty()) continue
                        val date = op.optString("date").ifBlank { dateFmt.format(Date()) }
                        val time = op.optString("time").ifBlank {
                            SimpleDateFormat("HH:mm", Locale.US).format(Date())
                        }.let { if (it.length == 5) "$it:00" else it }
                        val type = op.optString("mealType").ifBlank { MealEditFragment.guessMealType() }
                        db.insertMeal("${date}T$time", null, type, "对话入账", items)
                        done++
                        notes.add("加$type")
                    }
                }
            } catch (e: Exception) {
                Log.e("MealOps", "apply op failed: $kind", e)
            }
        }
        return Triple(body, done, notes.joinToString("、"))
    }

    /** 菜名匹配:完全相同或互相包含(应付「肉蟹煲」vs「蟹煲」这类简写) */
    private fun nameMatches(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /** 账本改动后即时刷新首页「摄入」数字(日历页打开时自行重查) */
    private fun refreshIntakeStat(db: DbHelper) {
        val v = view ?: return
        val today = dateFmt.format(Date())
        v.findViewById<TextView>(R.id.stat_intake)?.text = formatKcal(db.intakeKcalOn(today), 1280)
    }

    /**
     * 从 AI 回复中抽 ```plan JSON``` 块:解析成功写 workout_plans(source=ai,置 active)立即生效。
     * 返回(剥掉 plan 块后的正文, 是否生效);解析失败静默跳过,不干扰聊天。
     */
    private fun extractPlan(db: DbHelper, text: String): Pair<String, Boolean> {
        val regex = Regex("```plan\\s*\\n(\\{.*?\\})\\s*\\n```", RegexOption.DOT_MATCHES_ALL)
        val m = regex.find(text) ?: return text to false
        return try {
            val json = org.json.JSONObject(m.groupValues[1])
            val name = json.optString("name", "AI 定制计划").ifBlank { "AI 定制计划" }
            val days = json.getJSONArray("days")
            // 校验 day 1-7 齐全才生效,防半截计划覆盖好计划
            val dayNums = (0 until days.length()).map { days.getJSONObject(it).getInt("day") }.toSet()
            if (!((1..7).all { it in dayNums })) return text to false
            db.setActivePlan(name, "ai", json.toString())
            val body = (text.removeRange(m.range)).trim()
            body to true
        } catch (_: Exception) {
            text to false
        }
    }

    // ---------- 气泡 / 滚动 ----------

    /** 加一个气泡并返回气泡本身(「思考中」占位要能移除);整行带风格头像(2026-10-18) */
    private fun addBubble(root: LinearLayout, isAi: Boolean, text: String): TextView {
        val density = resources.displayMetrics.density
        val avatarSize = (34 * density).toInt()
        val gap = (6 * density).toInt()
        val bubble = TextView(root.context).apply {
            this.text = text
            textSize = 12.5f
            setPadding(28, 22, 28, 22)
            setTextColor(if (isAi) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
            setBackgroundResource(if (isAi) R.drawable.bg_chat_ai else R.drawable.bg_chat_me)
            setTextIsSelectable(true)   // 对话/错误全文可长按复制(2026-10-17)
            maxWidth = resources.displayMetrics.widthPixels - (110 * density).toInt()
        }
        val avatar = AvatarView(root.context).apply { bind(isAi, currentStyle) }
        val avatarLp = LinearLayout.LayoutParams(avatarSize, avatarSize)
        val bubbleLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        if (isAi) {
            row.addView(avatar, avatarLp)
            bubbleLp.marginStart = gap
            row.addView(bubble, bubbleLp)
        } else {
            bubbleLp.marginEnd = gap
            row.addView(bubble, bubbleLp)
            row.addView(avatar, avatarLp)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16
            gravity = if (isAi) Gravity.START else Gravity.END
        }
        root.addView(row, lp)
        return bubble
    }

    /** 移除气泡所在整行(现在是「头像+气泡」的行容器) */
    private fun removeBubbleRow(root: LinearLayout, bubble: View?) {
        if (bubble == null) return
        val p = bubble.parent
        if (p is ViewGroup && p !== root) root.removeView(p) else root.removeView(bubble)
    }

    /** 图片消息气泡:约 200dp 圆角缩略图;文件已清理则降级为占位文本 */
    private fun addPhotoBubble(root: LinearLayout, photoPath: String) {
        if (!File(photoPath).exists()) {
            addBubble(root, false, "📷(图片已被清理)")
            return
        }
        val density = resources.displayMetrics.density
        val side = (200 * density).toInt()
        val img = ImageView(root.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true   // 圆角:outline 裁剪,零第三方依赖
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 14 * density)
                }
            }
            // 点开全屏看大图(2026-10-26 设计决策)
            isClickable = true
            setOnClickListener { v ->
                v.context.startActivity(
                    android.content.Intent(v.context, PhotoViewerActivity::class.java)
                        .putExtra(PhotoViewerActivity.EXTRA_PATH, photoPath)
                )
            }
        }
        // 后台解码防卡 UI(原图 4000×3000 直接 decode 会卡顿)
        Thread {
            val bmp = decodeSampled(photoPath, side)
            activity?.runOnUiThread { if (bmp != null) img.setImageBitmap(bmp) }
        }.start()
        // 图左圆形重试标记(2026-10-18 用户点名):未识别成功才显示,点它重发识别;随历史持久渲染
        val badge = makeRetryBadge(photoPath)
        photoBadges[photoPath] = badge
        val wrap = FrameLayout(root.context).apply {
            addView(img, FrameLayout.LayoutParams(side, side))
            if (needsRetryBadge(photoPath)) addView(badge)
        }
        // 用户图片消息同样带用户头像(2026-10-18)
        val avatarSize = (34 * density).toInt()
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(wrap, LinearLayout.LayoutParams(side, side))
            addView(
                AvatarView(root.context).apply { bind(false, null) },
                LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    marginStart = (6 * density).toInt()
                }
            )
        }
        photoBubbleViews[photoPath] = row
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16
            gravity = Gravity.END
        }
        root.addView(row, lp)
    }

    /** 是否需要图左重试标记:未识别成功(排队/失败/无记录)且未入账 */
    private fun needsRetryBadge(photoPath: String): Boolean {
        val act = activity ?: return false
        val db = DbHelper.get(act)
        return db.recognitionStatusByPhoto(photoPath) != "已识别" && !db.mealExistsForPhoto(photoPath)
    }

    /** 图左圆形标记:30dp 深色半透明圆底白字,压在图片左缘中线;点击重发识别 */
    private fun makeRetryBadge(photoPath: String): TextView {
        val density = resources.displayMetrics.density
        val d = (30 * density).toInt()
        return TextView(activity).apply {
            text = "🔄"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC141414.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(d, d).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                val act = activity as? MainActivity ?: return@setOnClickListener
                val db = DbHelper.get(act)
                text = "…"
                recognizeInChat(db, photoPath)
                act.toast("重新发送识别…")
            }
        }
    }

    /** 缩略图解码:先量尺寸再按 2 的幂降采样 */
    private fun decodeSampled(path: String, target: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    /** AI 消息后的「✓ 已入账」小绿标 */
    private fun addSavedTag() {
        val tag = TextView(chat.context).apply {
            text = "✓ 已入账"
            textSize = 9.5f
            setTextColor(0xFF2E7D32.toInt())
            setPadding(30, 2, 0, 2)
        }
        chat.addView(tag, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.START })
    }

    // ---------- 对话压缩 / 新对话(2026-10-18 用户,参考 Claude Code 的 compact + clear) ----------

    /** 当前会话 id(settings.chat_session;v7 起会话隔离,旧对话归档在各自 session 里) */
    private fun chatSession(db: DbHelper): String {
        val s = db.getSetting("chat_session", "main")
        return if (s.isBlank()) "main" else s
    }

    private fun showChatMenu(db: DbHelper) {
        val act = activity as? MainActivity ?: return
        val n = db.chatCountOf(chatSession(db))
        AlertDialog.Builder(act)
            .setTitle("对话管理(当前会话 $n 条)")
            .setItems(
                arrayOf(
                    "🗜 压缩当前对话(旧消息变摘要,省 token)",
                    "🆕 开新对话(当前对话整体归档)",
                    "取消"
                )
            ) { _, which ->
                when (which) {
                    0 -> compactNow(db, manual = true)
                    1 -> startNewChat(db)
                }
            }
            .show()
    }

    /**
     * 压缩:把「已压缩位之后、最近 keep 条之前」的原文增量喂给 LLM 出摘要,
     * 存 settings.chat_summary(摘要)/chat_summary_n(覆盖到第几条)。
     * UI 历史一条不动,只有发给 AI 的上下文变小;原文无损,失败可重试。
     */
    private fun compactNow(db: DbHelper, manual: Boolean) {
        val act = activity as? MainActivity ?: return
        val base = db.getSetting("ds_api_base", "")
        val model = db.getSetting("ds_model", "")
        val key = db.getSetting("ds_api_key", "")
        if (base.isBlank() || model.isBlank() || key.isBlank()) {
            act.toast("AI 接线没配,压缩用不了")
            return
        }
        val session = chatSession(db)
        val msgs = db.chatMessagesOf(session)
        val keep = if (manual) 4 else 8        // 手动压得狠一点,自动多留几条原文
        val already = db.getSetting("chat_summary_n", "0").toIntOrNull() ?: 0
        if (msgs.size - keep <= already) {
            act.toast(if (manual) "没有可压缩的新消息" else "对话还不长,先不压缩")
            return
        }
        val fresh = msgs.subList(already, msgs.size - keep)
        val transcript = StringBuilder()
        for (m in fresh) {
            val (body, _) = stripBlocksForDisplay(m.content)
            if (body.isBlank()) continue
            transcript.append(if (m.role == "user") "用户: " else "管家: ")
                .append(body.take(300)).append('\n')
        }
        if (transcript.isBlank()) {
            act.toast("没有可压缩的文本消息")
            return
        }
        val prev = db.getSetting("chat_summary", "")
        act.toast("压缩中…")
        Thread {
            val sys = "你是对话摘要助手。把健身管家 APP 里用户与 AI 管家的对话压成要点摘要," +
                "保留:结论、承诺、计划、账本改动结果、用户的偏好与要求。500 字内,分条列点,不客套。"
            val user = (if (prev.isBlank()) "" else "【已有摘要】\n$prev\n\n") +
                "【新增对话(${fresh.size} 条)】\n$transcript"
            val reply = DeepSeekClient.chat(
                base, model, key,
                listOf(DeepSeekClient.Msg("system", sys), DeepSeekClient.Msg("user", user))
            )
            activity?.runOnUiThread {
                if (reply.ok && reply.text.isNotBlank()) {
                    db.setSetting("chat_summary", reply.text)
                    db.setSetting("chat_summary_n", (msgs.size - keep).toString())
                    act.toast("已压缩 ${fresh.size} 条对话进摘要")
                } else {
                    act.toast("压缩失败:${reply.error ?: "未知错误"}(原文无损,稍后再试)")
                }
            }
        }.start()
    }

    /** 自动压缩:未压缩尾巴 ≥ 30 条就触发(留 8 条原文) */
    private fun maybeAutoCompact(db: DbHelper) {
        val already = db.getSetting("chat_summary_n", "0").toIntOrNull() ?: 0
        if (db.chatCountOf(chatSession(db)) - already >= 30) compactNow(db, manual = false)
    }

    /** 开新对话:换 session id + 清摘要;旧对话在库里归档不删 */
    private fun startNewChat(db: DbHelper) {
        val act = activity as? MainActivity ?: return
        db.setSetting("chat_session", "s${System.currentTimeMillis()}")
        db.setSetting("chat_summary", "")
        db.setSetting("chat_summary_n", "0")
        chat.removeAllViews()
        addBubble(
            chat, true,
            "新对话开始。旧对话已归档(数据库都在,面板上不再显示);今天吃了什么、练了什么我照样知道——那些在账本和图表里。"
        )
        scrollToBottom()
        act.toast("已开新对话")
    }

    /** 摘要提示条(对话流顶部,说明 AI 记得要点) */
    private fun addSummaryNote(n: Int) {
        val act = activity ?: return
        val note = TextView(act).apply {
            text = "📖 更早 $n 条对话已压缩为摘要(AI 记要点,原文仍在库里)"
            textSize = 11f
            setTextColor(0xFF8A8A8A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 12)
        }
        chat.addView(
            note, 0,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun scrollToBottom() {
        // smoothScrollTo 纯滚动不动焦点;fullScroll(FOCUS_DOWN) 会把焦点转给内容里的
        // 可聚焦视图,键盘弹起时被触发会打断输入框的输入连接(2026-10-18 打字失灵)
        chatScroll.post { chatScroll.smoothScrollTo(0, chatScroll.getChildAt(0).height) }
    }

    /** 有真数据显示真数据,没数据给 demo 占位值 */
    private fun formatKcal(real: Double, placeholder: Int): String =
        if (real > 0) real.toInt().toString() else placeholder.toString()
}
