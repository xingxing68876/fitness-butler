package dev.xd.fitnessbutler.health

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import dev.xd.fitnessbutler.db.DbHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * vivo 健康数据收割器(M3 第一枪):把手机 vivo 健康 provider 里的手表同步数据收进
 * daily_stats / hourly_stats / sleep_stages 三张分析表 + activities(消耗),
 * 日历图表由 DailyMetrics 读取层零改动点亮。
 *
 * provider 实际粒度(照 akari-pulse 逆向底账,按实落表不硬凑):
 *  1) content://com.vivo.health.provider/sleep —— 睡眠日记录(逐行=一天,38 列):
 *     DATE/ENTER_TIME/EXIT_TIME/TOTAL_DURATION/SCORE + 深浅REM清醒四张 {enterTime,exitTime} 区间列表。
 *     落表:sleep_quality=SCORE、sleep_hours=总时长(小时)、sleep_stages=分期段。
 *  2) content://com.vivo.health.provider.care/healthCare —— MYSELF_DATA JSON,只有
 *     心率/血氧/压力的**最新一次快照**(值+各自时间戳),无序列、无日均:
 *     落表:spo2_avg/stress_avg = 该时间戳当日的最新读数(非真日均,如实标注);心率无图表键,只回显不落表。
 *  3) content://com.vivo.assistant.step.provider(call:updateTodaySportAIDLBean)——
 *     **只有今天**的步数/距离/卡路里快照,无历史、无小时桶:
 *     卡路里入 activities(source=手表,幂等),步数/距离只回显(9 指标无对齐键,不发明新键)。
 *  训练负荷:三个 provider 都没有 → 保持断线;hourly_stats:无小时序列 → 本轮不落。
 *
 * 权限:1)/2) 走 com.vivo.health.widget.permission(signature|privileged,机主 pm grant 可放行,
 * 重装 APK 清授权);3) 普通权限 com.vivo.assistant.StepProvider,安装即得。
 * 全程 ContentResolver 纯框架,零第三方依赖;无权限只回指引不崩溃。
 */
object VivoHealthHarvester {

    const val PERM_VIVO_HEALTH = "com.vivo.health.widget.permission"
    // 2026-10-18 实测定案(V2055A):该权限 signature|privileged 对第三方 APP 不可授(pm grant 双包
    // 同拒,shell 直读 provider 同拒,vivo 健康也不写 Health Connect)——睡眠/血氧/压力暂无数据源,
    // 相关收割路径保留(换到权限可授的机型即自动活),同步结果逐路自带「不可读(系统不开放)」说明

    private val SLEEP_URI: Uri = Uri.parse("content://com.vivo.health.provider/sleep")
    private val CARE_URI: Uri = Uri.parse("content://com.vivo.health.provider.care/healthCare")
    private val STEP_URI: Uri = Uri.parse("content://com.vivo.assistant.step.provider")

    data class Report(
        val ok: Boolean,             // 是否有任何一路成功落表
        val summary: String          // 中文回显
    )

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** 同步收割一次(同步阻塞,调用方放后台线程) */
    fun harvest(ctx: Context): Report {
        val db = DbHelper.get(ctx)
        appCtxRef = ctx.applicationContext
        val parts = mutableListOf<String>()
        var anyOk = false

        // ---------- 1/2) 睡眠 + 生命体 ----------
        // 2026-10-18:拆掉自查门(signature 权限永远 DENIED,自查=永远查不了),直接试查,
        // 拦截以 SecurityException 兜底。V2055A 实测必被拦;若未来换机权限可授则此路自动活
        val sleep = harvestSleep(db, ctx)
        parts.add(sleep.text)
        anyOk = anyOk || sleep.ok
        val vitals = harvestVitals(db, ctx)
        parts.add(vitals.text)
        anyOk = anyOk || vitals.ok

        // ---------- 3) 今日步数/卡路里(普通权限,无需 grant) ----------
        val steps = harvestSteps(db, ctx)
        parts.add(steps.text)
        anyOk = anyOk || steps.ok

        // ---------- 4) 2026-10-26 数据面探针:external/getSportData + Exercise/Trend provider ----------
        // 结果进 logcat(tag=VitalsSampler)+回显一行,异常全兜底,绝不让探针炸掉主收割
        val probe = probeExtraProviders()
        parts.add(probe)

        val summary = parts.joinToString(" | ")
        return Report(anyOk, summary)
    }

