package dev.xd.fitnessbutler.scale

import android.Manifest
import android.app.Fragment
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.ui.TextCopyUtil
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 体脂秤页:BLE 扫描 → 连接 → 订阅 notify → 解 0xAC 帧 → 实时重量 + 15 项体脂落库 weighings。
 * BLE 收编自 scale-collector(扫描/连接/订阅/帧处理照搬),落库改走 DbHelper。
 */
@Suppress("DEPRECATION")
class ScaleFragment : Fragment() {

    private lateinit var statusView: TextView
    private lateinit var weightView: TextView
    private lateinit var bodyfatView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceContainer: LinearLayout

    private val btManager: BluetoothManager by lazy {
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter get() = btManager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // 同一次称重去重:锁定后 30 秒内同 raw 不重复落库
    private var lastSavedRaw: Int = 0
    private var lastSavedAt: Long = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_scale, container, false)
        val act = activity as MainActivity

        statusView = v.findViewById(R.id.scale_status)
        weightView = v.findViewById(R.id.scale_weight)
        bodyfatView = v.findViewById(R.id.scale_bodyfat)
        logView = v.findViewById(R.id.scale_log)
        logScroll = v.findViewById(R.id.scale_log_scroll)
        deviceContainer = v.findViewById(R.id.scale_devices)

        v.findViewById<TextView>(R.id.scale_back).setOnClickListener {
            stopScan(); gatt?.close(); gatt = null
            act.backToScreen()
        }
        v.findViewById<TextView>(R.id.scale_scan).setOnClickListener {
            act.ensureBlePermissions()
            startScan()
        }
        v.findViewById<TextView>(R.id.scale_stop).setOnClickListener { stopScan() }
        v.findViewById<TextView>(R.id.scale_disconnect).setOnClickListener {
            stopScan(); gatt?.close(); gatt = null
            statusView.text = "未连接"
        }

