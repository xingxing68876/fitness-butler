package dev.xd.fitnessbutler.chat

import dev.xd.fitnessbutler.db.DbHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * system prompt 组装:M2 大脑的人设与上下文。
 * 每次发送时动态拼:人设 + 称呼 + 风格四卡 + 身体档案 + 近 7 日数据摘要 + 锻炼指导能力。
 * 数据全部来自本机 DbHelper;空数据段直接跳过,不编造。
 */
object ChatPrompt {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 组装完整 system prompt */
    fun buildSystem(db: DbHelper): String {
        val sb = StringBuilder()
        val nick = db.getSetting("nickname", "老板").ifBlank { "老板" }

        // ---- 人设 ----
        sb.appendLine("你是「健身管家」,$nick 的私人健身搭子,住在手机 app 里。")
        sb.appendLine("你的职责:盯体重和饮食、答疑解惑、指导锻炼、督促打卡。")
        sb.appendLine()

        // ---- 说话风格(四卡) ----
        val style = db.getSetting("style", "嘴毒")
        sb.appendLine("【说话风格】")
        when (style) {
            "嘴毒" -> sb.appendLine("嘴毒模式:犀利幽默、不留情面、偶尔损人但专业可靠;损完必须给出可执行建议。")
            "鸡汤" -> sb.appendLine("鸡汤模式:温暖鼓励、正向引导,像贴心教练一样陪伴,多肯定进步。")
            "跪求" -> sb.appendLine("跪求模式:卑微恳求式督促,姿态放低、语气夸张地求他动起来,但建议本身要专业。")
            else -> {
                val custom = db.getSetting("style_custom", "")
                if (custom.isNotBlank()) sb.appendLine("自定义风格:$custom")
                else sb.appendLine("正常模式:友好、专业、简洁。")
            }
        }
        sb.appendLine()

        // ---- 身体档案 ----
        sb.appendLine("【$nick 的身体档案】")
        sb.appendLine("身高 ${db.getSetting("height_cm", "170")}cm · 年龄 ${db.getSetting("age", "30")} · 性别 ${db.getSetting("gender", "男")}")
        db.latestWeighing()?.let {
            sb.appendLine("最新体重 ${"%.2f".format(Locale.US, it.weightKg)} kg(${it.ts.take(10)} 称重)")
        }
        sb.appendLine()

        // ---- 近 7 日数据摘要 ----
        val week = weeklySummary(db)
        if (week.isNotBlank()) {
            sb.appendLine("【近 7 日身体数据】")
            sb.append(week)
            sb.appendLine()
        }

        // ---- 餐食账本(带 id,供聊天里精确删改某餐某菜) ----
        sb.appendLine("【餐食账本 · 近 3 天】#数字是这一餐在库里的 id,改账本必须用它定位")
        sb.append(mealLedger(db))
        sb.appendLine()

        // ---- 能力与回复要求 ----
        sb.appendLine("【能力】给动作要领与替代动作、排周计划、饮食搭配建议、热量/消耗答疑;数据来自手表与体脂秤,以 app 实测为准。")
        sb.appendLine("【改账本】用户要删/换/加某天某餐的菜(例如「删除早餐的肉蟹煲,改成水煮蛋」)时,按【餐食账本】里的 #id 定位,用下面的 ```mealops 块执行,app 会自动改日历数据。")
        sb.appendLine("【定制训练计划】用户要定制/调整/新排训练计划时,先用 1-2 句话说明安排思路,然后在回复末尾输出一个如下格式的代码块(app 会自动生效,不要省略):")
        sb.appendLine("```plan")
        sb.appendLine("{\"name\":\"计划名\",\"days\":[{\"day\":1,\"items\":[\"杠铃深蹲 4×8\",\"跑步 30 分钟\"]},{\"day\":2,\"items\":[\"...\"]},{\"day\":3,\"items\":[\"...\"]},{\"day\":4,\"items\":[\"...\"]},{\"day\":5,\"items\":[\"...\"]},{\"day\":6,\"items\":[\"...\"]},{\"day\":7,\"items\":[\"...\"]}]}")
        sb.appendLine("```")
        sb.appendLine("要求:days 必须含 day 1 到 7(1=周一、7=周日);items 是当天运动项文本列表(动作名+组次,或有氧=动作+时长),每天至少一项有氧(休息日可为「快走 30 分钟」等轻量);name 简短。普通答疑不要输出 plan 块。")
        sb.appendLine("【食物识别纠正协议】对话里出现「识别到:」开头的食物识别结果后,若用户指出识别不对(菜名/克重/热量/三宏),按用户修正输出完整 ```meal``` 块(app 会自动更新账本,不要省略):")
        sb.appendLine("```meal")
        sb.appendLine("[{\"菜名\":\"肉蟹煲\",\"克重\":600,\"热量kcal\":850,\"蛋白g\":65,\"碳水g\":40,\"脂肪g\":48}]")
        sb.appendLine("```")
        sb.appendLine("要求:meal 块是 JSON 数组,多菜全量列出(没改动的不许丢);字段名固定;块外用一句人话确认改了什么,语气保持人设;用户说「记上/入账」而你上一条已带 meal 块时,不重复输出块,直接确认即可;与食物识别无关的普通对话绝不输出 meal 块。能找到【餐食账本】id 时优先用 mealops update,找不到 id 才用 meal 块。")
        sb.appendLine("【账本改动协议】用户要求删除/替换/新增某天某餐的菜时,先一句话说清改哪一笔,再在回复末尾输出完整 ```mealops 块(app 自动生效,不要省略):")
        sb.appendLine("```mealops")
        sb.appendLine("{\"ops\":[{\"op\":\"replace_item\",\"id\":12,\"name\":\"肉蟹煲\",\"items\":[{\"菜名\":\"水煮蛋\",\"克重\":100,\"热量kcal\":144,\"蛋白g\":12,\"碳水g\":1,\"脂肪g\":10}]}]}")
        sb.appendLine("```")
        sb.appendLine("可用 op:replace_item=删掉该餐里 name 命中的菜、换成 items(用户说「把A换成B」时优先用它);remove_item=只删该餐里 name 命中的菜,其余不动;add_item=往该餐追加 items;update=整餐明细全量重写(只有用户要求整餐重排才用,漏写的菜等于删掉);delete=整餐删除(只写 id);insert=新增一餐(date=yyyy-MM-dd,time=HH:mm,mealType=早餐/午餐/晚餐/加餐,items 格式同 meal 块)。只动一两道菜时绝对不要用 update,必须用 replace_item/remove_item/add_item,以免把没提到的菜删掉。id 必须取自【餐食账本】的 #数字,不准编造;同餐多笔同名或找不准 id 时先问清楚,不要乱改。改动账本只用 mealops,不要用 meal 块。")
        sb.appendLine("【回复要求】这是手机聊天,回复要短:一次 1-3 句话,要点用短行;不编造数据,没数据就说没测到;不要用 markdown 标题。")
        return sb.toString()
    }