    /**
     * 2026-10-26 深挖 vivo 健康其余 provider(dex 底账):
     * - content://com.vivo.health.externalprovider + call("getSportData") → 运动数据包(候选:vo2/负荷)
     * - content://com.vivo.health.provider.ExerciseProvider → 运动记录表
     * - content://com.vivo.health.provider.TrendSkillProvider → 桌面趋势卡(vo2/负荷/血氧趋势候选)
     * 有响应就打列名/键名清单进日志,先摸清数据面再决定落哪些表。
     */
    private fun probeExtraProviders(): String {
        val out = mutableListOf<String>()
        // ① external provider 的 call 通道
        runCatching {
            val uri = Uri.parse("content://com.vivo.health.externalprovider")
            val extras = Bundle().apply { putBoolean("ignore", true) }
            val b = appCtxRef?.contentResolver?.call(uri, "getSportData", null, extras)
            val keys = b?.keySet()?.toList()
            android.util.Log.d("VitalsSampler", "external getSportData keys=$keys bundle=$b")
            out.add(if (b != null) "external✅${keys?.size ?: 0}键" else "external空")
        }.onFailure { out.add("external✗${it.javaClass.simpleName.take(12)}") }
        // ② ExerciseProvider 裸查
        runCatching {
            appCtxRef?.contentResolver?.query(
                Uri.parse("content://com.vivo.health.provider.ExerciseProvider"), null, null, null, null
            )?.use { c ->
                android.util.Log.d("VitalsSampler",
                    "ExerciseProvider rows=${c.count} cols=${c.columnNames.toList()}")
                out.add("exercise✅${c.count}行")
            } ?: out.add("exercise空")
        }.onFailure { out.add("exercise✗${it.javaClass.simpleName.take(12)}") }
        // ③ TrendSkillProvider 裸查
        runCatching {
            appCtxRef?.contentResolver?.query(
                Uri.parse("content://com.vivo.health.provider.TrendSkillProvider"), null, null, null, null
            )?.use { c ->
                android.util.Log.d("VitalsSampler",
                    "TrendSkill rows=${c.count} cols=${c.columnNames.toList()}")
                out.add("trend✅${c.count}行")
            } ?: out.add("trend空")
        }.onFailure { out.add("trend✗${it.javaClass.simpleName.take(12)}") }
        return "探针:" + out.joinToString(" ")
    }

    /** 探针用 ctx(harvest 入口注入,避免改私有函数签名链) */
    @Volatile
    private var appCtxRef: Context? = null

    // ---------- 睡眠 ----------

