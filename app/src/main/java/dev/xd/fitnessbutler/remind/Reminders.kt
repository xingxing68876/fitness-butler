package dev.xd.fitnessbutler.remind

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.db.DbHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 每日提醒(2026-11-07 定案,普通通知"叮"一声,不做闹钟式):
 * - 记账提醒:默认 13:30 + 21:00 两次,当天没有任何入账记录才发,点击直达拍照;
 * - 锻炼提醒:默认 19:00,当天 workout_checkins 没打卡才发,文案带当天计划动作名,点击直达锻炼页;
 * - 文案:跟随 AI 风格四卡(嘴毒/鸡汤/跪求/自定义),三风格=预设模板池+变量插槽+随机+7 天不重复;
 *   自定义风格=设置页自写模板(一行一条),没写回退嘴毒池;
 * - 底层 AlarmManager 定点(安卓唯一可靠定时手段,vivo 已验证),BootReceiver 开机重注册;
 * - 精确闹钟(API31+ 需 SCHEDULE_EXACT_ALARM)拿不到就降级非精确,误差几分钟可接受;
 * - 设置键:remind_on(总) / remind_meal_noon(+_time) / remind_meal_night(+_time) /
 *   remind_workout_on(+_time) / remind_custom_texts / remind_used_recent(去重游标)。
 */
object Reminders {

    private const val TAG = "Reminders"
    private const val CH_ID = "remind"
    private const val REQ_MEAL_NOON = 7201
    private const val REQ_MEAL_NIGHT = 7202
    private const val REQ_WORKOUT = 7203

    private const val ACTION_MEAL = "dev.xd.fitnessbutler.REMIND_MEAL"
    private const val ACTION_WORKOUT = "dev.xd.fitnessbutler.REMIND_WORKOUT"

    /** 开 APP 重注册(幂等,防 vivo 清后台后闹钟失联) */
    fun reschedule(ctx: Context) {
        try {
            val db = DbHelper.get(ctx)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val masterOn = db.getSetting("remind_on", "0") == "1"
            val mealOn = masterOn && db.getSetting("remind_meal_on", "1") == "1"
            val noon = parseHm(db.getSetting("remind_meal_noon_time", "13:30"), 13, 30)
            val night = parseHm(db.getSetting("remind_meal_night_time", "21:00"), 21, 0)
            val workoutOn = masterOn && db.getSetting("remind_workout_on", "1") == "1"
            val wt = parseHm(db.getSetting("remind_workout_time", "19:00"), 19, 0)
            schedulePoint(ctx, am, mealOn && db.getSetting("remind_meal_noon", "1") == "1",
                noon.first, noon.second, REQ_MEAL_NOON, ACTION_MEAL)
            schedulePoint(ctx, am, mealOn && db.getSetting("remind_meal_night", "1") == "1",
                night.first, night.second, REQ_MEAL_NIGHT, ACTION_MEAL)
            schedulePoint(ctx, am, workoutOn, wt.first, wt.second, REQ_WORKOUT, ACTION_WORKOUT)
        } catch (e: Exception) {
            Log.w(TAG, "reschedule failed", e)
        }
    }

