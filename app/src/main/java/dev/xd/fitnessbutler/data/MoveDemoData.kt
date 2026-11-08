package dev.xd.fitnessbutler.data

/**
 * 动作演示数据 v3(2026-10-18):21 动作全覆盖。
 * 来源:opengym3d(MIT)GLB + three.js 程序化器材离屏渲染,front/side 双视角各 16 帧(8fps)。
 * 资产:assets/movedemo/<dir>/<front|side>_<00-15>.jpg + voice/<dir>.mp3(edge-tts 中文要领)。
 * 动作标准性红线:pack 23 动作逐帧质检,deadlift(mocap 弓背)/bent_over_row(实为直立划船)剔除;
 * 文案只写画面真做到的要领(画面与配音严格对应,2026-10-18 实测抓过穿帮)。
 * 匹配管道:精确→别名→唯一子串;匹配不到 demo=null(兜底面板),绝不猜配。
 */
object MoveDemoData {

    data class FrameDemo(
        val name: String,       // 规范动作名(与 WorkoutSeed/计划文本对齐)
        val dir: String,        // assets/movedemo/<dir>/
        val frameCount: Int = 16,
        val durationSec: Float = 2f, // 16帧@8fps 一循环
        val tip: String
    )

    // ---------- 21 个已质检动作 + 程序化渲染动作(2026-10-19) ----------
    private val DEMOS: List<FrameDemo> = listOf(
        FrameDemo(
            "杠铃卧推", "bench_press",
            tip = "杠铃卧推。仰卧在凳上,双脚踩实地面。双手略宽于肩握杠,杠铃放在锁骨上方。控制下放到胸口中部,再发力推起至手臂伸直。核心收紧,肩胛收紧下沉,发力时呼气。"
        ),
        FrameDemo(
            "杠铃深蹲", "squat",
            tip = "杠铃深蹲。双脚与肩同宽,下蹲时屈髋屈膝,大腿蹲到平行地面,膝盖对准脚尖方向。蹬地起身,发力时呼气。"
        ),
        FrameDemo(
            "罗马尼亚硬拉", "romanian_deadlift",
            tip = "罗马尼亚硬拉。双脚与髋同宽,双手握杠贴在腿前。屈髋俯身,臀部向后推,背部保持平直,杠铃沿腿部下移到小腿。再蹬地起身,全程腰背挺直。"
        ),
        FrameDemo(
            "箭步蹲", "lunge",
            tip = "箭步蹲。上身挺直,一腿向前迈出一大步。前腿弯曲到九十度,后腿膝盖接近地面。前脚发力站起,两边交替进行。膝盖不要超过脚尖。"
        ),
        FrameDemo(
            "杠铃推举", "overhead_press",
            tip = "杠铃推举。双手略宽于肩握杠,杠铃放在锁骨前。向上推过头顶,手臂伸直,核心收紧,再缓慢放回肩前。"
        ),
        FrameDemo(
            "哑铃肩推", "dumbbell_shoulder_press",
            tip = "哑铃肩推。双手各握一个哑铃,举在肩膀两侧。向上推起至手臂伸直,再缓慢下放。腰背挺直,不要借力。"
        ),
        FrameDemo(
            "锤式弯举", "hammer_curl",
            tip = "锤式弯举。双手各握哑铃,掌心相对,大臂贴紧身体。弯曲手肘把哑铃举向肩膀,再缓慢放下。身体不要晃动。"
        ),
        FrameDemo(
            "俯卧撑", "push_up",
            tip = "俯卧撑。双手略宽于肩,身体保持一条直线。屈肘下放到胸口接近地面,再发力推起。核心收紧,不要塌腰。"
        ),
        FrameDemo(
            "杠铃弯举", "bicep_curl",
            tip = "杠铃弯举。双脚与肩同宽,双手反握杠铃垂在体前。大臂贴紧身体,弯曲手肘把杠铃卷向肩膀,再缓慢放下。身体不要前后晃动。"
        ),
        FrameDemo(
            "哑铃前平举", "front_raise",
            tip = "哑铃前平举。双手各握哑铃垂在体前,掌心朝后。直臂把哑铃向前举到肩膀高度,再缓慢放下。腰背挺直,不要借力甩动。"
        ),
        FrameDemo(
            "哑铃侧平举", "lateral_raise",
            tip = "哑铃侧平举。双手各握哑铃垂在体侧。肘部微屈,把哑铃向两侧举到肩膀高度,像倒水一样,再缓慢放下。不要耸肩。"
        ),
        FrameDemo(
            "过顶臂屈伸", "overhead_tricep_extension",
            tip = "过顶臂屈伸。双手握一个哑铃举过头顶,大臂贴近耳朵。弯曲手肘让哑铃落到脑后,再伸直手臂。大臂保持不动,只有肘在动。"
        ),
        FrameDemo(
            "相扑深蹲", "sumo_squat",
            tip = "相扑深蹲。双脚宽距站立,脚尖向外四十五度。屈髋下蹲到大腿平行地面,膝盖对准脚尖方向。蹬地起身,臀部和大腿内侧发力。"
        ),
        FrameDemo(
            "后箭步蹲", "reverse_lunge",
            tip = "后箭步蹲。上身挺直,一腿向后迈出一大步。前腿弯曲到九十度,后腿膝盖下沉接近地面。前脚发力站起,两边交替。"
        ),
        FrameDemo(
            "站姿提踵", "calf_raise",
            tip = "站姿提踵。双脚与髋同宽,脚尖踩实地面。踮起脚跟到最高点,小腿顶峰停一秒,再缓慢放下。身体保持正直,不要屈膝借力。"
        ),
        FrameDemo(
            "卷腹", "crunch",
            tip = "卷腹。仰卧屈膝,双手轻扶头侧。腹部发力把肩胛骨抬离地面,下背贴地,再缓慢回落。不要用手拉脖子,动作幅度不用大。"
        ),
        FrameDemo(
            "平板支撑", "plank",
            tip = "平板支撑。双肘撑地与肩同宽,双脚并拢。身体从头到脚保持一条直线,腹部臀部收紧,自然呼吸。塌腰或撅臀都是错的。"
        ),
        FrameDemo(
            "臀桥", "glute_bridge",
            tip = "臀桥。仰卧屈膝,双脚踩实地面。臀部发力把髋部向上顶,到肩髋膝一条直线,顶峰停一秒再缓慢放下。发力时呼气。"
        ),
        FrameDemo(
            "小燕飞", "superman",
            tip = "小燕飞。俯卧,双臂向前伸。同时抬起手臂和上半身,双腿也微微抬离地面,像超人飞行,停一两秒再放下。腰部有伤就不做这个动作。"
        ),
        FrameDemo(
            "高抬腿", "high_knees",
            tip = "高抬腿。原地跑起来,膝盖抬到髋部高度,大腿与地面平行。前脚掌落地,双臂配合摆动。节奏保持均匀,核心收紧。"
        ),
        FrameDemo(
            "开合跳", "jumping_jack",
            tip = "开合跳。跳起时双脚向外分开,双手在头顶击掌,再跳回并拢。节奏保持均匀,落地屈膝缓冲。"
        ),
        FrameDemo(
            "早安式体前屈", "good_morning",
            tip = "早安式体前屈。双脚与髋同宽,双手抱胸。膝盖微屈,屈髋向前俯身到躯干接近平行地面,臀部向后推,再起身。全程腰背挺直。"
        )
    )

