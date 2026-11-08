package dev.xd.fitnessbutler.data

import dev.xd.fitnessbutler.db.DbHelper
import org.json.JSONObject
import java.util.Locale

/**
 * 按日聚合指标读取层(日历图表/一日详情共用)。
 *
 * 规则(设计定案):没有数据的日期一律返回 null,图表断线留空,绝不画假数据。
 * - 体重/体脂率/皮下脂肪/骨骼肌 = weighings 表当日最后一次称重(metrics_json 取值)
 * - 运动消耗 = activities 表当日合计
 * - 训练负荷/睡眠质量/血氧·日均/压力·日均 = 手表数据,M3 才收割;
 *   预留 daily_stats(date, metric, value) / hourly_stats(date, hour, metric, value) 两张聚合表,
 *   M3 把手表收割结果落进这两张表,这里自动点亮(表不存在=整条断线)。
 */
object DailyMetrics {

    // ---------- 指标定义(侧栏顺序 = demo) ----------

    data class Meta(
        val id: String,
        val label: String,
        val color: Int,
        val bubbleUnit: String,   // 气泡里带的单位
        val sidebarUnit: String,  // 侧栏值带的单位
        val decimals: Int
    )

    val METRICS: List<Meta> = listOf(
        Meta("weight", "体重", 0xFF141414.toInt(), "kg", "", 2),
        Meta("bodyfat", "体脂率", 0xFF8FB24E.toInt(), "%", "%", 1),
        Meta("subcut", "皮下脂肪", 0xFFB8D4E8.toInt(), "%", "", 1),
        Meta("skelmus", "骨骼肌", 0xFFF2C4A8.toInt(), "kg", "", 1),
        Meta("load", "训练负荷", 0xFFE07B39.toInt(), "", "", 0),
        Meta("sleep", "睡眠质量", 0xFF5A8F2E.toInt(), "", "", 0),
        Meta("spo2", "血氧·日均", 0xFF4A90D9.toInt(), "%", "%", 0),
        Meta("stress", "压力·日均", 0xFFC94F7C.toInt(), "", "", 0),
        Meta("burn", "运动消耗", 0xFF7B61FF.toInt(), "kcal", "", 0)
    )

    fun meta(id: String): Meta = METRICS.first { it.id == id }

    fun fmt(meta: Meta, v: Double?): String {
        if (v == null) return "--"
        val num = if (meta.decimals == 0) v.toInt().toString()
        else String.format(Locale.US, "%.${meta.decimals}f", v)
        return num + meta.sidebarUnit
    }

    fun fmtBubble(meta: Meta, v: Double?): String {
        if (v == null) return "--"
        val num = if (meta.decimals == 0) v.toInt().toString()
        else String.format(Locale.US, "%.${meta.decimals}f", v)
        return num + meta.bubbleUnit
    }

    // ---------- 每日单值 ----------

    /** 某日某指标,没有=null */
    fun daily(db: DbHelper, metricId: String, date: String): Double? = when (metricId) {
        "weight" -> db.weighingsOnDate(date).lastOrNull()?.weightKg
        "bodyfat" -> weighingMetric(db, date, "body_fat_pct")
        "subcut" -> weighingMetric(db, date, "subcutaneous_fat_pct")
        "skelmus" -> weighingMetric(db, date, "skeletal_muscle_pct")
        "burn" -> db.burnKcalOn(date).takeIf { it > 0 }
        "load" -> watchDaily(db, date, "training_load")
        "sleep" -> watchDaily(db, date, "sleep_quality")
        "spo2" -> watchDaily(db, date, "spo2_avg")
        "stress" -> watchDaily(db, date, "stress_avg")
        else -> null
    }

    /** 一批日期的序列(断点=null) */
    fun series(db: DbHelper, metricId: String, dates: List<String>): List<Double?> =
        dates.map { daily(db, metricId, it) }

    /** 最新非空值(侧栏展示用),从近往远找 */
    fun latest(db: DbHelper, metricId: String, dates: List<String>): Double? {
        for (d in dates.asReversed()) {
            val v = daily(db, metricId, d)
            if (v != null) return v
        }
        return null
    }

    private fun weighingMetric(db: DbHelper, date: String, key: String): Double? {
        val w = db.weighingsOnDate(date).lastOrNull() ?: return null
        val json = w.metricsJson ?: return null
        return try {
            val v = JSONObject(json).optDouble(key, Double.NaN)
            if (v.isNaN()) null else v
        } catch (_: Exception) {
            null
        }
    }

    /** 手表日聚合(M3):daily_stats 表存在才读,不存在=断线 */
    private fun watchDaily(db: DbHelper, date: String, metric: String): Double? {
        if (!tableExists(db, "daily_stats")) return null
        val c = db.readableDatabase.rawQuery(
            "SELECT value FROM daily_stats WHERE date=? AND metric=?",
            arrayOf(date, metric)
        )
        c.use { if (it.moveToFirst()) return it.getDouble(0) }
        return null
    }

    // ---------- 详情页:小时均值 / 睡眠分期 ----------

    /** 手表小时均值(0–23),血氧/压力;M3 前=null,该层不画 */
    fun hourly(db: DbHelper, metric: String, date: String): DoubleArray? {
        if (!tableExists(db, "hourly_stats")) return null
        val arr = DoubleArray(24) { Double.NaN }
        var any = false
        val c = db.readableDatabase.rawQuery(
            "SELECT hour, value FROM hourly_stats WHERE date=? AND metric=?",
            arrayOf(date, metric)
        )
        c.use {
            while (it.moveToNext()) {
                val h = it.getInt(0)
                if (h in 0..23) { arr[h] = it.getDouble(1); any = true }
            }
        }
        return if (any) arr else null
    }

    /** 睡眠分期段(起止小时 + 0深睡/1浅睡/2REM/3清醒);M3 前=null,顶条不画 */
    data class SleepSeg(val startHour: Double, val endHour: Double, val stage: Int)

    fun sleepStages(db: DbHelper, date: String): List<SleepSeg>? {
        if (!tableExists(db, "sleep_stages")) return null
        val out = mutableListOf<SleepSeg>()
        val c = db.readableDatabase.rawQuery(
            "SELECT start_hour, end_hour, stage FROM sleep_stages WHERE date=? ORDER BY start_hour",
            arrayOf(date)
        )
        c.use {
            while (it.moveToNext()) {
                out.add(SleepSeg(it.getDouble(0), it.getDouble(1), it.getInt(2)))
            }
        }
        return out.ifEmpty { null }
    }

    /** 睡眠时长(小时);M3 前=null */
    fun sleepHours(db: DbHelper, date: String): Double? =
        watchDaily(db, date, "sleep_hours")

    private fun tableExists(db: DbHelper, name: String): Boolean {
        val c = db.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
        )
        c.use { return it.moveToFirst() }
    }
}
