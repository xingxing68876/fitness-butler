package dev.xd.fitnessbutler.ui

import android.app.Fragment
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.data.FoodSeed
import kotlin.math.roundToInt

/**
 * 食物图鉴:分类胶囊 + 搜索 + 食物卡(热量 + 消耗换算「走约 N 分钟才能消」)。
 * 数据 = FoodSeed 自建估值(约),不承诺精准。
 */
@Suppress("DEPRECATION")
class FoodGuideFragment : Fragment() {

    private var category = "全部"
    private var keyword = ""
    private var shown: List<FoodSeed.Food> = emptyList()
    private lateinit var adapter: FoodAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_food_guide, container, false)

        // 分类胶囊
        val cats = v.findViewById<LinearLayout>(R.id.guide_cats)
        val density = resources.displayMetrics.density
        for (cat in FoodSeed.CATEGORIES) {
            val pill = TextView(activity).apply {
                text = cat
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((28 * density).toInt(), (14 * density).toInt(), (28 * density).toInt(), (14 * density).toInt())
                setOnClickListener {
                    category = cat
                    drawCats(cats)
                    refresh()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (14 * density).toInt() }
            cats.addView(pill, lp)
        }
        drawCats(cats)

        // 搜索
        v.findViewById<EditText>(R.id.guide_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString() ?: ""
                refresh()
            }
        })

        // 列表
        adapter = FoodAdapter()
        v.findViewById<ListView>(R.id.guide_list).adapter = adapter
        refresh()
        TextCopyUtil.apply(v)   // 全文字可复制(2026-10-17)
        return v
    }

    private fun drawCats(cats: LinearLayout) {
        for (i in 0 until cats.childCount) {
            val pill = cats.getChildAt(i) as TextView
            val on = pill.text.toString() == category
            pill.setBackgroundResource(if (on) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            pill.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
        }
    }

    private fun refresh() {
        shown = FoodSeed.search(keyword).filter {
            category == "全部" || it.category == category
        }
        adapter.notifyDataSetChanged()
    }

    private inner class FoodAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): FoodSeed.Food = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = shown[position]
            val row = convertView ?: layoutInflater.inflate(R.layout.item_food, parent, false)
            row.findViewById<TextView>(R.id.food_name).text = f.name
            row.findViewById<TextView>(R.id.food_kcal).text =
                "约 ${f.kcalPer100g.toInt()} kcal / 100g"
            val portionKcal = (f.kcalPer100g * f.portionG / 100).roundToInt()
            row.findViewById<TextView>(R.id.food_sub).text =
                "${f.portionDesc}≈${f.portionG.toInt()}g · 约 $portionKcal kcal · 走约 ${FoodSeed.walkMinutes(portionKcal.toDouble())} 分钟才能消"
            return row
        }
    }
}
