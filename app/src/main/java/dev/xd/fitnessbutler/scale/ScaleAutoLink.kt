package dev.xd.fitnessbutler.scale

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.widget.ScaleWidget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 开 APP 自动连秤(今日未称重才启动,省电):
 * 已知 MAC 直连,否则 BLE 扫描 + 指纹匹配(AltBeacon 字节/服务 UUID/名字族,照 ScaleFragment 判定),
 * 连上订阅 0xAC 帧 → 体脂锁定包入账 weighings → 记住 MAC/名字 → 断开。
 *
 * - 解码/落库复用 ScaleDecoder / DbHelper,解码器零复制
 * - 手动优先:ScaleFragment 开扫描/连接前调 [stopForManual],单 gatt 不打架
 * - 超时:扫描 45s 无果静默收工;连接 3min 无锁定帧断开省电
 * - 权限缺失 / 蓝牙未开 / 今日已称 → 一律静默跳过
 */
@Suppress("DEPRECATION")
object ScaleAutoLink {

    private const val SCAN_TIMEOUT_MS = 45_000L
    private const val LOCK_TIMEOUT_MS = 90_000L

    /** 自动链路申请蓝牙权限的 requestCode(MainActivity.onRequestPermissionsResult 转发) */
    const val PERM_REQ_AUTO = 9201

    /** 权限弹窗结果回调:通过则自动续跑连秤链路 */
    fun onPermResult(granted: Boolean, ctx: Context) {
        if (granted) Thread { maybeAutoLink(ctx.applicationContext) }.start()
    }

    /** 权限清单给 ScaleSyncActivity 复用(小组件点击路径就地申请) */
    internal fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var appCtx: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    @Volatile
    private var active = false

    // 同一次称重去重:锁定后 12 秒内同 raw 不重复落库(2026-10-27 30→12s:连踩两次同重量的真·第二次曾被误杀)
    private var lastSavedRaw = 0
    private var lastSavedAt = 0L

    // 小组件浮层收工回调(syncFromWidget 设置,各收工点 fireDone 触发)
    @Volatile
    private var doneCb: (() -> Unit)? = null

    // 2026-10-27 体验三件套:实时读数上浮层 / 收工结果上浮层 / 直连失败转扫描
    /** 浮层实时读数回调(收到秤的实时重量帧时推;ScaleSyncActivity 设置,onDestroy 清) */
    @Volatile
    var liveWeightCb: ((Double) -> Unit)? = null
    /** 收工结果文案(「✓ 已入账…」/「超时…」),浮层显示后延迟关闭 */
    @Volatile
    var lastOutcome: String? = null
    /** 直连断开后是否已回退到扫描(一次机会,防循环) */
    @Volatile
    private var usedScanFallback = false

    // 经典发现补名字(只用于记录 scale_last_name,自动流程无 UI)
    private val knownNames = HashMap<String, String>()
    private var classicReceiver: BroadcastReceiver? = null

    private val scanTimeout = Runnable {
        stopScanInternal()
        if (active) {
            active = false
            lastOutcome = "附近没找到秤\n确认秤有电,或到「设置 · 体脂秤」手动连"
            toast("未找到秤,可到「设置 · 体脂秤」手动连接")
            fireDone()
        }
    }
    private val lockTimeout = Runnable {
        if (active) {
            lastOutcome = "没等到锁定读数\n下秤再上秤,或再点一次组件"
            toast("秤未响应,已断开(上秤后可再开 APP)")
            stopAll()
        }
    }

    // ---------- 入口 ----------

