package dev.xd.fitnessbutler.scale

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 蚂蚁阿福 FG2508WB(货号 A1)体重秤 BLE 帧解码 + BIA 体脂公式。
 * 收编自 vivo健康数据/scale-collector(逻辑照搬,WEIGHT_OFFSET 不改)。
 *
 * 协议来源:开源项目 lxgxdx/icomon_scale(AFU-WL-TZ-A1 校准版)交叉验证
 * 蚂蚁AI健康管家 APK (com.antgroup.aijk.android) 内香山 ICOMON 秤 SDK 常量。
 *
 * 帧格式:0xAC 头,byte[2] = 类型
 *   0x80 实时重量:byte[3:6] 24位大端 raw,kg = (raw - WEIGHT_OFFSET) / 1000
 *   0x02 体脂完成:byte[6:8] 16位大端阻抗(直接为 Ω),byte[10:13] 体重 raw 校验
 */
object ScaleDecoder {

    /** 体重零点偏移(货号 A1 校准:raw=6884794 时秤显 69.05kg)。首次实测对一次秤显读数校准 */
    const val WEIGHT_OFFSET = 6815744 // 0x680000

    data class Profile(val heightCm: Int, val age: Int, val male: Boolean)

    data class Result(
        val kind: String,          // "weight" | "bodyfat"
        val weightKg: Double,
        val rawWeight: Int,
        val impedance: Double?,    // 仅体脂包
        val metrics: JSONObject?,  // 仅体脂包:完整 15 项指标
    )

    fun hex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }

    fun parse(data: ByteArray): Result? {
        if (data.size < 6) return null
        if ((data[0].toInt() and 0xFF) != 0xAC) return null
        return when (data[2].toInt() and 0xFF) {
            0x80 -> parseWeight(data)
            0x02 -> parseBodyFat(data)
            else -> null
        }
    }

    private fun raw24(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 16) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            (data[off + 2].toInt() and 0xFF)

    private fun parseWeight(data: ByteArray): Result {
        val raw = raw24(data, 3)
        val kg = (raw - WEIGHT_OFFSET) / 1000.0
        return Result("weight", kg, raw, null, null)
    }

    private fun parseBodyFat(data: ByteArray): Result? {
        val imp = if (data.size >= 8)
            ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        else 0
        val rawW = if (data.size >= 13) raw24(data, 10) else 0
        val kg = if (rawW != 0) (rawW - WEIGHT_OFFSET) / 1000.0 else Double.NaN
        if (imp <= 0 || kg.isNaN()) return null
        return Result("bodyfat", kg, rawW, imp.toDouble(), calc(kg, imp.toDouble(), profile))
    }

    /** 当前用户档案,由设置页读入后写到这里(解码入口会用到) */
    @Volatile
    var profile: Profile = Profile(170, 30, true)

    /** BIA 体脂 15 项指标(Icomon App 同款公式) */
    fun calc(weight: Double, impedance: Double, p: Profile): JSONObject {
        val h = p.heightCm.toDouble()
        val age = p.age.toDouble()
        val bmi = weight / ((h / 100) * (h / 100))

        val lbmCoeff = ((h * 9.058 / 100) * (h / 100) +
            weight * 0.32 + 12.226 -
            impedance * 0.0068 -
            age * 0.0542)

        val fatConst: Double
        var fatCoeff = 1.0
        if (p.male) {
            fatConst = 0.8
            if (weight < 61) fatCoeff = 0.98
        } else {
            fatConst = if (age <= 49) 9.25 else 7.25
            if (weight > 60) fatCoeff = 0.96
            else if (weight < 50) fatCoeff = 1.02
        }

        var fatPct = (1.0 - (((lbmCoeff - fatConst) * fatCoeff) / weight)) * 100
        fatPct = fatPct.coerceIn(3.0, 60.0)

        var waterPct = (100 - fatPct) * 0.7
        waterPct = (waterPct * 1.017).coerceIn(35.0, 75.0)

        var boneMass = if (p.male) {
            (0.18016894 - (lbmCoeff * 0.05158)) * -1
        } else {
            (0.245691014 - (lbmCoeff * 0.07158)) * -1
        }
        if (boneMass > 2.2) boneMass += 0.1 else boneMass -= 0.1
        boneMass = boneMass.coerceAtLeast(0.5)
        val bonePct = (boneMass * 0.85 / weight * 100 * 10).roundToInt() / 10.0

        val fatMass = fatPct * 0.01 * weight
        val muscleMass = weight - fatMass - boneMass * 0.85
        val muscleRate = (muscleMass / weight * 100 * 10).roundToInt() / 10.0
        val skeletal = (muscleRate * 0.558 * 10).roundToInt() / 10.0

        var protein = (muscleRate - waterPct).coerceIn(5.0, 32.0)
        protein = (protein * 10).roundToInt() / 10.0

        val ffm = ((weight - fatMass) * 10).roundToInt() / 10.0
        val bmr = (370 + 21.6 * ffm).roundToInt()

        var visceral = (bmi * 0.3 - age * 0.05 + 0.4).coerceIn(1.0, 50.0)
        visceral = (visceral * 10).roundToInt() / 10.0
        val subcut = ((fatPct * 0.71) * 10).roundToInt() / 10.0

        val physique = when {
            bmi < 18.5 -> "偏瘦"
            bmi < 24 -> "标准"
            bmi < 28 -> "超重"
            else -> "肥胖"
        }

        return JSONObject().apply {
            put("bmi", (bmi * 10).roundToInt() / 10.0)
            put("body_fat_pct", (fatPct * 10).roundToInt() / 10.0)
            put("water_pct", (waterPct * 10).roundToInt() / 10.0)
            put("bone_pct", bonePct)
            put("muscle_rate_pct", muscleRate)
            put("skeletal_muscle_pct", skeletal)
            put("protein_pct", protein)
            put("ffm_kg", ffm)
            put("bmr_kcal", bmr)
            put("visceral_fat", visceral)
            put("bone_mass_kg", (boneMass * 10).roundToInt() / 10.0)
            put("muscle_mass_kg", (muscleMass * 10).roundToInt() / 10.0)
            put("subcutaneous_fat_pct", subcut)
            put("physique", physique)
            put("impedance_ohm", impedance)
            put("lbm_coeff", lbmCoeff)
        }
    }
}
