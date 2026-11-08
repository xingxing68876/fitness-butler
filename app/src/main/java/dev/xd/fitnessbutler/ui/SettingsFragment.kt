package dev.xd.fitnessbutler.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Fragment
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.health.VivoHealthHarvester
import dev.xd.fitnessbutler.rec.VisionRecognizers
import dev.xd.fitnessbutler.remind.Reminders
import java.util.Locale

/**
 * 设置:称呼 / 风格四卡(嘴毒/鸡汤/跪求/自定义)/ 身体档案(秤体脂用)/ 视觉识别(引擎+Key+模型 ID)/ 数据源开关 / 体脂秤入口。
 * 风格与称呼 M2 拼进 prompt;视觉识别 M1.5 起服务 AI 直出;开关为 UI 可拨,M3 实装数据链路。
 */
@Suppress("DEPRECATION")
class SettingsFragment : Fragment() {

    private var style = "嘴毒"
    private var male = true
    private var visionEngine = VisionRecognizers.ENGINE_DEEPSEEK

    // 每日提醒时间(设置页选完暂存,保存时落库+重排闹钟)
    private var noonTime = "13:30"
    private var nightTime = "21:00"
    private var workoutTime = "19:00"

    /** 头像预览:(视图, 是否AI, 风格) —— 恢复默认后就地重绘 */
    private val avatarPreviews = mutableListOf<Triple<AvatarView, Boolean, String?>>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        val act = activity as MainActivity
        val db = DbHelper.get(activity)

        // 读库回显
        style = db.getSetting("style", "嘴毒")
        v.findViewById<EditText>(R.id.settings_nickname).setText(db.getSetting("nickname", "老板"))
        v.findViewById<EditText>(R.id.settings_height).setText(db.getSetting("height_cm", "170"))
        v.findViewById<EditText>(R.id.settings_age).setText(db.getSetting("age", "30"))
        male = db.getSetting("gender", "男") != "女"
        v.findViewById<EditText>(R.id.style_custom_text).setText(db.getSetting("style_custom", ""))
        v.findViewById<Switch>(R.id.switch_watch).isChecked = db.getSetting("watch_on", "1") == "1"
        v.findViewById<Switch>(R.id.switch_scale).isChecked = db.getSetting("scale_on", "1") == "1"
        v.findViewById<Switch>(R.id.switch_remind).isChecked = db.getSetting("remind_on", "0") == "1"
        // AI 识别后自动入账(2026-10-17 修丢账,默认开)
        v.findViewById<Switch>(R.id.switch_auto_save).isChecked = db.getSetting("auto_save_meal", "1") == "1"

        // AI 大脑接线(M2 对话):三键回显,密钥只存 settings 表
        v.findViewById<EditText>(R.id.ds_api_base).setText(db.getSetting("ds_api_base", ""))
        v.findViewById<EditText>(R.id.ds_model).setText(db.getSetting("ds_model", ""))
        v.findViewById<EditText>(R.id.ds_api_key).setText(db.getSetting("ds_api_key", ""))