    /**
     * 小组件手动同步入口(2026-10-25 点桌面「称重」组件):
     * 强制连秤不问今日是否已称(用户点了就是想称),scale_on=0 时明确告知不静默;
     * onDone 在收工(入账/超时/未找到/断开)时回调,浮层 Activity 靠它自动关闭。
     */
    fun syncFromWidget(ctx: Context, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (active) {
            // 已有会话在跑:旧浮层仍在前台显示实时读数,新浮层秒退即可(2026-10-27 文案改明确)
            toast("正在连秤中,看浮层提示")
            fireDone()
            return
        }
        doneCb = onDone
        lastOutcome = null
        usedScanFallback = false
        val db = DbHelper.get(app)
        if (db.getSetting("scale_on", "1") != "1") {
            toast("体重秤功能在设置里是关的,先去打开")
            fireDone()
            return
        }
        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val ad = btManager.adapter
        if (ad == null || ad.isEnabled != true) {
            toast("手机蓝牙没开,开了再点")
            fireDone()
            return
        }
        appCtx = app
        adapter = ad
        active = true
        // 前台保活:放下手机/熄屏时 vivo 会冻结进程丢蓝牙帧,服务进程不冻结(2026-11-05)
        ScaleSyncService.start(app)

        // 已知 MAC 直连(桌面点击场景最常见:就是家里那一台)
        val knownAddr = db.getSetting("scale_last_addr", "")
        if (knownAddr.isNotBlank()) {
            val dev = try { ad.getRemoteDevice(knownAddr) } catch (_: Exception) { null }
            if (dev != null) {
                connect(dev)
                mainHandler.postDelayed(lockTimeout, LOCK_TIMEOUT_MS)
                return
            }
        }
        toast("正在找秤…(请站上秤)")
        startScan()
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    /** 浮层收工回调:置空防重入,主线程执行(activity finish 要求);顺带撤前台保活服务 */
    private fun fireDone() {
        appCtx?.let { ScaleSyncService.stop(it) }
        val cb = doneCb
        doneCb = null
        cb?.let { c -> mainHandler.post { runCatching { c() } } }
    }

    /** 启动自动链路:今日已称/蓝牙没开/scale_on=0 一律静默;权限缺失时主动申请一次(通过后自动续跑) */
    fun maybeAutoLink(ctx: Context) {
        if (active) return
        val app = ctx.applicationContext
        val db = DbHelper.get(app)
        if (db.getSetting("scale_on", "1") != "1") return
        if (!hasPerms(app)) {
            // 2026-10-18 修:此前权限缺失静默退出=自动链路永不启动;现在首次主动申请,点一次以后全自动
            (ctx as? android.app.Activity)?.let { act ->
                toast("需要蓝牙权限用于自动连秤,请在弹窗中允许")
                // requestPermissions 必须主线程(maybeAutoLink 可能从后台线程进入)
                mainHandler.post {
                    runCatching { act.requestPermissions(neededPerms(), PERM_REQ_AUTO) }
                }
            }
            return
        }
        if (db.weighingsOnDate(dateFmt.format(Date())).isNotEmpty()) return

        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val ad = btManager.adapter ?: return
        if (ad.isEnabled != true) return

        appCtx = app
        adapter = ad
        active = true
        // 同挂前台保活:自动连秤后熄屏同样会被 vivo 冻结丢帧(2026-11-05)
        ScaleSyncService.start(app)

        // ① 已知 MAC 直连(上次连过的秤)
        val knownAddr = db.getSetting("scale_last_addr", "")
        if (knownAddr.isNotBlank()) {
            val dev = try { ad.getRemoteDevice(knownAddr) } catch (_: Exception) { null }
            if (dev != null) {
                toast("正在连接体重秤…")
                connect(dev)
                mainHandler.postDelayed(lockTimeout, LOCK_TIMEOUT_MS)
                return
            }
        }

        // ② 扫描指纹匹配
        toast("正在找秤…(找到自动连接)")
        startScan()
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    /** 手动优先:ScaleFragment 开扫描/连接时调用,立刻让位 */
    fun stopForManual() {
        if (!active && gatt == null && !scanning) return
        stopAll()
    }

    // ---------- 权限 ----------

    internal fun hasPerms(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------- 扫描 ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!active) return
            if (isLikelyScale(result)) {
                mainHandler.removeCallbacks(scanTimeout)
                stopScanInternal()
                toast("找到秤,连接中…")
                connect(result.device)
                mainHandler.postDelayed(lockTimeout, LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun startScan() {
        if (scanning) return
        startClassicNameDiscovery()
        try {
            adapter?.bluetoothLeScanner?.startScan(scanCb)
            scanning = true
        } catch (_: Exception) {
            scanning = false
        }
    }

    private fun stopScanInternal() {
        if (scanning) {
            try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
            scanning = false
        }
        stopClassicNameDiscovery()
    }

    /** 秤指纹:AltBeacon proximity UUID 字节 / 服务 UUID / 名字族(照 ScaleFragment 判定) */
    private fun isLikelyScale(r: ScanResult): Boolean {
        val name = try {
            r.device.name ?: r.scanRecord?.deviceName ?: knownNames[r.device.address.uppercase()] ?: ""
        } catch (_: SecurityException) {
            knownNames[r.device.address.uppercase()] ?: ""
        }
        val services = r.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() } ?: ""
        if (services.contains("af000002", true) || services.contains("00001530", true)) return true
        if (name.contains("AFU-WL", true) || name.contains("AFU", true) ||
            name.contains("ICOMON", true) || name.contains("SCALE", true)
        ) return true
        // iBeacon/AltBeacon 硬指纹:fa ce 00 16 00 10 00 80 80 00 10 ca 00 6d 2c a5
        val mfg = r.scanRecord?.manufacturerSpecificData ?: return false
        for (k in 0 until mfg.size()) {
            val v = mfg.valueAt(k) ?: continue
            if (v.size >= 16) {
                for (i in 0..v.size - 16) {
                    if (v[i] == 0xfa.toByte() && v[i + 1] == 0xce.toByte() &&
                        v[i + 2] == 0x00.toByte() && v[i + 3] == 0x16.toByte() &&
                        v[i + 4] == 0x00.toByte() && v[i + 5] == 0x10.toByte() &&
                        v[i + 6] == 0x00.toByte() && v[i + 7] == 0x80.toByte() &&
                        v[i + 8] == 0x80.toByte() && v[i + 9] == 0x00.toByte() &&
                        v[i + 10] == 0x10.toByte() && v[i + 11] == 0xca.toByte() &&
                        v[i + 12] == 0x00.toByte() && v[i + 13] == 0x6d.toByte() &&
                        v[i + 14] == 0x2c.toByte() && v[i + 15] == 0xa5.toByte()
                    ) return true
                }
            }
        }
        return false
    }

    // ---------- 经典发现补名字 ----------

    private fun startClassicNameDiscovery() {
        val ad = adapter ?: return
        try {
            ad.bondedDevices?.forEach {
                if (!it.address.isNullOrBlank() && !it.name.isNullOrBlank())
                    knownNames[it.address.uppercase()] = it.name
            }
        } catch (_: SecurityException) {}
        val rx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                val dev: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: dev?.name
                val addr = dev?.address ?: return
                if (!addr.isNullOrBlank() && !name.isNullOrBlank()) knownNames[addr.uppercase()] = name
            }
        }
        classicReceiver = rx
        try {
            appCtx?.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_FOUND))
            ad.startDiscovery()
        } catch (_: Exception) {}
    }

    private fun stopClassicNameDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        classicReceiver?.let {
            try { appCtx?.unregisterReceiver(it) } catch (_: Exception) {}
        }
        classicReceiver = null
    }

    // ---------- 连接 / 订阅 ----------

    private fun connect(dev: BluetoothDevice) {
        try {
            gatt?.close()
        } catch (_: Exception) {}
        gatt = try {
            dev.connectGatt(appCtx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
        if (gatt == null) active = false
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!active) {
                        try { g.close() } catch (_: Exception) {}
                        return
                    }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    if (active) {
                        // 2026-10-27:直连失败(秤休眠/没在广播)先回退扫描一次,不再傻等超时
                        if (!usedScanFallback) {
                            usedScanFallback = true
                            mainHandler.removeCallbacks(lockTimeout)
                            toast("直连没成功,改扫描找秤…")
                            startScan()
                            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
                        } else {
                            active = false
                            if (lastOutcome == null) lastOutcome = "秤断开了\n再点一次组件重试"
                            mainHandler.removeCallbacks(scanTimeout)
                            mainHandler.removeCallbacks(lockTimeout)
                            stopScanInternal()
                            fireDone()
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            if (!active) return
            for (s in g.services) {
                for (c in s.characteristics) {
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        g.setCharacteristicNotification(c, true)
                        val cccd = c.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (cccd != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(cccd)
                            }
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleFrame(value)
        }

        @Deprecated("旧接口兼容")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            handleFrame(v)
        }
    }

    // ---------- 帧处理 / 落库 ----------

    private fun handleFrame(data: ByteArray) {
        if (!active) return
        val app = appCtx ?: return
        val db = DbHelper.get(app)
        ScaleDecoder.profile = currentProfile(db)
        val res = ScaleDecoder.parse(data) ?: return
        if (res.kind != "bodyfat") {
            // 实时重量帧:推浮层显示(2026-10-27 用户反馈「连得慢」——其实是等锁定帧没反馈)
            val cb = liveWeightCb
            val w = res.weightKg
            if (cb != null && w > 0) mainHandler.post { runCatching { cb(w) } }
            return
        }

        val m = res.metrics ?: return
        val now = System.currentTimeMillis()
        if (res.rawWeight == lastSavedRaw && now - lastSavedAt < 12_000) return
        lastSavedRaw = res.rawWeight
        lastSavedAt = now

        db.insertWeighing(
            ts = timeFmt.format(Date()),
            weightKg = res.weightKg,
            impedance = res.impedance,
            metricsJson = m.toString(),
            rawFrame = ScaleDecoder.hex(data)
        )
        // 记住这台秤,下次直连
        runCatching {
            gatt?.device?.let { d ->
                db.setSetting("scale_last_addr", d.address ?: "")
                db.setSetting("scale_last_name", knownNames[d.address?.uppercase()] ?: d.name ?: "")
            }
        }
        toast("今日体重已入账 ${"%.2f".format(Locale.US, res.weightKg)} kg")
        lastOutcome = "✓ 已入账 %.2f kg".format(Locale.US, res.weightKg)
        ScaleWidget.pushUpdate(app)   // 桌面小组件同步最新体重
        stopAll()   // 入账即断开省电(内含 fireDone 收浮层)
    }

    /** 用户档案(体脂公式用),读 settings */
    private fun currentProfile(db: DbHelper): ScaleDecoder.Profile =
        ScaleDecoder.Profile(
            heightCm = db.getSetting("height_cm", "170").toIntOrNull() ?: 170,
            age = db.getSetting("age", "30").toIntOrNull() ?: 30,
            male = db.getSetting("gender", "男") != "女"
        )

    // ---------- 收工 ----------

    private fun stopAll() {
        active = false
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(lockTimeout)
        stopScanInternal()
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        fireDone()   // 浮层 Activity 收工关闭(无回调时为空操作)
    }

    private fun toast(msg: String) {
        val app = appCtx ?: return
        mainHandler.post {
            runCatching { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