    // ---------- 面1 匹配:解析→别名→唯一子串,宁兜底不猜 ----------

    /**
     * 无歧义别名;「弯举」「划船」「卧推」等现库多义或无演示的短名不收,走兜底面板。
     * 2026-10-19 补常用裸名(AI 计划高频写法);近亲动作映射同族演示:
     * 深蹲→杠铃深蹲 / 跑步→高抬腿(原地跑画面,同族有氧跑姿)/ 仰卧臂屈伸→过顶臂屈伸(同族三头屈伸)。
     */
    private val ALIASES = mapOf(
        "深蹲" to "杠铃深蹲",
        "跑步" to "高抬腿",
        "卧推" to "杠铃卧推",
        "哑铃卧推" to "杠铃卧推",
        "仰卧臂屈伸" to "过顶臂屈伸",
        "二头弯举" to "杠铃弯举",
        "站姿弯举" to "杠铃弯举",
        "硬拉" to "罗马尼亚硬拉",
        "罗马尼亚硬拉" to "罗马尼亚硬拉",
        "RDL" to "罗马尼亚硬拉",
        "肩推" to "哑铃肩推",
        "哑铃推举" to "哑铃肩推",
        "坐姿推举" to "哑铃肩推",
        "坐姿哑铃推举" to "哑铃肩推",
        "锤式弯举" to "锤式弯举",
        "哑铃弯举" to "锤式弯举",
        "杠铃弯举" to "杠铃弯举",
        "提踵" to "站姿提踵",
        "站姿提踵" to "站姿提踵",
        "前平举" to "哑铃前平举",
        "哑铃前平举" to "哑铃前平举",
        "侧平举" to "哑铃侧平举",
        "哑铃侧平举" to "哑铃侧平举",
        "过顶臂屈伸" to "过顶臂屈伸",
        "哑铃过顶臂屈伸" to "过顶臂屈伸",
        "相扑深蹲" to "相扑深蹲",
        "后箭步蹲" to "后箭步蹲",
        "箭步蹲" to "箭步蹲",
        "箭步走" to "箭步蹲",
        "弓步蹲" to "箭步蹲",
        "卷腹" to "卷腹",
        "平板" to "平板支撑",
        "平板支撑" to "平板支撑",
        "臀桥" to "臀桥",
        "小燕飞" to "小燕飞",
        "超人式" to "小燕飞",
        "高抬腿" to "高抬腿",
        "开合跳" to "开合跳",
        "早安式" to "早安式体前屈",
        "早安式体前屈" to "早安式体前屈",
        "高脚杯深蹲" to "杠铃深蹲",
        "高脚杯" to "杠铃深蹲",
        "哑铃高脚杯深蹲" to "杠铃深蹲",
        "俯卧撑" to "俯卧撑",
        "跪姿俯卧撑" to "俯卧撑"
    )