        // 视觉识别:引擎两态 + Key + 模型 ID(每引擎一套,切引擎不丢)
        visionEngine = db.getSetting("vision_engine", VisionRecognizers.ENGINE_DEEPSEEK)
        fun loadVisionFields() {
            v.findViewById<EditText>(R.id.vision_api_key).setText(
                db.getSetting("vision_api_key_$visionEngine", "")
            )
            v.findViewById<EditText>(R.id.vision_model_id).setText(
                db.getSetting("vision_model_$visionEngine", "")
            )
            v.findViewById<EditText>(R.id.vision_model_id).hint =
                "模型 ID(默认 ${VisionRecognizers.defaultModel(visionEngine)};填新模型 ID 即无缝切换,如 doubao-seed-2-1-pro-260915)"
        }
        fun paintEngine() {
            val doubaoBtn = v.findViewById<TextView>(R.id.vision_engine_doubao)
            val dsBtn = v.findViewById<TextView>(R.id.vision_engine_ds)
            val doubaoOn = visionEngine == VisionRecognizers.ENGINE_DOUBAO
            doubaoBtn.setBackgroundResource(if (doubaoOn) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            doubaoBtn.setTextColor(if (doubaoOn) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
            dsBtn.setBackgroundResource(if (!doubaoOn) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            dsBtn.setTextColor(if (!doubaoOn) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
        }
        fun saveVisionFieldsTo(engine: String) {
            db.setSetting(
                "vision_api_key_$engine",
                v.findViewById<EditText>(R.id.vision_api_key).text.toString().trim()
            )
            db.setSetting(
                "vision_model_$engine",
                v.findViewById<EditText>(R.id.vision_model_id).text.toString().trim()
            )
        }
        loadVisionFields()
        paintEngine()
        v.findViewById<TextView>(R.id.vision_engine_doubao).setOnClickListener {
            saveVisionFieldsTo(visionEngine)
            visionEngine = VisionRecognizers.ENGINE_DOUBAO
            db.setSetting("vision_engine", visionEngine)
            loadVisionFields(); paintEngine()
        }
        v.findViewById<TextView>(R.id.vision_engine_ds).setOnClickListener {
            saveVisionFieldsTo(visionEngine)
            visionEngine = VisionRecognizers.ENGINE_DEEPSEEK
            db.setSetting("vision_engine", visionEngine)
            loadVisionFields(); paintEngine()
        }

        // 风格四卡
        val styleCards = listOf(
            Triple("嘴毒", R.id.style_zaidu, R.id.style_zaidu_radio to R.id.style_zaidu_tag),
            Triple("鸡汤", R.id.style_jitang, R.id.style_jitang_radio to R.id.style_jitang_tag),
            Triple("跪求", R.id.style_guiqiu, R.id.style_guiqiu_radio to R.id.style_guiqiu_tag),
            Triple("自定义", R.id.style_custom, R.id.style_custom_radio to R.id.style_custom_tag)
        )
        fun paintStyle() {
            for ((name, cardId, ids) in styleCards) {
                val on = name == style
                v.findViewById<LinearLayout>(cardId).setBackgroundResource(
                    if (on) R.drawable.bg_style_on else R.drawable.bg_style_off
                )
                v.findViewById<TextView>(ids.first).setBackgroundResource(
                    if (on) R.drawable.bg_radio_on else R.drawable.bg_radio_off
                )
                v.findViewById<TextView>(ids.second).visibility = if (on) View.VISIBLE else View.GONE
            }
            v.findViewById<EditText>(R.id.style_custom_text).visibility =
                if (style == "自定义") View.VISIBLE else View.GONE
        }
        for ((name, cardId, _) in styleCards) {
            v.findViewById<LinearLayout>(cardId).setOnClickListener {
                style = name
                paintStyle()
            }
        }
        paintStyle()

        // 每日提醒明细(2026-11-07):总开关联动 + 时间选择 + 自定义模板 + 权限提示
        noonTime = db.getSetting("remind_meal_noon_time", "13:30")
        nightTime = db.getSetting("remind_meal_night_time", "21:00")
        workoutTime = db.getSetting("remind_workout_time", "19:00")
        v.findViewById<Switch>(R.id.sw_remind_noon).isChecked = db.getSetting("remind_meal_noon", "1") == "1"
        v.findViewById<Switch>(R.id.sw_remind_night).isChecked = db.getSetting("remind_meal_night", "1") == "1"
        v.findViewById<Switch>(R.id.sw_remind_workout).isChecked = db.getSetting("remind_workout_on", "1") == "1"
        v.findViewById<EditText>(R.id.et_remind_custom).setText(db.getSetting("remind_custom_texts", ""))

        fun paintRemindBox() {
            v.findViewById<View>(R.id.remind_box).visibility =
                if (v.findViewById<Switch>(R.id.switch_remind).isChecked) View.VISIBLE else View.GONE
        }
        v.findViewById<Switch>(R.id.switch_remind).setOnCheckedChangeListener { _, _ -> paintRemindBox() }
        paintRemindBox()

        fun paintTime(id: Int, t: String) { v.findViewById<TextView>(id).text = t }
        paintTime(R.id.tv_remind_noon_time, noonTime)
        paintTime(R.id.tv_remind_night_time, nightTime)
        paintTime(R.id.tv_remind_workout_time, workoutTime)
        fun pickTime(id: Int, cur: String, onOk: (String) -> Unit) {
            val (h, m) = Reminders.parseHm(cur, 12, 0)
            TimePickerDialog(activity, { _, hh, mm ->
                val s = String.format(Locale.US, "%02d:%02d", hh, mm)
                onOk(s)
                paintTime(id, s)
            }, h, m, true).show()
        }
        v.findViewById<TextView>(R.id.tv_remind_noon_time).setOnClickListener {
            pickTime(R.id.tv_remind_noon_time, noonTime) { noonTime = it }
        }
        v.findViewById<TextView>(R.id.tv_remind_night_time).setOnClickListener {
            pickTime(R.id.tv_remind_night_time, nightTime) { nightTime = it }
        }
        v.findViewById<TextView>(R.id.tv_remind_workout_time).setOnClickListener {
            pickTime(R.id.tv_remind_workout_time, workoutTime) { workoutTime = it }
        }

        fun hasNotifyPerm(): Boolean =
            Build.VERSION.SDK_INT < 33 || act.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        fun paintPerm() {
            v.findViewById<TextView>(R.id.tv_remind_perm).visibility =
                if (hasNotifyPerm()) View.GONE else View.VISIBLE
        }
        paintPerm()
        // 首次进设置页且未授权 → 弹一次授权(拒绝后不再弹,防骚扰)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifyPerm() &&
            db.getSetting("remind_perm_asked", "0") != "1"
        ) {
            db.setSetting("remind_perm_asked", "1")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8001)
        }

        // 头像(2026-10-18):我的 + AI 四风格,点行换图/恢复默认
        val content = v.findViewById<LinearLayout>(R.id.settings_content)
        val nicknameView = v.findViewById<EditText>(R.id.settings_nickname)
        content.addView(buildAvatarSection(act), content.indexOfChild(nicknameView) + 1)

        // 性别两态
        fun paintGender() {
            val maleBtn = v.findViewById<TextView>(R.id.gender_male)
            val femaleBtn = v.findViewById<TextView>(R.id.gender_female)
            maleBtn.setBackgroundResource(if (male) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            maleBtn.setTextColor(if (male) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
            femaleBtn.setBackgroundResource(if (!male) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            femaleBtn.setTextColor(if (!male) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
        }
        v.findViewById<TextView>(R.id.gender_male).setOnClickListener { male = true; paintGender() }
        v.findViewById<TextView>(R.id.gender_female).setOnClickListener { male = false; paintGender() }
        paintGender()

        // 保存
        v.findViewById<TextView>(R.id.settings_save).setOnClickListener { save(v, db, act) }

        // 返回 / 体脂秤入口
        v.findViewById<TextView>(R.id.settings_back).setOnClickListener { act.backToScreen() }
        v.findViewById<View>(R.id.settings_scale_entry).setOnClickListener { act.openScale() }

        // 同步健康数据(M3 手表收割:点击→后台收割→结果回显)
        val syncResult = v.findViewById<TextView>(R.id.settings_sync_result)
        v.findViewById<TextView>(R.id.settings_sync_health).setOnClickListener {
            syncResult.text = "同步中…"
            Thread {
                val report = runCatching {
                    VivoHealthHarvester.harvest(activity.applicationContext)
                }.getOrElse {
                    VivoHealthHarvester.Report(false, "同步失败:${it.message ?: "未知错误"}")
                }
                activity?.runOnUiThread {
                    syncResult.text = report.summary
                    act.toast(if (report.ok) "同步完成" else "同步未落数据,看结果说明")
                }
            }.start()
        }

        TextCopyUtil.apply(v)   // 全文字可复制(2026-10-17),含同步结果说明
        return v
    }

    /**
     * 头像区(2026-10-18 用户):我的头像 + AI 四风格;默认 emoji,可换相册图。
     * 程序化拼装,避免在设置 XML 里再抄五份行布局。
     */
    private fun buildAvatarSection(act: MainActivity): View {
        avatarPreviews.clear()
        val density = resources.displayMetrics.density
        fun dp(x: Int) = (x * density).toInt()

        val section = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        section.addView(TextView(act).apply {
            text = "头像"
            textSize = 15f
            setTextColor(0xFF141414.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, 0)
        })
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_white)
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }

        val targets = mutableListOf<Triple<Boolean, String?, String>>()
        targets.add(Triple<Boolean, String?, String>(false, null, "我的头像"))
        for (s in AvatarUtil.AI_STYLES) targets.add(Triple(true, s, "AI · $s"))

        for ((isAi, styleKey, label) in targets) {
            val preview = AvatarView(act).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                bind(isAi, styleKey)
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener { showAvatarDialog(act, isAi, styleKey, label) }
            }
            row.addView(preview)
            row.addView(TextView(act).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF141414.toInt())
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(TextView(act).apply {
                text = "换图 ›"
                textSize = 11f
                setTextColor(0xFF666666.toInt())
            })
            card.addView(row)
            avatarPreviews.add(Triple(preview, isAi, styleKey))
        }
        section.addView(card)
        return section
    }

    private fun showAvatarDialog(
        act: MainActivity, isAi: Boolean, styleKey: String?, label: String
    ) {
        val has = AvatarUtil.hasCustom(act, isAi, styleKey)
        val options = if (has) arrayOf("从相册选图", "恢复默认") else arrayOf("从相册选图")
        AlertDialog.Builder(act)
            .setTitle("更换「$label」")
            .setItems(options) { _, which ->
                if (which == 0) {
                    act.openAvatarPick(AvatarUtil.slugFor(isAi, styleKey))
                } else {
                    AvatarUtil.reset(act, isAi, styleKey)
                    for ((view, ai, st) in avatarPreviews) {
                        if (ai == isAi && st == styleKey) view.bind(ai, st)
                    }
                    act.toast("已恢复默认头像")
                }
            }
            .show()
    }

    private fun save(v: View, db: DbHelper, act: MainActivity) {
        db.setSetting("style", style)
        db.setSetting(
            "nickname",
            v.findViewById<EditText>(R.id.settings_nickname).text.toString().trim().ifBlank { "老板" }
        )
        db.setSetting(
            "height_cm",
            v.findViewById<EditText>(R.id.settings_height).text.toString().trim().ifBlank { "170" }
        )
        db.setSetting(
            "age",
            v.findViewById<EditText>(R.id.settings_age).text.toString().trim().ifBlank { "30" }
        )
        db.setSetting("gender", if (male) "男" else "女")
        db.setSetting("style_custom", v.findViewById<EditText>(R.id.style_custom_text).text.toString())
        db.setSetting("watch_on", if (v.findViewById<Switch>(R.id.switch_watch).isChecked) "1" else "0")
        db.setSetting("scale_on", if (v.findViewById<Switch>(R.id.switch_scale).isChecked) "1" else "0")
        db.setSetting("remind_on", if (v.findViewById<Switch>(R.id.switch_remind).isChecked) "1" else "0")
        // 每日提醒明细(2026-11-07):写键 + 立刻重排闹钟
        db.setSetting("remind_meal_noon", if (v.findViewById<Switch>(R.id.sw_remind_noon).isChecked) "1" else "0")
        db.setSetting("remind_meal_noon_time", noonTime)
        db.setSetting("remind_meal_night", if (v.findViewById<Switch>(R.id.sw_remind_night).isChecked) "1" else "0")
        db.setSetting("remind_meal_night_time", nightTime)
        db.setSetting("remind_workout_on", if (v.findViewById<Switch>(R.id.sw_remind_workout).isChecked) "1" else "0")
        db.setSetting("remind_workout_time", workoutTime)
        db.setSetting("remind_custom_texts", v.findViewById<EditText>(R.id.et_remind_custom).text.toString())
        Reminders.reschedule(act)
        db.setSetting("auto_save_meal", if (v.findViewById<Switch>(R.id.switch_auto_save).isChecked) "1" else "0")
        // 视觉识别:引擎 + 该引擎的 Key / 模型 ID(模型留空即用默认)
        db.setSetting("vision_engine", visionEngine)
        db.setSetting(
            "vision_api_key_$visionEngine",
            v.findViewById<EditText>(R.id.vision_api_key).text.toString().trim()
        )
        db.setSetting(
            "vision_model_$visionEngine",
            v.findViewById<EditText>(R.id.vision_model_id).text.toString().trim()
        )
        // AI 大脑接线(M2 对话):三键只进 settings 表
        db.setSetting("ds_api_base", v.findViewById<EditText>(R.id.ds_api_base).text.toString().trim())
        db.setSetting("ds_model", v.findViewById<EditText>(R.id.ds_model).text.toString().trim())
        db.setSetting("ds_api_key", v.findViewById<EditText>(R.id.ds_api_key).text.toString().trim())
        act.toast("设置已保存")
    }
}