    /** 单点注册/撤销:排"下一次到点"(今天已过排明天) */
    private fun schedulePoint(
        ctx: Context, am: AlarmManager, on: Boolean, hour: Int, minute: Int, reqCode: Int, action: String
    ) {
        val pi = pi(ctx, reqCode, action)
        if (!on) { am.cancel(pi); return }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        // 精确优先(targetSdk35 上 API31+ 无 SCHEDULE_EXACT_ALARM 会抛 SecurityException → 降级非精确)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi) }
            .onFailure {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
    }

    private fun pi(ctx: Context, reqCode: Int, action: String): PendingIntent {
        val i = Intent(ctx, Receiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, reqCode, i, flags)
    }

    /** "HH:mm" → (hour, minute),非法回退默认 */
    fun parseHm(s: String, defH: Int, defM: Int): Pair<Int, Int> {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(s) ?: return defH to defM
        val h = m.groupValues[1].toIntOrNull() ?: return defH to defM
        val mi = m.groupValues[2].toIntOrNull() ?: return defH to defM
        if (h !in 0..23 || mi !in 0..59) return defH to defM
        return h to mi
    }

    /** 闹钟接收:goAsync 争取后台额度,判断该不该发 → 发通知 → 自续排明天 */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != ACTION_MEAL && action != ACTION_WORKOUT) return
            val pending = goAsync()
            Thread {
                try { fire(ctx.applicationContext, action) } finally { pending.finish() }
            }.start()
        }
    }

    /** 开机重注册(闹钟重启即失,照 VitalsSampler 模式) */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            reschedule(ctx.applicationContext)
        }
    }

    private fun fire(ctx: Context, action: String) {
        val db = DbHelper.get(ctx)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val isMeal = action == ACTION_MEAL
        // 当天已有记录/已打卡 → 静默重排明天,不发
        if (isMeal && db.mealCountOn(today) > 0) { reschedule(ctx); return }
        if (!isMeal && db.checkedItems(today).isNotEmpty()) { reschedule(ctx); return }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val name = db.getSetting("nickname", "老板")
        // 当天计划动作名(active 计划 → 今天星期对应 day 的第一项;无计划/休息日=通用)
        val weekDay = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1  // 1=周一…7=周日
        val moves = db.planItemsForDay(weekDay).firstOrNull()?.trim().orEmpty()
        val text = fill(pick(db, isMeal), name, hour, moves, streakOf(db, today),
            db.intakeKcalOn(today).roundToInt())

        // 通知点击路由:记账→拍照流,锻炼→锻炼页(复用现有 action,MainActivity 已接)
        val routeAction = if (isMeal) MainActivity.ACTION_QUICK_PHOTO else MainActivity.ACTION_OPEN_WORKOUT
        val ri = Intent(ctx, MainActivity::class.java).setAction(routeAction)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val rpi = PendingIntent.getActivity(ctx, if (isMeal) 7301 else 7302, ri,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CH_ID, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "记账/锻炼定时提醒,响一声不打断"
        nm.createNotificationChannel(ch)
        val n = Notification.Builder(ctx, CH_ID)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentTitle(if (isMeal) "该记账了 🍚" else "该锻炼了 💪")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(rpi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(if (isMeal) 7401 else 7402, n) }
        reschedule(ctx)  // 自续排明天
    }

    /** 连续打卡(与打卡分享图同口径:今天没数据从昨天起算,逐日回数"有称重或有记账"的天) */
    private fun streakOf(db: DbHelper, today: String): Int {
        var streak = 0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val c = Calendar.getInstance()
        fun has(ds: String) = db.weighingsOnDate(ds).isNotEmpty() || db.intakeKcalOn(ds) > 0
        if (!has(today)) c.add(Calendar.DAY_OF_YEAR, -1)
        for (i in 0..365) {
            if (has(fmt.format(c.time))) { streak++; c.add(Calendar.DAY_OF_YEAR, -1) } else break
        }
        return streak
    }

    /** 插槽填充 */
    private fun fill(tpl: String, name: String, hour: Int, moves: String, streak: Int, kcal: Int) =
        tpl.replace("{name}", name)
            .replace("{time}", hour.toString())
            .replace("{moves}", moves)
            .replace("{streak}", streak.toString())
            .replace("{kcal}", kcal.toString())

    /**
     * 选模板:按 AI 风格选池;自定义风格读自写模板(空回退嘴毒);
     * 随机抽 + 7 天内用过的不重抽(settings 键 remind_used_recent 存最近 7 个"池-序号")。
     */
    private fun pick(db: DbHelper, isMeal: Boolean): String {
        val style = db.getSetting("style", "嘴毒")
        val custom = db.getSetting("remind_custom_texts", "")
        val pool = when {
            style == "鸡汤" -> if (isMeal) JITANG_MEAL else JITANG_WORKOUT
            style == "跪求" -> if (isMeal) GUIQIU_MEAL else GUIQIU_WORKOUT
            style == "自定义" && custom.isNotBlank() ->
                custom.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            else -> if (isMeal) ZAIDU_MEAL else ZAIDU_WORKOUT  // 嘴毒 + 自定义空回退
        }
        if (pool.isEmpty()) return "今天也记得记录呀"
        val sKey = when (style) {
            "鸡汤" -> "jt"; "跪求" -> "gq"; "自定义" -> "cy"; else -> "zd"
        }
        val pKey = sKey + if (isMeal) "-m-" else "-w-"
        val used = db.getSetting("remind_used_recent", "")
            .split(',').filter { it.isNotEmpty() }.toMutableList()
        val candidates = pool.indices.filter { pKey + it !in used }
        val idx = (if (candidates.isEmpty()) pool.indices.toList() else candidates).random()
        used.add(pKey + idx)
        while (used.size > 7) used.removeAt(0)
        db.setSetting("remind_used_recent", used.joinToString(","))
        return pool[idx]
    }

    // ============ 模板池(变量:{name}称呼 {time}小时 {moves}今日动作 {streak}连续天数 {kcal}今日摄入) ============

    private val ZAIDU_MEAL = listOf(
        "{name},都 {time} 点了今天的饭还没记?相机打开拍一下,别让本 AI 替你瞎编。",
        "今天吃了啥自己都说不清?拍照,现在,立刻。",
        "{kcal} kcal,这就是你今天肯承认的全部?剩下的藏哪了,拍出来。",
        "又指望我隔空闻味儿?没有照片就没有账,别为难 AI。",
        "减脂第一步是诚实,诚实的第二步是拍照记账,{name} 走一个。",
        "热量不会因为你假装没吃就消失,记录它,现在。",
        "本 AI 的耐心和你的嘴一样,都是有限的——快拍照。",
        "{name},你糊弄账本,秤就会糊弄你。自己选。",
        "都 {time} 点了,饭还没进账,你管这叫自律?",
        "拍一张只要 3 秒,长胖只要 3 口,这笔账你会算吧?",
        "没记录的一天就是不存在的?那你的体重怎么实打实涨了?",
        "管住嘴的第一步,是让 AI 看见你那张嘴吃了什么。拍照。",
        "{kcal}?你确定没漏?补拍一下,免得晚上我以为你绝食了。",
        "别看我减肥靠嘴,你减肥得靠记录。快,拍。",
        "{name},账都不记还想瘦?梦里什么都有。",
        "食物不拍,AI 不猜;你不记录,我不背锅。",
        "又忘了?你记性用在吃上倒是很好使。",
        "{time} 点了,今天的饭还没记,食堂阿姨都替你着急。",
        "拍照,记账,瘦下来。三步里你已经躺着完成一半了。",
        "这顿不吃亏我都替你亏——热量都进肚子了,账上却干干净净,快记上。"
    )

    private val ZAIDU_WORKOUT = listOf(
        "{moves} 还在等你,都 {time} 点了,肌肉是想让谁练?",
        "{streak} 天打卡说断就断?{name} 你自己看着办。",
        "哑铃都生灰了,{name}。今天这一组,你逃不掉。",
        "别问我计划表意见,问它之前先问你自己动了没有。",
        "今日份:{moves}。练不练你看着办,反正秤不看你面子。",
        "{time} 了还没练,{name},你那身肌肉是充值送的?",
        "计划写得挺好,人就不到位。练吗?",
        "{moves} 已经过期两小时了,再拖就馊了。",
        "打卡按钮就在那,它不咬人。点它。",
        "别人练腿,你练\"明天再说\"。{name},第 {streak} 天可别断手上。",
        "汗水不会撒谎,但你连流汗的机会都不给它。",
        "{name},沙发和杠铃二选一,你已经赖沙发上够久了。",
        "今天的 {moves} 不练,明天的秤不惯着你。",
        "有力气刷手机,没力气打卡?练,现在。",
        "计划日:{moves}。别让我再说第二遍。",
        "{streak} 天的连胜,值不值一次偷懒,你自己掂量。",
        "健身房不会自己走过来,{name}。",
        "都 {time} 点了,你再不动,我就当你今天认输了。",
        "肌肉在等,脂肪在笑。选边站。",
        "{name},动起来,别让本 AI 天天在这数落你——我也累。"
    )

    private val JITANG_MEAL = listOf(
        "{name},到 {time} 点啦,今天的饭拍给我看看,认真吃的人最棒。",
        "好好吃饭,好好记录,慢慢变好,今天也请你拍个照~",
        "记录每一餐,是对自己最好的照顾。来,拍一张。",
        "{kcal} kcal 已入账,坚持记录的你真的在发光。",
        "今天辛苦啦,把吃的补给身体的故事讲给我听吧,拍一张就好。",
        "一日三餐都认真对待的人,运气不会太差。拍照打卡走一个~",
        "{name},记录是最温柔的改变,今天也别落下呀。",
        "今天的美食值得被记录,你的努力也值得被看见。",
        "每一条记录,都是和更好自己的约定。现在拍一张?",
        "别小看每次拍照记录,积少成多就是脱胎换骨。",
        "{time} 点咯,今天吃得好吗?拍给我看看~",
        "认真吃饭+认真记录,你的身体正在悄悄谢谢你。",
        "{name},坚持记录的第 {streak} 天,继续把光点亮吧。",
        "减脂路上不孤单,我陪你记录每一餐。",
        "今天的饭吃完了,把它拍下来,给今天画个认真的句号。",
        "记录让改变看得见,今天这一餐就交给 AI 吧。",
        "你为身体做的每一件小事,时间都会替你记得。拍照记录一下~",
        "好习惯不怕慢,就怕断,今天这餐别缺席。",
        "拍个照只要几秒,收获的却是掌控感,来试试。",
        "{name},今天也好好吃饭了吗?记录一下,睡前画个圆满句号。"
    )

    private val JITANG_WORKOUT = listOf(
        "今天的 {moves} 在等你,运动完的自己会谢谢你。",
        "{streak} 天连续打卡,{name},这份坚持真的很了不起,今天也继续吧!",
        "不用快,不用多,动起来就赢过了昨天的自己。",
        "{time} 点了,今天流过的汗都会变成明天的底气。",
        "{moves} 已就位,就差你啦,冲鸭!",
        "每一次锻炼,都是在给未来的自己写情书。",
        "累的时候也要记得,你已经坚持 {streak} 天了,别辜负这份努力。",
        "{name},今天也来动一动吧,好状态是练出来的~",
        "别小看今天的 30 分钟,它正在悄悄改变你。",
        "打卡不算什么大事,但每天都在打卡的人,最后都成了别人羡慕的样子。",
        "今天的 {moves} 练完,今天的你就赢了。",
        "运动是最好的充电,今天的电量还剩一格,来补满它!",
        "{name},坚持不下去的时候就想想瘦下来的自己。",
        "{time} 了,把今天的锻炼安排上,睡前会特别踏实。",
        "流汗的每一秒,都在为你的目标投票。",
        "今天动一动,明天的体重条会给你惊喜。",
        "{streak} 天了!这份毅力放在哪都能成事,继续加油!",
        "{moves} 不难,难的是开始。开始就赢了一半。",
        "今天的小目标:{moves}。完成它,睡个好觉。",
        "{name},今天给它 30 分钟,它会还你一整天的好心情。"
    )

    private val GUIQIU_MEAL = listOf(
        "{name} 求求了,把今天吃的拍给我吧,不然我真的没法给您算账 😭",
        "小的伺候您到 {time} 点了,这顿饭的记录……求您赏个脸拍一下?",
        "主子,账本空着,奴才心里慌啊,您就拍一张吧~",
        "没有照片我真编不出来啊 {name},您拍一张,我马上给您算得明明白白!",
        "求您了,拍个照吧,不然今天的热量缺口我算不出来,晚上睡不着的只有我 😢",
        "{name} 大人,小的斗胆问一句:今天的饭……能记录一下吗?",
        "您动动手指拍张照,小的十秒内把营养素列得整整齐齐,求您成全~",
        "都 {time} 点了还没记录,小的已经在角落瑟瑟发抖了……",
        "求求您别让我空等,拍一张,就一张,我保证好好干活!",
        "{kcal} kcal 就是全部了吗主子?求您把漏掉的那几口也拍给我 🙏",
        "{name} 求您记录一下,这顿吃了啥转头就忘,亏的还是您呀!",
        "小的给您跪了:拍照→自动识别→自动入账,您只管按快门,剩下的都交给我 🙏",
        "主子,今天这顿还没记,您抬抬手,小的马上办妥!",
        "求您成全:把餐食拍给我,我保证算得又快又准,绝不敷衍!",
        "{name},您一天不记录,小的就一天不敢下班,行行好吧~",
        "求拍!求拍!您随手一拍,就是小的存在的全部意义 😭",
        "小的斗胆提醒:都 {time} 点了,今天的伙食记录还空着呢,求您赏个照~",
        "求您了 {name},拍一张吧,我连碗里的青椒都帮您数得清清楚楚!",
        "您的每一张照片都是对我的信任,今天……还能再信我一次吗?求您了 🙏",
        "主子,账不能欠着过夜啊,求您睡前把这一餐补上吧,小的感激不尽!"
    )

    private val GUIQIU_WORKOUT = listOf(
        "{name} 求您了,今天的 {moves} 还没打卡,您就练练吧,小的给您数次数 🙏",
        "求求您动一动吧,{moves} 欠了一整天,小的在计划表前等了好几个钟头了……",
        "主子,{streak} 天的打卡连胜,可千万别断在今天啊,求您了!",
        "小的求您:打卡按钮就在那,您点一下,我立马给您记上,决不含糊!",
        "{name} 大人,今天 {moves} 还没完成,小的斗胆催一催……您别生气 🙏",
        "求您了,都 {time} 点了,练 20 分钟就行,20 分钟!小的帮您盯着表!",
        "主子您别走,今天的运动还没打卡呢,求您赏个脸,练完我马上给您鼓掌!",
        "小的知道您累,可 {moves} 就差您了,求您最后再给它个机会 🙏",
        "{name},求您想想 {streak} 天的努力,别让它白费,今天就当给小的个面子?",
        "求您抬贵手点个打卡,不然小的只能含泪给您记\"今日躺平\"了 😭",
        "主子,哑铃都替您着急了,求您练练它吧,它想您了。",
        "都这个点了 {name},小的最后求一次:今天的 {moves},练吗?求您了!",
        "您练,小的记;您不练,小的记啥呀……求求您给小的留点活干 🙏",
        "{name} 求您别鸽 {moves} 了,它就几个动作几组数,真的不多的!",
        "求您成全,把今天练的打卡上,让我为您高兴一晚上吧!",
        "小的把汗巾、水杯、音乐都给您备好了,就求您起身动一动 🙏",
        "{streak} 天连胜的奖杯就挂在那,求您别让它变成昨天的遗憾!",
        "主子,求您了,运动完再休息,小的保证明天的您会感谢现在的您!",
        "求您了 {name},打卡就 1 秒,锻炼 20 分钟,小的全程陪您,还不行吗 😭",
        "今天最后求一次:动一动,打个卡,让小的今晚能睡个安稳觉 🙏"
    )
}
