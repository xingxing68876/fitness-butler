package dev.xd.fitnessbutler.ui

import android.app.AlertDialog
import android.app.Fragment
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.data.FoodSeed
import dev.xd.fitnessbutler.db.DbHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 明细编辑页(M1.5 AI 直出):AI 识别清单直接预填,一键「记一笔」入账;
 * 手动增删改只是微调,「手记」为识别失败/没网兜底。
 * 来源:AI识别(默认)/ 库(手记时命中食物库)/ 手记。
 */
@Suppress("DEPRECATION")
class MealEditFragment : Fragment() {

    companion object {
        /** mealId>0 = 编辑/核对一笔已有账(自动入账核对、详情页点进改删);-1 = 新记 */
        fun newInstance(
            photoPath: String?, prefillJson: String?, notice: String?, mealId: Long = -1L
        ): MealEditFragment =
            MealEditFragment().apply {
                arguments = Bundle().apply {
                    putString("photo", photoPath)
                    putString("prefill", prefillJson)
                    putString("notice", notice)
                    putLong("mealId", mealId)
                }
            }

        /** 详情页点三餐行进:纯改一笔(从库里载入) */
        fun newForEdit(mealId: Long): MealEditFragment =
            newInstance(null, null, "改这笔:改完点「✓ 记一笔」保存;「删这笔」整笔撤销", mealId)

        /** 按当前时刻猜餐次(与编辑页默认一致;批量自动入账共用) */
        fun guessMealType(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..9 -> "早餐"
            in 10..14 -> "午餐"
            in 15..20 -> "晚餐"
            else -> "加餐"
        }

        /** 识别结果 JSON([{"name","grams","kcal","protein","carbs","fat","candidates","source","foodId"}])→ 明细列表(预填/自动入账共用) */
        fun parseItemsJson(json: String): List<DbHelper.MealItem> {
            val out = mutableListOf<DbHelper.MealItem>()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        DbHelper.MealItem(
                            name = o.optString("name"),
                            grams = o.optDouble("grams", 0.0),
                            kcal = o.optDouble("kcal", 0.0),
                            source = o.optString("source", DbHelper.SRC_AI_VISION),
                            foodId = o.optLong("foodId", -1),
                            protein = o.optDouble("protein", 0.0),
                            carbs = o.optDouble("carbs", 0.0),
                            fat = o.optDouble("fat", 0.0),
                            candidates = o.optJSONArray("candidates")?.let { a ->
                                (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } }
                            } ?: emptyList()
                        )
                    )
                }
            } catch (_: Exception) {
                // 解析失败返回已解析部分,不炸
            }
            return out
        }

        /** 批量识别自动入账:识别清单直接落库(与编辑页「记一笔」同一落库格式),返回 meal id */
        fun quickSaveFromRecognition(
            ctx: android.content.Context, photoPath: String?, itemsJson: String,
            note: String = "批量识别自动入账"
        ): Long {
            val items = parseItemsJson(itemsJson)
            return DbHelper.get(ctx).insertMeal(
                ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                photoPath = photoPath,
                mealType = guessMealType(),
                note = note,
                items = items
            )
        }
    }

    private val items = mutableListOf<DbHelper.MealItem>()
    private var mealType = "午餐"
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** 编辑已有账时的原值(改笔保留原时间戳;新记=当前时刻) */
    private var mealId = -1L
    private var loadedTs: String? = null
    private var loadedPhoto: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_meal_edit, container, false)
        val act = activity as MainActivity

        // 照片(优先 arguments;改笔模式载入库里存的)
        var photoPath = arguments?.getString("photo")
        mealId = arguments?.getLong("mealId", -1L) ?: -1L

        // 改笔/核对模式且没有 prefill:从库里整笔载入(明细/餐次/备注/照片/时间)
        if (mealId > 0 && arguments?.getString("prefill").isNullOrBlank()) {
            val m = DbHelper.get(activity).mealById(mealId)
            if (m != null) {
                items.addAll(m.items)
                mealType = m.mealType ?: guessMealType()
                loadedTs = m.ts
                loadedPhoto = m.photoPath
                if (photoPath == null && m.photoPath != null) photoPath = m.photoPath
                v.findViewById<EditText>(R.id.edit_note).setText(m.note ?: "")
            }
        }

        val photoView = v.findViewById<ImageView>(R.id.edit_photo)
        if (photoPath != null && File(photoPath).exists()) {
            photoView.setImageBitmap(BitmapFactory.decodeFile(photoPath))
        } else {
            photoView.visibility = View.GONE
        }

        // AI 识别结果直接预填(用户可改可删)
        arguments?.getString("prefill")?.let { prefillItems(it) }

        // 提示条 + 重试识别(识别失败/手记兜底时用)
        val noticeText = arguments?.getString("notice")
        val noticeView = v.findViewById<TextView>(R.id.edit_notice)
        val retryView = v.findViewById<TextView>(R.id.edit_retry)
        if (!noticeText.isNullOrBlank()) {
            noticeView.visibility = View.VISIBLE
            noticeView.text = noticeText
        } else if (items.isNotEmpty()) {
            noticeView.visibility = View.VISIBLE
            noticeView.text = "AI 识别到 ${items.size} 项(约),可直接记账,也可改"
        }
        if (photoPath != null) {
            retryView.visibility = View.VISIBLE
            retryView.setOnClickListener { act.retryRecognition(photoPath) }
        }

        // 餐次:改笔用载入值,新记按当前时间猜(与批量自动入账同一逻辑)
        if (mealId <= 0) mealType = guessMealType()
        bindMealType(v)

        // 明细
        v.findViewById<TextView>(R.id.edit_add_item).setOnClickListener { editItem(v, -1) }
        renderItems(v)

        // 返回 / 保存(改笔=更新原账,新记=insert)
        v.findViewById<TextView>(R.id.edit_back).setOnClickListener { act.backHome() }
        v.findViewById<TextView>(R.id.edit_save).setOnClickListener {
            val note = v.findViewById<EditText>(R.id.edit_note).text.toString().trim()
            if (items.isEmpty()) {
                act.toast("至少加一项菜品"); return@setOnClickListener
            }
            val db = DbHelper.get(activity)
            val kcal = items.sumOf { it.kcal }.toInt()
            if (mealId > 0) {
                db.updateMeal(
                    mealId, loadedTs ?: tsFmt.format(Date()),
                    photoPath ?: loadedPhoto, mealType, note.ifBlank { null }, items.toList()
                )
                act.toast("已更新这笔 ≈ $kcal kcal")
            } else {
                db.insertMeal(tsFmt.format(Date()), photoPath, mealType, note.ifBlank { null }, items.toList())
                act.toast("已记一笔 ≈ $kcal kcal")
            }
            act.backHome()
        }

        // 删这笔(只对已有账显示;自动入账后悔药/详情页改删共用)
        val delBtn = v.findViewById<TextView>(R.id.edit_delete)
        if (mealId > 0) {
            delBtn.visibility = View.VISIBLE
            delBtn.setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("删这笔?")
                    .setMessage("该餐及全部明细将从账本删除")
                    .setPositiveButton("删") { _, _ ->
                        DbHelper.get(activity).deleteMeal(mealId)
                        act.toast("已删这笔")
                        act.backHome()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        // 全文字可复制(2026-10-17):含「识别失败:HTTP …」错误横幅全文
        TextCopyUtil.apply(v)
        return v
    }

    // ---------- 餐次 ----------

    private fun bindMealType(v: View) {
        val map = mapOf(
            "早餐" to R.id.type_breakfast,
            "午餐" to R.id.type_lunch,
            "晚餐" to R.id.type_dinner,
            "加餐" to R.id.type_snack
        )
        for ((name, id) in map) {
            val tv = v.findViewById<TextView>(id)
            tv.setOnClickListener {
                mealType = name
                for ((n, i) in map) {
                    v.findViewById<TextView>(i).setBackgroundResource(
                        if (n == name) R.drawable.bg_tag_ink else R.drawable.bg_tag_out
                    )
                    v.findViewById<TextView>(i).setTextColor(
                        if (n == name) 0xFFFFFFFF.toInt() else 0xFF141414.toInt()
                    )
                }
            }
        }
        // 初始化选中态
        v.findViewById<TextView>(map[mealType]!!).performClick()
    }

    // ---------- 明细列表 ----------

    private fun renderItems(v: View) {
        val container = v.findViewById<LinearLayout>(R.id.edit_items)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        items.forEachIndexed { index, item ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_row_white)
                setPadding((24 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt())
            }
            val mid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(TextView(activity).apply {
                text = "${item.name}  ${item.grams.toInt()}g"
                textSize = 12.5f
                setTextColor(0xFF141414.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            mid.addView(TextView(activity).apply {
                text = "约 ${item.kcal.toInt()} kcal · 蛋白${item.protein.toInt()}g · 碳水${item.carbs.toInt()}g · 脂肪${item.fat.toInt()}g · ${item.source}"
                textSize = 10f
                setTextColor(0xFF666666.toInt())
            })
            // 候选菜名(2026-10-17 治认错菜):有候选才显示,点「改」可手动换成候选
            if (item.candidates.isNotEmpty()) {
                mid.addView(TextView(activity).apply {
                    text = "拿不准?候选:${item.candidates.joinToString(" / ")}"
                    textSize = 9.5f
                    setTextColor(0xFF2E7D32.toInt())
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }
            row.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(activity).apply {
                text = "改"
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_tag_green)
                setPadding((22 * density).toInt(), (8 * density).toInt(), (22 * density).toInt(), (8 * density).toInt())
                setOnClickListener { editItem(v, index) }
            })
            val del = TextView(activity).apply {
                text = "删"
                textSize = 11f
                setTextColor(0xFF141414.toInt())
                setBackgroundResource(R.drawable.bg_tag_out)
                setPadding((22 * density).toInt(), (8 * density).toInt(), (22 * density).toInt(), (8 * density).toInt())
                setOnClickListener {
                    items.removeAt(index)
                    renderItems(v)
                }
            }
            val delLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (12 * density).toInt() }
            row.addView(del, delLp)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * density).toInt() }
            container.addView(row, lp)
        }
        v.findViewById<TextView>(R.id.edit_total).text =
            "合计 ≈ ${items.sumOf { it.kcal }.toInt()} kcal"
        // 动态重建的明细行重扫一遍,保持菜名/来源可复制(2026-10-17)
        TextCopyUtil.apply(container)
    }

    // ---------- 单项编辑弹窗 ----------

    private fun editItem(v: View, index: Int) {
        val editing = if (index >= 0) items[index] else null
        val density = resources.displayMetrics.density

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((40 * density).toInt(), (20 * density).toInt(), (40 * density).toInt(), 0)
        }
        val nameInput = EditText(activity).apply {
            hint = "菜名"
            setText(editing?.name ?: "")
            setBackgroundResource(R.drawable.bg_edit_square)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val gramsInput = EditText(activity).apply {
            hint = "克重 g"
            setText(editing?.grams?.takeIf { it > 0 }?.toInt()?.toString() ?: "")
            setBackgroundResource(R.drawable.bg_edit_square)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val kcalInput = EditText(activity).apply {
            hint = "热量 kcal(约)"
            setText(editing?.kcal?.takeIf { it > 0 }?.toInt()?.toString() ?: "")
            setBackgroundResource(R.drawable.bg_edit_square)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val pickLib = TextView(activity).apply {
            text = "📚 从食物库选择"
            textSize = 12f
            setTextColor(0xFF141414.toInt())
            setBackgroundResource(R.drawable.bg_tag_out)
            gravity = Gravity.CENTER
            setPadding(0, (18 * density).toInt(), 0, (18 * density).toInt())
        }
        val srcHint = TextView(activity).apply {
            text = "来源:${editing?.source ?: DbHelper.SRC_MANUAL}(选库自动改「库」并算热量)"
            textSize = 10f
            setTextColor(0xFF666666.toInt())
        }

        form.addView(nameInput)
        form.addView(gramsInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() })
        form.addView(kcalInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() })
        form.addView(pickLib, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() })
        form.addView(srcHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })

        // 选库状态
        var pickedFoodId = editing?.foodId ?: -1L
        var pickedSource = editing?.source ?: DbHelper.SRC_MANUAL

        pickLib.setOnClickListener {
            showFoodPicker { food ->
                nameInput.setText(food.name)
                pickedFoodId = food.id
                pickedSource = DbHelper.SRC_LIB
                val g = gramsInput.text.toString().toDoubleOrNull() ?: food.portionG
                if (gramsInput.text.isBlank()) gramsInput.setText(food.portionG.toInt().toString())
                kcalInput.setText(((food.kcalPer100g * g / 100).roundToInt()).toString())
                srcHint.text = "来源:${DbHelper.SRC_LIB}(命中「${food.name}」)"
            }
        }
        // 克重改动时若来源是库,联动重算热量(可再手动改)
        gramsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (pickedSource != DbHelper.SRC_LIB || pickedFoodId < 0) return
                val food = FoodSeed.byId(pickedFoodId) ?: return
                val g = s?.toString()?.toDoubleOrNull() ?: return
                kcalInput.setText(((food.kcalPer100g * g / 100).roundToInt()).toString())
            }
        })

        AlertDialog.Builder(activity)
            .setTitle(if (editing == null) "加一项" else "改一项")
            .setView(form)
            .setPositiveButton("确定") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    (activity as MainActivity).toast("菜名不能为空")
                    return@setPositiveButton
                }
                val grams = gramsInput.text.toString().toDoubleOrNull() ?: 0.0
                val kcal = kcalInput.text.toString().toDoubleOrNull()
                    ?: (FoodSeed.findByName(name)?.let { it.kcalPer100g * grams / 100 } ?: 0.0)
                val item = DbHelper.MealItem(name, grams, kcal, pickedSource, pickedFoodId)
                if (editing == null) items.add(item) else items[index] = item
                renderItems(v)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 食物库选择弹窗:搜索 + 列表 */
    private fun showFoodPicker(onPick: (FoodSeed.Food) -> Unit) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((30 * density).toInt(), (10 * density).toInt(), (30 * density).toInt(), 0)
        }
        val search = EditText(activity).apply {
            hint = "搜菜名"
            setBackgroundResource(R.drawable.bg_edit_square)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val list = ListView(activity)
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (600 * density).toInt()
        ).apply { topMargin = (16 * density).toInt() })

        fun refresh(q: String) {
            val foods = FoodSeed.search(q)
            list.adapter = ArrayAdapter(
                activity, android.R.layout.simple_list_item_1,
                foods.map { "${it.name} · ${it.kcalPer100g.toInt()} kcal/100g · ${it.portionDesc}≈${it.portionG.toInt()}g" }
            )
            list.setOnItemClickListener { _, _, pos, _ -> onPick(foods[pos]) }
        }
        refresh("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refresh(s?.toString() ?: "")
        })

        AlertDialog.Builder(activity)
            .setTitle("食物库(自建估值)")
            .setView(box)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()

    /** 识别器结果预填(JSON 数组,解析复用 companion.parseItemsJson) */
    private fun prefillItems(json: String) {
        items.addAll(parseItemsJson(json))
    }
}
