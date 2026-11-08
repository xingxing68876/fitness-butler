package dev.xd.fitnessbutler.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView

/**
 * 全文字可复制(2026-10-17 设计决策):APP 内所有展示文字长按可选可复制,
 * 错误码/对话内容尤其要能贴出去诊断。
 * 递归遍历布局树,把纯展示 TextView 置为 selectable;
 * 跳过 EditText(本就可编辑)、Button/CompoundButton(交互控件)、
 * 以及已绑 click 的 TextView(isClickable=true,防点击/长按选择冲突)。
 * 注意:TrendChartView/DayTimelineView 为自绘 View 不受影响;
 * 动态后加的气泡/明细行需在生成处自行调用。
 *
 * 2026-10-18 修复"日历点日期文字无反应,点格子空白才有反应":
 * selectable 的 TextView 会自己吃掉 ACTION_DOWN,父容器(日历格 cell)的
 * OnClickListener 收不到事件。规则:祖先容器 clickable 时,整棵子树不设
 * selectable,把点击完整让给父容器(格子里的日期数字本来也不需要复制)。
 */
object TextCopyUtil {

    fun apply(root: View?) = walk(root, blocked = false)

    private fun walk(v: View?, blocked: Boolean) {
        when (v) {
            null -> return
            is TextView -> if (
                !blocked &&
                v !is EditText &&
                v !is Button &&
                v !is CompoundButton &&
                !v.isClickable
            ) {
                v.setTextIsSelectable(true)
            }
            is ViewGroup -> {
                val childBlocked = blocked || v.isClickable
                for (i in 0 until v.childCount) walk(v.getChildAt(i), childBlocked)
            }
        }
    }
}
