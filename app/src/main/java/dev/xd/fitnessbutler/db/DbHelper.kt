package dev.xd.fitnessbutler.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SQLite 七表封装(meals / meal_items / weighings / activities / plans / settings / recognition_queue)。
 * 系统自带 SQLiteOpenHelper,零第三方依赖。所有时间戳 ISO 格式 "yyyy-MM-dd'T'HH:mm:ss"。
 */
class DbHelper private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "fitness.db", null, 7) {

    companion object {
        @Volatile
        private var inst: DbHelper? = null

        fun get(ctx: Context): DbHelper =
            inst ?: synchronized(this) { inst ?: DbHelper(ctx).also { inst = it } }

        const val SRC_LIB = "库"          // 来源:本地食物库
        const val SRC_AI = "AI估算"       // 来源:视觉 API 估算(旧称,M1.5 起统一 AI识别)
        const val SRC_AI_VISION = "AI识别" // 来源:视觉 API 直出
        const val SRC_MANUAL = "手记"     // 来源:手动录入(兜底)
    }

    // ---------- 建表 ----------

    override fun onCreate(db: SQLiteDatabase) {
        // 一餐(含照片、总热量、餐次、备注)
        db.execSQL(
            """CREATE TABLE meals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts TEXT NOT NULL,
                photo_path TEXT,
                total_kcal REAL NOT NULL DEFAULT 0,
                meal_type TEXT,
                note TEXT)"""
        )
        // 餐内菜品明细(菜名/克重/热量/来源/库id;v6 补三宏+候选)
        db.execSQL(
            """CREATE TABLE meal_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                meal_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                grams REAL,
                kcal REAL,
                source TEXT,
                food_id INTEGER,
                protein_g REAL,
                carbs_g REAL,
                fat_g REAL,
                candidates TEXT)"""
        )
        // 称重(时间/体重/阻抗/15 项指标 JSON/原始帧)
        db.execSQL(
            """CREATE TABLE weighings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts TEXT NOT NULL,
                weight_kg REAL NOT NULL,
                impedance REAL,
                metrics_json TEXT,
                raw_frame TEXT)"""
        )
        // 运动消耗(时间/类型/时长/消耗/来源;v2 补训练负荷/平均心率,M3 手表收割前为空)
        db.execSQL(
            """CREATE TABLE activities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts TEXT NOT NULL,
                type TEXT,
                duration_min REAL,
                kcal_burned REAL,
                source TEXT,
                training_load REAL,
                avg_hr REAL)"""
        )
        // 计划(起止日期/内容 JSON/打卡位)
        db.execSQL(
            """CREATE TABLE plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_date TEXT,
                end_date TEXT,
                content_json TEXT,
                checkin TEXT,
                done INTEGER NOT NULL DEFAULT 0)"""
        )
        // 设置(风格/称呼/提醒开关/API 地址/密钥引用等键值对)
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
        // 照片识别排队表
        db.execSQL(
            """CREATE TABLE recognition_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_path TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT '待识别',
                result_json TEXT,
                created_ts TEXT NOT NULL)"""
        )
        // 三张分析表(M3 手表收割落表)
        ensureAnalysisTables(db)
        // 对话记录表(M2 对话助手)
        ensureChatTable(db)
        // 锻炼计划/打卡表(M2.5 打卡制锻炼页)
        ensureWorkoutTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2(日历改版):activities 补训练负荷/平均心率两列(M3 手表收割前为空),已有数据不动
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE activities ADD COLUMN training_load REAL")
            db.execSQL("ALTER TABLE activities ADD COLUMN avg_hr REAL")
        }
        // v3(M3 手表收割):补建三张分析表,纯增量 CREATE IF NOT EXISTS,存量零改写
        ensureAnalysisTables(db)
        // v4(M2 对话助手):补建对话记录表,纯增量,存量零改写
        if (oldVersion < 4) ensureChatTable(db)
        // v5(M2.5 打卡制锻炼页):补建计划/打卡两表,纯增量,存量零改写
        if (oldVersion < 5) ensureWorkoutTables(db)
        // v6(2026-10-17 三宏+候选):meal_items 补 4 列,ALTER 增量迁移,绝不 drop 旧数据
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE meal_items ADD COLUMN protein_g REAL")
            db.execSQL("ALTER TABLE meal_items ADD COLUMN carbs_g REAL")
            db.execSQL("ALTER TABLE meal_items ADD COLUMN fat_g REAL")
            db.execSQL("ALTER TABLE meal_items ADD COLUMN candidates TEXT")
        }
        // v7(2026-10-18 对话压缩/新对话):chat_messages 补 session 列,存量全部归 'main' 会话
        if (oldVersion < 7) {
            db.execSQL(
                "ALTER TABLE chat_messages ADD COLUMN session TEXT NOT NULL DEFAULT 'main'"
            )
        }
    }

    /** 三张分析表(daily_stats/hourly_stats/sleep_stages,预留读取层 DailyMetrics 直读),建表幂等 */
    private fun ensureAnalysisTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS daily_stats (
                date TEXT NOT NULL,
                metric TEXT NOT NULL,
                value REAL NOT NULL,
                PRIMARY KEY(date, metric))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS hourly_stats (
                date TEXT NOT NULL,
                hour INTEGER NOT NULL,
                metric TEXT NOT NULL,
                value REAL NOT NULL,
                PRIMARY KEY(date, hour, metric))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sleep_stages (
                date TEXT NOT NULL,
                start_hour REAL NOT NULL,
                end_hour REAL NOT NULL,
                stage INTEGER NOT NULL)"""
        )
    }

    /** 对话记录表(M2;v7 起带 session 列),建表幂等 */
    private fun ensureChatTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                ts TEXT NOT NULL,
                session TEXT NOT NULL DEFAULT 'main')"""
        )
    }

    // ---------- 分析表写入(手表收割用,全部幂等) ----------

    /** 日聚合指标:同 date+metric 先删后插,重复收割不重复计 */
    fun replaceDailyStat(date: String, metric: String, value: Double) {
        val db = writableDatabase
        db.execSQL("DELETE FROM daily_stats WHERE date=? AND metric=?", arrayOf(date, metric))
        db.execSQL(
            "INSERT INTO daily_stats(date, metric, value) VALUES(?,?,?)",
            arrayOf<Any>(date, metric, value)
        )
    }

    /** 小时聚合指标(血氧/压力小时均值,M3 序列数据到位后用),先删后插 */
    fun replaceHourlyStat(date: String, hour: Int, metric: String, value: Double) {
        val db = writableDatabase
        db.execSQL(
            "DELETE FROM hourly_stats WHERE date=? AND hour=? AND metric=?",
            arrayOf<Any>(date, hour, metric)
        )
        db.execSQL(
            "INSERT INTO hourly_stats(date, hour, metric, value) VALUES(?,?,?,?)",
            arrayOf<Any>(date, hour, metric, value)
        )
    }

    fun clearSleepStages(date: String) {
        writableDatabase.execSQL("DELETE FROM sleep_stages WHERE date=?", arrayOf(date))
    }

    fun insertSleepStage(date: String, startHour: Double, endHour: Double, stage: Int) {
        writableDatabase.execSQL(
            "INSERT INTO sleep_stages(date, start_hour, end_hour, stage) VALUES(?,?,?,?)",
            arrayOf<Any>(date, startHour, endHour, stage)
        )
    }

    /** 手表当日消耗入 activities(source=手表),幂等:先删当日手表行再插 */
    fun replaceWatchActivity(date: String, ts: String, kcal: Double) {
        val db = writableDatabase
        db.execSQL(
            "DELETE FROM activities WHERE source='手表' AND substr(ts,1,10)=?", arrayOf(date)
        )
        insertActivity(ts, "全天活动(手表)", null, kcal, "手表")
    }

    // ---------- 数据类 ----------

    data class MealItem(
        var name: String,
        var grams: Double,
        var kcal: Double,
        var source: String,
        var foodId: Long = -1,
        /** 三宏(克,v6);旧数据/手记默认 0 */
        var protein: Double = 0.0,
        var carbs: Double = 0.0,
        var fat: Double = 0.0,
        /** 候选菜名(v6);逗号分隔存库,识别拿不准时给 */
        var candidates: List<String> = emptyList()
    )

    data class Meal(
        val id: Long,
        val ts: String,
        val photoPath: String?,
        val totalKcal: Double,
        val mealType: String?,
        val note: String?,
        val items: List<MealItem>
    )

    data class Weighing(
        val id: Long,
        val ts: String,
        val weightKg: Double,
        val impedance: Double?,
        val metricsJson: String?,
        val rawFrame: String?
    )

    data class Activity(
        val id: Long,
        val ts: String,
        val type: String?,
        val durationMin: Double?,
        val kcalBurned: Double?,
        val source: String?,
        val trainingLoad: Double? = null, // v2:M3 手表收割,收割前为空
        val avgHr: Double? = null         // v2:M3 手表收割,收割前为空
    )

    // ---------- meals / meal_items ----------

    /** 一餐连明细整体入库,返回 meal id */
    fun insertMeal(
        ts: String,
        photoPath: String?,
        mealType: String?,
        note: String?,
        items: List<MealItem>
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val total = items.sumOf { it.kcal }
            val cv = ContentValues().apply {
                put("ts", ts)
                put("photo_path", photoPath)
                put("total_kcal", total)
                put("meal_type", mealType)
                put("note", note)
            }
            val mealId = db.insert("meals", null, cv)
            for (it in items) {
                db.insert("meal_items", null, itemValues(mealId, it))
            }
            db.setTransactionSuccessful()
            return mealId
        } finally {
            db.endTransaction()
        }
    }

    /** 某日的全部餐次(带明细),按时间升序 */
    fun mealsOnDate(date: String): List<Meal> {
        val out = mutableListOf<Meal>()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM meals WHERE substr(ts,1,10)=? ORDER BY ts", arrayOf(date)
        )
        c.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow("id"))
                out.add(
                    Meal(
                        id = id,
                        ts = it.getString(it.getColumnIndexOrThrow("ts")),
                        photoPath = it.getStringOrNull("photo_path"),
                        totalKcal = it.getDouble(it.getColumnIndexOrThrow("total_kcal")),
                        mealType = it.getStringOrNull("meal_type"),
                        note = it.getStringOrNull("note"),
                        items = itemsOfMeal(id)
                    )
                )
            }
        }
        return out
    }

    private fun itemsOfMeal(mealId: Long): List<MealItem> {
        val out = mutableListOf<MealItem>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM meal_items WHERE meal_id=?", arrayOf(mealId.toString())
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    MealItem(
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        grams = it.getDouble(it.getColumnIndexOrThrow("grams")),
                        kcal = it.getDouble(it.getColumnIndexOrThrow("kcal")),
                        source = it.getStringOrNull("source") ?: SRC_MANUAL,
                        foodId = it.getLongOrNull("food_id") ?: -1,
                        protein = it.getDoubleOrNull("protein_g") ?: 0.0,
                        carbs = it.getDoubleOrNull("carbs_g") ?: 0.0,
                        fat = it.getDoubleOrNull("fat_g") ?: 0.0,
                        candidates = it.getStringOrNull("candidates")
                            ?.split(',')?.mapNotNull { s -> s.trim().ifBlank { null } }
                            ?: emptyList()
                    )
                )
            }
        }
        return out
    }

    /** 明细行 ContentValues(insert/update 共用;候选逗号分隔存库) */
    private fun itemValues(mealId: Long, it: MealItem): ContentValues = ContentValues().apply {
        put("meal_id", mealId)
        put("name", it.name)
        put("grams", it.grams)
        put("kcal", it.kcal)
        put("source", it.source)
        put("food_id", if (it.foodId >= 0) it.foodId else null)
        put("protein_g", it.protein)
        put("carbs_g", it.carbs)
        put("fat_g", it.fat)
        put("candidates", it.candidates.takeIf { it.isNotEmpty() }?.joinToString(","))
    }

    /** 某日摄入总热量 kcal */
    fun intakeKcalOn(date: String): Double {
        val c = readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(total_kcal),0) FROM meals WHERE substr(ts,1,10)=?",
            arrayOf(date)
        )
        c.use { if (it.moveToFirst()) return it.getDouble(0) }
        return 0.0
    }

    /** 某日入账笔数(每日提醒判断"当天没记录才提醒"用,2026-11-07) */
    fun mealCountOn(date: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM meals WHERE substr(ts,1,10)=?", arrayOf(date)
        )
        c.use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    // ---------- 改/删一笔(2026-10-17 自动入账配套:详情页点进编辑页用) ----------

    /** 按 id 取一餐(带明细);不存在返回 null */
    fun mealById(mealId: Long): Meal? {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM meals WHERE id=? LIMIT 1", arrayOf(mealId.toString())
        )
        c.use {
            if (!it.moveToFirst()) return null
            return Meal(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                ts = it.getString(it.getColumnIndexOrThrow("ts")),
                photoPath = it.getStringOrNull("photo_path"),
                totalKcal = it.getDouble(it.getColumnIndexOrThrow("total_kcal")),
                mealType = it.getStringOrNull("meal_type"),
                note = it.getStringOrNull("note"),
                items = itemsOfMeal(mealId)
            )
        }
    }

    /** 改一笔:保留原 id 与时间戳,明细整组重写(与 insertMeal 同一事务粒度) */
    fun updateMeal(
        mealId: Long, ts: String, photoPath: String?, mealType: String?,
        note: String?, items: List<MealItem>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val total = items.sumOf { it.kcal }
            val cv = ContentValues().apply {
                put("ts", ts)
                put("photo_path", photoPath)
                put("total_kcal", total)
                put("meal_type", mealType)
                put("note", note)
            }
            db.update("meals", cv, "id=?", arrayOf(mealId.toString()))
            db.delete("meal_items", "meal_id=?", arrayOf(mealId.toString()))
            for (it in items) {
                db.insert("meal_items", null, itemValues(mealId, it))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删一笔:餐与明细同删 */
    fun deleteMeal(mealId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("meals", "id=?", arrayOf(mealId.toString()))
            db.delete("meal_items", "meal_id=?", arrayOf(mealId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- weighings ----------

    fun insertWeighing(
        ts: String, weightKg: Double, impedance: Double?, metricsJson: String?, rawFrame: String?
    ): Long {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("weight_kg", weightKg)
            put("impedance", impedance)
            put("metrics_json", metricsJson)
            put("raw_frame", rawFrame)
        }
        return writableDatabase.insert("weighings", null, cv)
    }

    fun latestWeighing(): Weighing? {
        val c = readableDatabase.rawQuery("SELECT * FROM weighings ORDER BY ts DESC LIMIT 1", null)
        c.use {
            if (it.moveToFirst()) return weighingFromCursor(it)
        }
        return null
    }

    fun weighingsOnDate(date: String): List<Weighing> {
        val out = mutableListOf<Weighing>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM weighings WHERE substr(ts,1,10)=? ORDER BY ts", arrayOf(date)
        )
        c.use { while (it.moveToNext()) out.add(weighingFromCursor(it)) }
        return out
    }

    private fun weighingFromCursor(c: Cursor) = Weighing(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ts = c.getString(c.getColumnIndexOrThrow("ts")),
        weightKg = c.getDouble(c.getColumnIndexOrThrow("weight_kg")),
        impedance = c.getDoubleOrNull("impedance"),
        metricsJson = c.getStringOrNull("metrics_json"),
        rawFrame = c.getStringOrNull("raw_frame")
    )

    // ---------- activities ----------

    fun insertActivity(
        ts: String, type: String?, durationMin: Double?, kcal: Double?, source: String?,
        trainingLoad: Double? = null, avgHr: Double? = null
    ): Long {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("type", type)
            put("duration_min", durationMin)
            put("kcal_burned", kcal)
            put("source", source)
            put("training_load", trainingLoad)
            put("avg_hr", avgHr)
        }
        return writableDatabase.insert("activities", null, cv)
    }

    fun activitiesOnDate(date: String): List<Activity> {
        val out = mutableListOf<Activity>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM activities WHERE substr(ts,1,10)=? ORDER BY ts", arrayOf(date)
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    Activity(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        ts = it.getString(it.getColumnIndexOrThrow("ts")),
                        type = it.getStringOrNull("type"),
                        durationMin = it.getDoubleOrNull("duration_min"),
                        kcalBurned = it.getDoubleOrNull("kcal_burned"),
                        source = it.getStringOrNull("source"),
                        trainingLoad = it.getDoubleOrNull("training_load"),
                        avgHr = it.getDoubleOrNull("avg_hr")
                    )
                )
            }
        }
        return out
    }

    /** 某日消耗总热量 kcal */
    fun burnKcalOn(date: String): Double {
        val c = readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(kcal_burned),0) FROM activities WHERE substr(ts,1,10)=?",
            arrayOf(date)
        )
        c.use { if (it.moveToFirst()) return it.getDouble(0) }
        return 0.0
    }

    // ---------- settings 键值 ----------

    fun getSetting(key: String, def: String = ""): String {
        val c = readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key=?", arrayOf(key)
        )
        c.use { if (it.moveToFirst()) return it.getString(0) ?: def }
        return def
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict(
            "settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---------- recognition_queue ----------

    /**
     * 入队(2026-10-17 修堆积:同 photo_path 已有「待识别」在排 → 直接返回已有 id,不重复排)。
     * 源头收口:单张识别/手动重试/批量管线全走这里,一处查重处处生效(实测堆过 23 条同图)。
     */
    fun enqueueRecognition(photoPath: String, createdTs: String): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id FROM recognition_queue WHERE photo_path=? AND status='待识别' LIMIT 1",
                arrayOf(photoPath)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (existing != null) {
                db.setTransactionSuccessful()
                return existing
            }
            val cv = ContentValues().apply {
                put("photo_path", photoPath)
                put("status", "待识别")
                put("created_ts", createdTs)
            }
            val id = db.insert("recognition_queue", null, cv)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun markRecognition(id: Long, status: String, resultJson: String?) {
        val cv = ContentValues().apply {
            put("status", status)
            put("result_json", resultJson)
        }
        writableDatabase.update("recognition_queue", cv, "id=?", arrayOf(id.toString()))
    }

    /** 识别失败即丢弃队列行(2026-10-18 设计决策:失败不保存) */
    fun deleteRecognition(id: Long) {
        if (id <= 0) return
        writableDatabase.delete("recognition_queue", "id=?", arrayOf(id.toString()))
    }

    /**
     * 可补跑的卡死图(2026-11-05 划卡杀服务冤死图自救):
     * status='待识别' 且创建时间在 maxAgeMs 内的 photo_path(去重)。
     * 调用方补跑前自查 File.exists()。
     */
    fun requeueablePending(maxAgeMs: Long): List<String> {
        val cutoff = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(Date(System.currentTimeMillis() - maxAgeMs))
        val paths = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT photo_path FROM recognition_queue WHERE status='待识别' AND created_ts >= ?",
            arrayOf(cutoff)
        ).use { c ->
            while (c.moveToNext()) paths.add(c.getString(0))
        }
        return paths
    }

    /**
     * 启动清理(2026-10-18 设计决策;2026-11-05 改口径:只丢超龄的)——
     * 删队列行 + 删对话流里对应的图片消息,返回照片路径供调用方删文件。
     * 超龄(maxAgeMs 以上)的「待识别」图视为过期垃圾;6 小时内的新图走 requeueablePending 补跑。
     */
    fun discardPendingRecognitions(maxAgeMs: Long = 6 * 3600_000L): List<String> {
        val cutoff = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(Date(System.currentTimeMillis() - maxAgeMs))
        val paths = mutableListOf<String>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT DISTINCT photo_path FROM recognition_queue WHERE status='待识别' AND created_ts < ?",
                arrayOf(cutoff)
            ).use { c ->
                while (c.moveToNext()) paths.add(c.getString(0))
            }
            for (p in paths) {
                // 与 HomeFragment.PHOTO_PREFIX 保持一致:content = "📷<绝对路径>"
                db.delete("chat_messages", "content=?", arrayOf("📷<$p>"))
            }
            db.delete("recognition_queue", "status='待识别' AND created_ts < ?", arrayOf(cutoff))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return paths
    }

    fun pendingRecognitionCount(): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM recognition_queue WHERE status='待识别'", null
        )
        c.use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    data class Recognition(
        val id: Long, val photoPath: String, val status: String, val createdTs: String,
        val resultJson: String? = null
    )

    /** 某图最新识别状态(已识别/待识别/...),无记录返回 null —— 图左重试标记判显隐用 */
    fun recognitionStatusByPhoto(photoPath: String): String? {
        readableDatabase.rawQuery(
            "SELECT status FROM recognition_queue WHERE photo_path=? ORDER BY id DESC LIMIT 1",
            arrayOf(photoPath)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /** 行状态精确查询(2026-10-19 后台化:总线回执防重复呈现用) */
    fun recognitionStatusById(id: Long): String? {
        readableDatabase.rawQuery(
            "SELECT status FROM recognition_queue WHERE id=?", arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /**
     * 服务在后台跑完待呈现的结果(2026-10-19 后台化):
     * status='已识别待呈现' 的行;UI 认领(上屏+入账)后转「已识别」。
     */
    fun pendingPresentations(): List<Recognition> {
        val out = mutableListOf<Recognition>()
        readableDatabase.rawQuery(
            "SELECT * FROM recognition_queue WHERE status='已识别待呈现' ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Recognition(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        photoPath = c.getString(c.getColumnIndexOrThrow("photo_path")),
                        status = c.getString(c.getColumnIndexOrThrow("status")),
                        createdTs = c.getString(c.getColumnIndexOrThrow("created_ts")),
                        resultJson = c.getString(c.getColumnIndexOrThrow("result_json"))
                    )
                )
            }
        }
        return out
    }

    /** 该图是否已有入账餐食(meals.photo_path 命中)——图左重试标记判显隐兜底 */
    fun mealExistsForPhoto(photoPath: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM meals WHERE photo_path=? LIMIT 1", arrayOf(photoPath)
        ).use { return it.moveToFirst() }
    }

    /** 对话流里是否已有这条消息(补识重提交防重复上屏图片气泡) */
    fun chatHasMessage(content: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM chat_messages WHERE content=? LIMIT 1", arrayOf(content)
        ).use { return it.moveToFirst() }
    }

    /** 删一条对话消息(识别失败丢弃图片消息用) */
    fun deleteChatMessage(content: String) {
        writableDatabase.delete("chat_messages", "content=?", arrayOf(content))
    }

    /** 待识别队列(按时间升序,供「下次进拍照页自动补识」用) */
    fun pendingRecognitions(): List<Recognition> {
        val out = mutableListOf<Recognition>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM recognition_queue WHERE status='待识别' ORDER BY created_ts", null
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    Recognition(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        photoPath = it.getString(it.getColumnIndexOrThrow("photo_path")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        createdTs = it.getString(it.getColumnIndexOrThrow("created_ts"))
                    )
                )
            }
        }
        return out
    }

    // ---------- chat_messages(M2 对话) ----------

    data class ChatMsg(val id: Long, val role: String, val content: String, val ts: String)

    /** 追加一条对话(role=user/assistant;session=v7 会话隔离),返回 id */
    fun insertChatMessage(role: String, content: String, ts: String, session: String = "main"): Long {
        val cv = ContentValues().apply {
            put("role", role); put("content", content); put("ts", ts); put("session", session)
        }
        return writableDatabase.insert("chat_messages", null, cv)
    }

    /** 某会话的全部对话(按 id 升序;UI 回放与 LLM 上下文都只看当前会话) */
    fun chatMessagesOf(session: String): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM chat_messages WHERE session=? ORDER BY id", arrayOf(session)
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    ChatMsg(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        role = it.getString(it.getColumnIndexOrThrow("role")),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        ts = it.getString(it.getColumnIndexOrThrow("ts"))
                    )
                )
            }
        }
        return out
    }

    /** 某会话的消息条数(对话管理弹窗展示/自动压缩阈值判断) */
    fun chatCountOf(session: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chat_messages WHERE session=?", arrayOf(session)
        )
        c.use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    fun clearChatMessages() {
        writableDatabase.execSQL("DELETE FROM chat_messages")
    }

    // ---------- workout_plans / workout_checkins(M2.5 打卡制锻炼) ----------
    // 表结构/week_json 格式为跨窗口约定(桌面小组件按同规范只读),改动需两边同步:
    // week_json = {"days":[{"day":1,"items":["杠铃深蹲 4×8","跑步 30 分钟"]},...]},day=1(周一)…7(周日)

    data class WorkoutPlan(
        val id: Long, val name: String, val source: String,
        val weekJson: String, val active: Int
    )

    /** 计划/打卡两表,建表幂等 */
    fun ensureWorkoutTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workout_plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                source TEXT NOT NULL,
                week_json TEXT NOT NULL,
                active INTEGER NOT NULL DEFAULT 1)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workout_checkins (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                item TEXT NOT NULL,
                ts TEXT NOT NULL)"""
        )
    }

    /** 置入激活计划(旧计划全部置 inactive),返回新计划 id */
    fun setActivePlan(name: String, source: String, weekJson: String): Long {
        val db = writableDatabase
        db.execSQL("UPDATE workout_plans SET active=0")
        val cv = ContentValues().apply {
            put("name", name); put("source", source); put("week_json", weekJson); put("active", 1)
        }
        return db.insert("workout_plans", null, cv)
    }

    /** 当前激活计划(最新一条),无则 null */
    fun activePlan(): WorkoutPlan? {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM workout_plans WHERE active=1 ORDER BY id DESC LIMIT 1", null
        )
        c.use {
            if (it.moveToFirst()) return WorkoutPlan(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                name = it.getString(it.getColumnIndexOrThrow("name")),
                source = it.getString(it.getColumnIndexOrThrow("source")),
                weekJson = it.getString(it.getColumnIndexOrThrow("week_json")),
                active = 1
            )
        }
        return null
    }

    /** 激活计划某天(1=周一…7=周日)的运动项列表;无计划/解析失败返回空 */
    fun planItemsForDay(weekDay: Int): List<String> {
        val plan = activePlan() ?: return emptyList()
        return try {
            val days = JSONObject(plan.weekJson).getJSONArray("days")
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                if (d.getInt("day") == weekDay) {
                    val items = d.getJSONArray("items")
                    return (0 until items.length()).map { items.getString(it) }
                }
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 打卡一条(date=yyyy-MM-dd,item=运动项文本) */
    fun checkin(date: String, item: String, ts: String): Long {
        val cv = ContentValues().apply {
            put("date", date); put("item", item); put("ts", ts)
        }
        return writableDatabase.insert("workout_checkins", null, cv)
    }

    /** 取消打卡 */
    fun uncheckin(date: String, item: String) {
        writableDatabase.execSQL(
            "DELETE FROM workout_checkins WHERE date=? AND item=?", arrayOf(date, item)
        )
    }

    /** 某日已打卡的项集合 */
    fun checkedItems(date: String): Set<String> {
        val out = mutableSetOf<String>()
        val c = readableDatabase.rawQuery(
            "SELECT item FROM workout_checkins WHERE date=?", arrayOf(date)
        )
        c.use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    // ---------- daily_stats 读取(M2 对话上下文/首页睡眠用) ----------

    data class DailyStatRow(val date: String, val metric: String, val value: Double)

    /** 某日起的日指标行(升序) */
    fun dailyStatsSince(fromDate: String): List<DailyStatRow> {
        val out = mutableListOf<DailyStatRow>()
        val c = readableDatabase.rawQuery(
            "SELECT date, metric, value FROM daily_stats WHERE date>=? ORDER BY date",
            arrayOf(fromDate)
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    DailyStatRow(
                        date = it.getString(0),
                        metric = it.getString(1),
                        value = it.getDouble(2)
                    )
                )
            }
        }
        return out
    }

    // ---------- 游标小工具 ----------

    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.getDoubleOrNull(col: String): Double? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getDouble(i)
    }

    private fun Cursor.getLongOrNull(col: String): Long? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }
}