    private fun harvestSleep(db: DbHelper, ctx: Context): PartResult {
        return try {
            var days = 0
            var segs = 0
            ctx.contentResolver.query(SLEEP_URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val cols = c.columnNames
                    fun str(name: String): String? {
                        val i = c.getColumnIndex(name)
                        return if (i < 0 || c.isNull(i)) null else c.getString(i)
                    }
                    val date = str("DATE") ?: continue
                    if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(date)) continue

                    // 睡眠质量评分 / 总时长(小时)
                    str("SCORE")?.toDoubleOrNull()?.let {
                        db.replaceDailyStat(date, "sleep_quality", it)
                    }
                    str("TOTAL_DURATION")?.toLongOrNull()?.let { ms ->
                        if (ms > 0) db.replaceDailyStat(date, "sleep_hours", ms / 3600000.0)
                    }

                    // 2026-10-26 全量薅(sleep 表 38 列底账,akari-pulse 逆向对齐):
                    // 各期时长(毫秒→小时)+夜睡/午睡拆分+深睡连续性,入 daily_stats 新键,图表暂不上
                    val msH = 3600000.0
                    fun colMs(key: String, col: String) {
                        str(col)?.toLongOrNull()?.let { ms -> if (ms > 0) db.replaceDailyStat(date, key, ms / msH) }
                    }
                    colMs("sleep_deep_h", "DEEP_SLEEP_DURATION")
                    colMs("sleep_light_h", "LIGHT_SLEEP_DURATION")
                    colMs("sleep_rem_h", "REM_SLEEP_DURATION")
                    colMs("sleep_awake_h", "AWAKE_SLEEP_DURATION")
                    colMs("sleep_night_h", "NIGHT_SLEEP_TOTAL")
                    colMs("sleep_nap_h", "NAP_TOTAL")
                    str("DEEP_SLEEP_CONTINUITY")?.toDoubleOrNull()?.let {
                        db.replaceDailyStat(date, "sleep_cont", it)
                    }

                    // 分期段:深睡=0 浅睡=1 REM=2 清醒=3(与 DayTimelineView 着色顺序一致)
                    db.clearSleepStages(date)
                    val listSpecs = listOf(
                        "DEEP_SLEEP_LIST" to 0,
                        "LIGHT_SLEEP_LIST" to 1,
                        "REM_SLEEP_LIST" to 2,
                        "AWAKE_SLEEP_LIST" to 3
                    )
                    for ((col, stage) in listSpecs) {
                        val raw = str(col) ?: continue
                        val n = writeStageIntervals(db, date, raw, stage)
                        segs += n
                    }
                    days++
                }
            }
            if (days > 0) PartResult(true, "睡眠 $days 天 · 分期 $segs 段(含深/浅/REM/夜/午拆分)")
            else PartResult(false, "睡眠:无数据")
        } catch (e: SecurityException) {
            PartResult(false, "睡眠:不可读(系统不开放)")
        } catch (e: Exception) {
            PartResult(false, "睡眠失败:${e.message?.take(60) ?: e.javaClass.simpleName}")
        }
    }

    /** 区间 JSON 数组 [{enterTime,exitTime}] → sleep_stages(跨午夜拆两段,小时=当日时刻) */
    private fun writeStageIntervals(db: DbHelper, date: String, raw: String, stage: Int): Int {
        val arr = try { JSONArray(raw) } catch (_: Exception) { return 0 }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val enter = o.optLong("enterTime", 0)
            val exit = o.optLong("exitTime", 0)
            if (enter <= 0 || exit <= enter) continue
            val s = hourOfDay(enter)
            val e = hourOfDay(exit)
            if (e >= s) {
                db.insertSleepStage(date, s, e, stage); n++
            } else {
                // 跨午夜:23:x→24 与 0→6:x 两段,都挂睡眠日 DATE(demo 同款画法)
                db.insertSleepStage(date, s, 24.0, stage)
                db.insertSleepStage(date, 0.0, e, stage); n += 2
            }
        }
        return n
    }

    private fun hourOfDay(epochMs: Long): Double {
        val c = Calendar.getInstance().apply { timeInMillis = epochMs }
        return c.get(Calendar.HOUR_OF_DAY) +
            c.get(Calendar.MINUTE) / 60.0 +
            c.get(Calendar.SECOND) / 3600.0
    }

    // ---------- 生命体快照(心率/血氧/压力) ----------

    private fun harvestVitals(db: DbHelper, ctx: Context): PartResult {
        return try {
            val row = ctx.contentResolver.query(CARE_URI, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else {
                    val i = c.getColumnIndex("MYSELF_DATA")
                    if (i < 0 || c.isNull(i)) null else c.getString(i)
                }
            }
            if (row.isNullOrBlank()) return PartResult(false, "血氧/压力:无数据")
            val json = try { JSONObject(row) } catch (_: Exception) {
                return PartResult(false, "血氧/压力:解析失败")
            }
            var spo2 = 0; var stress = 0; var hr = 0; var extra = 0
            // 快照落当日(时间戳所在日),键名对齐 DailyMetrics(spo2_avg/stress_avg)
            snapshot(json, "saO2Value", "saO2TimeStamp")?.let { (v, ts) ->
                db.replaceDailyStat(dateFmt.format(Date(ts)), "spo2_avg", v); spo2 = 1
            }
            snapshot(json, "pressureValue", "pressureTimeStamp")?.let { (v, ts) ->
                db.replaceDailyStat(dateFmt.format(Date(ts)), "stress_avg", v); stress = 1
            }
            // 心率:9 指标无对齐键,不发明,只回显
            snapshot(json, "heartValue", "heartTimeStamp")?.let { hr = 1 }
            // 2026-10-26 全量薅:MYSELF_DATA 里探最大摄氧量/训练负荷(dex 底账键名 vo2Max*/trainingLoad*)
            val known = arrayOf("heartValue", "saO2Value", "pressureValue",
                "heartTimeStamp", "saO2TimeStamp", "pressureTimeStamp", "sourceFrom")
            var hitVo2: Pair<Double, Long>? = null
            var hitLoad: Pair<Double, Long>? = null
            for (k in json.keys()) {
                if (k in known) continue
                val kk = k.lowercase(Locale.US)
                if (hitVo2 == null && ("vo2" in kk || "maxvo" in kk) && json.opt(k) is Number) {
                    val v = json.optDouble(k, Double.NaN)
                    if (!v.isNaN() && v > 0) hitVo2 = v to System.currentTimeMillis()
                }
                if (hitLoad == null && "training" in kk && json.opt(k) is Number) {
                    val v = json.optDouble(k, Double.NaN)
                    if (!v.isNaN() && v >= 0) hitLoad = v to System.currentTimeMillis()
                }
            }
            hitVo2?.let { (v, ts) -> db.replaceDailyStat(dateFmt.format(Date(ts)), "vo2_max", v); extra++ }
            hitLoad?.let { (v, ts) -> db.replaceDailyStat(dateFmt.format(Date(ts)), "training_load", v); extra++ }
            // 键名清单进日志(诊断数据面,不进 UI)
            val keyList = json.keys().asSequence().toList()
            android.util.Log.d("VitalsSampler", "MYSELF_DATA keys=$keyList")
            PartResult(
                spo2 + stress + hr + extra > 0,
                "血氧 $spo2 · 压力 $stress · 心率 $hr" +
                    (if (extra > 0) " · 摄氧/负荷 $extra" else "") +
                    "(快照)care 键 ${keyList.size} 个见日志"
            )
        } catch (e: SecurityException) {
            PartResult(false, "血氧/压力:不可读(系统不开放)")
        } catch (e: Exception) {
            PartResult(false, "血氧/压力失败:${e.message?.take(60) ?: e.javaClass.simpleName}")
        }
    }

    /** 快照读数:value 与时间戳都必须为正才作数(vivo 用 0,0 表示无) */
    private fun snapshot(json: JSONObject, vKey: String, tKey: String): Pair<Double, Long>? {
        val v = json.optDouble(vKey, Double.NaN)
        val t = json.optLong(tKey, 0)
        if (v.isNaN() || v <= 0 || t <= 0) return null
        return v to t
    }

    // ---------- 快照采样(采样器每 30 分钟调,攒小时曲线) ----------

    /**
     * 读一次血氧/压力/心率最新快照 → hourly_stats 当前小时槽(键 spo2_avg/stress_avg/hr_avg,
     * 与 daily_stats 日键对齐;同槽后来值覆盖,replaceHourlyStat 幂等)。
     * 无权限/无数据/解析失败一律静默返回 0,绝不抛(后台采样不打扰)。
     * 返回写入项数(0~3)。
     */
    fun sampleVitalsHourly(ctx: Context): Int {
        val tag = "VitalsSampler"
        return try {
            // 2026-10-18:同 harvest,不再自查 signature 权限,直接试查(异常兜底返 0)
            val row = ctx.contentResolver.query(CARE_URI, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else {
                    val i = c.getColumnIndex("MYSELF_DATA")
                    if (i < 0 || c.isNull(i)) null else c.getString(i)
                }
            } ?: return 0
            if (row.isBlank()) return 0
            val json = try { JSONObject(row) } catch (_: Exception) { return 0 }
            val db = DbHelper.get(ctx)
            val today = dateFmt.format(Date())
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            var n = 0
            // 时间戳校验仍走 snapshot(vivo 0,0=无),但小时槽记"采样时刻"(30 分钟一采,误差可接受)
            snapshot(json, "saO2Value", "saO2TimeStamp")?.let { (v, _) ->
                db.replaceHourlyStat(today, hour, "spo2_avg", v); n++
            }
            snapshot(json, "pressureValue", "pressureTimeStamp")?.let { (v, _) ->
                db.replaceHourlyStat(today, hour, "stress_avg", v); n++
            }
            snapshot(json, "heartValue", "heartTimeStamp")?.let { (v, _) ->
                db.replaceHourlyStat(today, hour, "hr_avg", v); n++
            }
            android.util.Log.d(tag, "sampleVitalsHourly wrote=$n today=$today hour=$hour")
            n
        } catch (e: Exception) {
            android.util.Log.w(tag, "sampleVitalsHourly failed", e)
            0
        }
    }

    // ---------- 今日步数/卡路里 ----------

    private fun harvestSteps(db: DbHelper, ctx: Context): PartResult {
        return try {
            val extras = Bundle().apply { putBoolean("ignore", true) }
            val bundle = ctx.contentResolver.call(STEP_URI, "updateTodaySportAIDLBean", null, extras)
                ?: return PartResult(false, "步数:无数据")
            @Suppress("DEPRECATION")
            fun num(key: String): Double? = (bundle.get(key) as? Number)?.toDouble()
            val steps = num("step")?.toInt()
            val dist = num("distance")
            val kcal = num("calorie")
            val today = dateFmt.format(Date())
            var saved = false
            if (kcal != null && kcal > 0) {
                db.replaceWatchActivity(today, tsFmt.format(Date()), kcal)
                saved = true
            }
            val distKm = dist?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: "--"
            PartResult(
                saved,
                "步数 ${steps ?: "--"} · ${distKm}km · 约 ${kcal?.toInt() ?: "--"}kcal" +
                    (if (saved) " 已入消耗" else "")
            )
        } catch (e: SecurityException) {
            PartResult(false, "步数:无权限")
        } catch (e: Exception) {
            PartResult(false, "步数失败:${e.message?.take(60) ?: e.javaClass.simpleName}")
        }
    }

    private data class PartResult(val ok: Boolean, val text: String)
}
