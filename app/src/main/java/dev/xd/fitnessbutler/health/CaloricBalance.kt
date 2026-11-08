package dev.xd.fitnessbutler.health

import dev.xd.fitnessbutler.db.DbHelper
import org.json.JSONObject

/**
 * 热量平衡计算 v2(2026-10-19):热量缺口加入人体基础代谢,不再只算「吃−动」。
 *
 * 总消耗 = BMR(基础代谢) + 活动消耗(手表全天活动)。
 * 缺口   = 摄入 − (BMR + 活动消耗);负数=缺口(减脂),正数=盈余。
 *
 * BMR 三档来源(由精到粗自动降级,2026-10-19 设计决策:按性别/肌肉量定基础代谢):
 *  1. 秤直出 bmr_kcal —— 阿福秤 BIA 体脂包自带(Katch-McArdle 口径,含肌肉量,最准),
 *     取最近 30 天内最近一次有 metrics 的称重;
 *  2. Katch-McArdle 公式 —— BMR = 370 + 21.6 × 瘦体重(kg),瘦体重=体重×(1−体脂率);
 *  3. Mifflin-St Jeor 公式 —— 现行医学主流估算:
 *     男 BMR = 10×体重 + 6.25×身高 − 5×年龄 + 5;女 同式 −161(体重缺省 70kg 兜底)。
 * BMR 取当前值回填历史日期近似(体重短期波动小,误差可接受)。
 */
object CaloricBalance {

    data class Bmr(val kcal: Double, val source: String)

    const val DEFAULT_WEIGHT_KG = 70.0

    /** 当前基础代谢(三档自动降级,后台线程调用) */
    fun bmr(db: DbHelper): Bmr {
        // 1/2) 最近一次带体成分的称重(秤直出 bmr 或体脂率/瘦体重)
        val w = db.latestWeighing()
        if (w != null && !w.metricsJson.isNullOrBlank()) {
            runCatching {
                val m = JSONObject(w.metricsJson!!)
                // ① 秤直出(Katch-McArdle 口径,含肌肉量)
                m.optDouble("bmr_kcal", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let {
                    return Bmr(it, "秤测(含肌肉量)")
                }
                // ② Katch-McArdle:370 + 21.6 × 瘦体重
                val fatPct = m.optDouble("body_fat_pct", Double.NaN)
                if (!fatPct.isNaN() && fatPct in 3.0..60.0 && w.weightKg > 0) {
                    val lbm = w.weightKg * (1 - fatPct / 100.0)
                    return Bmr(370 + 21.6 * lbm, "瘦体重推算(Katch-McArdle)")
                }
                m.optDouble("ffm_kg", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let {
                    return Bmr(370 + 21.6 * it, "瘦体重推算(Katch-McArdle)")
                }
            }
        }
        // 3) Mifflin-St Jeor:性别/身高/年龄(settings)+ 最近体重(缺省 70)
        val male = db.getSetting("gender", "男") != "女"
        val h = db.getSetting("height_cm", "170").toDoubleOrNull() ?: 170.0
        val age = db.getSetting("age", "30").toDoubleOrNull() ?: 30.0
        val weight = w?.weightKg?.takeIf { it > 0 } ?: DEFAULT_WEIGHT_KG
        val bmrVal = 10 * weight + 6.25 * h - 5 * age + if (male) 5 else -161
        return Bmr(bmrVal, if (w?.weightKg != null) "Mifflin-St Jeor 估算" else "Mifflin-St Jeor 估算(默认体重)")
    }

    /** 当日总消耗 = BMR + 活动消耗(手表全天活动) */
    fun totalBurn(db: DbHelper, date: String): BurnBreakdown {
        val b = bmr(db)
        val activity = db.burnKcalOn(date)
        return BurnBreakdown(bmr = b.kcal, activity = activity, source = b.source)
    }

    /** 当日热量缺口 = 摄入 − (BMR + 活动消耗);负=缺口 */
    fun gapOn(db: DbHelper, date: String): Double {
        val intake = db.intakeKcalOn(date)
        val t = totalBurn(db, date)
        return intake - (t.bmr + t.activity)
    }

    data class BurnBreakdown(val bmr: Double, val activity: Double, val source: String) {
        val total: Double get() = bmr + activity
    }
}
