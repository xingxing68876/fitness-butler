package dev.xd.fitnessbutler.rec

/**
 * 食物识别器插拔接口(M1.5 起 AI 直出:拍照 → 视觉 API → 菜品/克重/热量清单直接进编辑页)。
 * 实现见 VisionRecognizers.kt(豆包 Doubao / 智谱 GLM 双引擎)。
 * 约定:recognize 是同步阻塞调用,网络在调用方自己的后台线程里跑;回调也在该线程。
 */
data class RecognizedFood(
    val name: String,
    val grams: Double,
    val kcal: Double,
    /** 三大营养素(克,2026-10-17:视觉 API 估计值) */
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    /** 拿不准时的候选菜名(≤2 个,2026-10-17 治"肉蟹煲认成大盘鸡") */
    val candidates: List<String> = emptyList(),
    val source: String = "AI识别",
    val foodId: Long = -1
)

interface FoodRecognizer {
    /** 识别照片里的菜品;成功给列表(空=没认出来),失败走 error */
    fun recognize(photoPath: String, callback: (items: List<RecognizedFood>?, error: String?) -> Unit)
}