        log("就绪。点「扫描秤」→ 上秤称重。")
        TextCopyUtil.apply(v)   // 全文字可复制(2026-10-17),RAW 日志可贴出诊断
        return v
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopScan()
        gatt?.close()
        gatt = null
    }

    // ---------- 用户档案(设置页维护,体脂公式用) ----------

    private fun currentProfile(): ScaleDecoder.Profile {
        val db = DbHelper.get(activity)
        return ScaleDecoder.Profile(
            heightCm = db.getSetting("height_cm", "170").toIntOrNull() ?: 170,
            age = db.getSetting("age", "30").toIntOrNull() ?: 30,
            male = db.getSetting("gender", "男") != "女"
        )
    }

    // ---------- 权限 ----------

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean = neededPerms().all {
        activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    // ---------- 扫描 ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            activity?.runOnUiThread { addDeviceRow(result) }
        }
    }

    private val seen = HashSet<String>()

    // 经典蓝牙发现补名字:系统设置能看到的设备名(如 AFU-WL-TZ-A1)走经典 inquiry,
    // BLE 广播常不带 Local Name,只扫 BLE 就只剩 MAC。两路名字按 MAC 合并。
    private val knownNames = HashMap<String, String>()
    private var classicReceiver: BroadcastReceiver? = null

    private fun rememberName(addr: String?, name: String?) {
        if (addr.isNullOrBlank() || name.isNullOrBlank()) return
        knownNames[addr.uppercase()] = name
    }

    private fun startClassicDiscovery() {
        try {
            adapter?.bondedDevices?.forEach { rememberName(it.address, it.name) }
        } catch (_: SecurityException) {}
        val rx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                val dev: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: dev?.name
                rememberName(dev?.address, name)
                // 经典通道发现的设备也补进行列表(若 BLE 未报过)
                val addr = dev?.address ?: return
                if (seen.add(addr)) {
                    val n = name ?: "(无名)"
                    val likely = n.contains("AFU-WL", true) || n.contains("AFU", true) ||
                        n.contains("ICOMON", true) || n.contains("SCALE", true)
                    val row = TextView(activity).apply {
                        text = "${if (likely) " ★疑似秤" else ""} $n\n$addr  经典发现"
                        textSize = 13f
                        setPadding(16, 24, 16, 24)
                        setBackgroundResource(R.drawable.bg_row_white)
                        setOnClickListener {
                            stopScan()
                            connect(dev)
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 12 }
                    if (likely) deviceContainer.addView(row, 0, lp) else deviceContainer.addView(row, lp)
                }
            }
        }
        classicReceiver = rx
        activity.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_FOUND))
        try {
            adapter?.startDiscovery()
        } catch (_: SecurityException) {}
    }

    private fun stopClassicDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        classicReceiver?.let {
            try { activity.unregisterReceiver(it) } catch (_: Exception) {}
        }
        classicReceiver = null
    }

    private fun startScan() {
        ScaleAutoLink.stopForManual()   // 手动优先:自动连秤让位(单 gatt 不打架)
        if (scanning) return
        if (!hasPerms()) {
            log("缺蓝牙权限,请先授权后重试")
            return
        }
        if (adapter?.isEnabled != true) {
            log("蓝牙未开启,请先打开蓝牙")
            return
        }
        seen.clear()
        deviceContainer.removeAllViews()
        try {
            scanner?.startScan(scanCb)
            scanning = true
            statusView.text = "扫描中…(看到秤的服务就点条目连接)"
            log("开始 BLE 扫描 + 经典发现(补设备名)")
            startClassicDiscovery()
        } catch (e: SecurityException) {
            log("扫描被拒:${e.message},请授予权限")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        stopClassicDiscovery()
        scanning = false
    }

    private fun addDeviceRow(r: ScanResult) {
        val addr = r.device.address
        if (!seen.add(addr)) return
        val name = try {
            r.device.name ?: r.scanRecord?.deviceName ?: knownNames[addr.uppercase()] ?: "(无名)"
        } catch (_: SecurityException) {
            knownNames[addr.uppercase()] ?: "(无名)"
        }
        val services = r.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() } ?: ""
        // iBeacon/AltBeacon 硬指纹:阿福秤广播 proximity UUID face0016-0010-0080-8000-10ca006d2ca5
        // (在厂商广播数据字节里,不在 serviceUuids;逆向阿福 APK altbeacon 栈所得)
        val mfg = r.scanRecord?.manufacturerSpecificData
        var beaconHit = false
        if (mfg != null) {
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
                        ) { beaconHit = true; break }
                    }
                }
                if (beaconHit) break
            }
        }
        val likely = beaconHit ||
            services.contains("af000002", true) ||
            services.contains("00001530", true) ||
            name.contains("AFU-WL", true) || name.contains("AFU", true) ||
            name.contains("ICOMON", true) || name.contains("SCALE", true)
        val marker = if (likely) " ★疑似秤" else ""
        val row = TextView(activity).apply {
            text = "$marker $name\n$addr  RSSI=${r.rssi}"
            textSize = 13f
            setPadding(16, 24, 16, 24)
            setBackgroundResource(R.drawable.bg_row_white)
            setOnClickListener {
                stopScan()
                connect(r)
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 }
        // 疑似秤置顶,普通设备排后
        if (likely) deviceContainer.addView(row, 0, lp) else deviceContainer.addView(row, lp)
    }

    // ---------- 连接 / 订阅 ----------

    private fun connect(r: ScanResult) = connect(r.device)

    private fun connect(dev: BluetoothDevice) {
        ScaleAutoLink.stopForManual()   // 手动优先:自动连秤让位
        gatt?.close()
        statusView.text = "连接中 ${dev.address}…"
        log("连接 ${knownNames[dev.address.uppercase()] ?: dev.name ?: ""} ${dev.address}")
        gatt = dev.connectGatt(
            activity, false, gattCb,
            android.bluetooth.BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            activity?.runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    statusView.text = "已连接,发现服务…"
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    statusView.text = "已断开"
                    log("连接断开(status=$st)")
                    g.close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            activity?.runOnUiThread {
                var n = 0
                for (s in g.services) {
                    log("服务 ${s.uuid}")
                    for (c in s.characteristics) {
                        log("  特征 ${c.uuid} props=${c.properties}")
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            g.setCharacteristicNotification(c, true)
                            val cccd = c.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (cccd != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    g.writeDescriptor(cccd)
                                }
                                n++
                            }
                        }
                    }
                }
                log("已订阅 $n 个 notify 特征,等待秤数据(上秤即可)")
                statusView.text = "等待上秤…"
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            activity?.runOnUiThread { handleFrame(value) }
        }

        @Deprecated("旧接口兼容")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            activity?.runOnUiThread { handleFrame(v) }
        }
    }

    // ---------- 帧处理 ----------

    private fun handleFrame(data: ByteArray) {
        ScaleDecoder.profile = currentProfile()
        val hex = ScaleDecoder.hex(data)
        val res = ScaleDecoder.parse(data)
        if (res == null) {
            log("RAW $hex")
            return
        }
        when (res.kind) {
            "weight" -> {
                statusView.text = "称重中 ${"%.2f".format(Locale.US, res.weightKg)} kg"
                weightView.text = "%.2f kg".format(Locale.US, res.weightKg)
            }
            "bodyfat" -> {
                val m = res.metrics!!
                statusView.text = "已锁定 ${"%.2f".format(Locale.US, res.weightKg)} kg"
                weightView.text = "%.2f kg".format(Locale.US, res.weightKg)
                bodyfatView.text =
                    "体脂 ${m.optDouble("body_fat_pct")}% · 肌肉 ${m.optDouble("muscle_rate_pct")}% · ${m.optString("physique")}"
                log("★体脂包 raw=${res.rawWeight} 阻抗=${res.impedance}Ω → ${"%.2f".format(Locale.US, res.weightKg)} kg")
                saveWeighing(res, m, hex)
            }
        }
    }

    /** 15 项体脂落库 weighings(同 raw 30 秒内去重) */
    private fun saveWeighing(res: ScaleDecoder.Result, m: JSONObject, hex: String) {
        val now = System.currentTimeMillis()
        if (res.rawWeight == lastSavedRaw && now - lastSavedAt < 30_000) return
        lastSavedRaw = res.rawWeight
        lastSavedAt = now

        val db = DbHelper.get(activity)
        db.insertWeighing(
            ts = dateFmt.format(Date()),
            weightKg = res.weightKg,
            impedance = res.impedance,
            metricsJson = m.toString(),
            rawFrame = hex
        )
        log("已入账 weighings:${"%.2f".format(Locale.US, res.weightKg)} kg")
        // 记住这台秤,下次开 APP 自动直连
        runCatching {
            gatt?.device?.let { d ->
                db.setSetting("scale_last_addr", d.address ?: "")
                db.setSetting("scale_last_name", knownNames[d.address?.uppercase()] ?: d.name ?: "")
            }
        }
        (activity as? MainActivity)?.toast("称重已入账 ${"%.2f".format(Locale.US, res.weightKg)} kg")
    }

    // ---------- 日志 ----------

    private fun log(s: String) {
        val line = "[${timeFmt.format(Date())}] $s\n"
        logView.append(line)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