    /** 计划文本→动作名:取行首连续「中英文+()（）·-」段,遇空白/数字/×截止 */
    fun parseName(raw: String): String {
        val s = raw.trim()
        val m = Regex("^[\\p{IsHan}A-Za-z()（）·\\-]+").find(s) ?: return s
        return m.value.trim()
    }

    data class Resolved(val title: String, val tip: String, val demo: FrameDemo?)

    /**
     * 计划行文本→(标题, 要领, 演示)。匹配不到时 demo=null(面板仍开,暂无动画),绝不猜配。
     */
    fun resolve(rawItem: String): Resolved {
        val parsed = parseName(rawItem)
        byName(parsed)?.let { return Resolved(rawItem.trim(), it.tip, it) }

        val aliased = ALIASES[parsed]?.let { byName(it) }
        if (aliased != null) return Resolved(rawItem.trim(), aliased.tip, aliased)

        // 唯一子串:parsed 命中的 demo 名全库只有一个才认
        val hits = DEMOS.filter { it.name.contains(parsed) && parsed.length >= 2 }
        if (hits.size == 1) return Resolved(rawItem.trim(), hits[0].tip, hits[0])

        return Resolved(rawItem.trim(), tipOf(parsed), null)
    }

    /** 演示面板标题用规范名直接解析(动作库入口) */
    fun resolveByName(name: String): Resolved {
        val d = byName(name)
        return Resolved(name, d?.tip ?: tipOf(name), d)
    }

    private fun byName(name: String): FrameDemo? = DEMOS.find { it.name == name }

    /** 要领:优先 WorkoutSeed(库内动作),再兜底通用提示 */
    private fun tipOf(name: String): String {
        WorkoutSeed.ALL.find { it.name == name }?.let { return it.tip }
        return "注意动作节奏,发力时呼气,有伤病史遵医嘱、量力而行。"
    }

    /** 当前已配演示的动作名(调试/自检用) */
    val demoNames: List<String> get() = DEMOS.map { it.name }
}