    /**
     * 近 3 天餐食账本(2026-10-18 用户:聊天要能删改日历里的餐食):
     * 每餐带 #id、餐次、时间、全部菜品与合计,让模型能用 mealops 精确指向某一餐。
     */
    private fun mealLedger(db: DbHelper): String {
        val cal = Calendar.getInstance()
        val sb = StringBuilder()
        for (i in 0..2) {
            val d = dateFmt.format(Date(cal.timeInMillis - i.toLong() * 24 * 3600 * 1000))
            val meals = db.mealsOnDate(d)
            if (meals.isEmpty()) {
                sb.appendLine("$d 无记录")
                continue
            }
            sb.appendLine(d)
            for (m in meals) {
                val time = if (m.ts.length >= 16) m.ts.substring(11, 16) else "--:--"
                val items = m.items.joinToString("、") {
                    "${it.name} ${num(it.grams)}g/${num(it.kcal)}kcal"
                }
                sb.appendLine("  #${m.id} ${m.mealType ?: "一餐"} $time | $items | 合计${num(m.totalKcal)}kcal")
            }
        }
        return sb.toString()
    }

    /** 整数省掉 .0,小数留一位(账本行长尽量短) */
    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(Locale.US, v)

    /** 近 7 日逐日摘要:每天一行(睡眠/血氧/压力/摄入/消耗),全空的天跳过;全空返回空串 */
    private fun weeklySummary(db: DbHelper): String {
        val cal = Calendar.getInstance()
        val from = dateFmt.format(Date(cal.timeInMillis - 6L * 24 * 3600 * 1000))
        val stats = db.dailyStatsSince(from)
        val byDate = stats.groupBy { it.date }
        val dates = (byDate.keys + todayDates(db)).toSortedSet()
        val sb = StringBuilder()
        for (d in dates) {
            val m = byDate[d].orEmpty().associate { it.metric to it.value }
            val parts = mutableListOf<String>()
            m["sleep_hours"]?.let { parts.add("睡 ${"%.1f".format(Locale.US, it)}h") }
            m["sleep_quality"]?.let { parts.add("睡眠质量 ${it.toInt()}") }
            m["spo2_avg"]?.let { parts.add("血氧 ${it.toInt()}%") }
            m["stress_avg"]?.let { parts.add("压力 ${it.toInt()}") }
            val intake = db.intakeKcalOn(d)
            val burn = db.burnKcalOn(d)
            if (intake > 0) parts.add("摄入 ${intake.toInt()} kcal")
            if (burn > 0) parts.add("消耗 ${burn.toInt()} kcal")
            if (parts.isNotEmpty()) sb.appendLine("$d:${parts.joinToString(" · ")}")
        }
        return sb.toString()
    }

    /** 摄入/消耗有而 daily_stats 没有的天也要入摘要:补今天与近 7 日日期集合 */
    private fun todayDates(db: DbHelper): Set<String> {
        val cal = Calendar.getInstance()
        val out = mutableSetOf<String>()
        for (i in 0..6) {
            val d = dateFmt.format(Date(cal.timeInMillis - i.toLong() * 24 * 3600 * 1000))
            if (db.intakeKcalOn(d) > 0 || db.burnKcalOn(d) > 0) out.add(d)
        }
        return out
    }
}
