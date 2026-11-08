package dev.xd.fitnessbutler.scale

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import dev.xd.fitnessbutler.R

/**
 * 连秤同步浮层(2026-10-25 设计决策:小组件点一下就连秤,不开 APP 界面)。
 * 透明 Activity:权限齐 → 卡片浮层「正在连接…请上秤」+ syncFromWidget,连上入账/超时自动 finish;
 * 权限缺 → 就地申请一次(小组件点击路径必须有 Activity 承载系统权限弹窗)。
 * 全程 BLE 扫描期间本 Activity 在前台,不触后台扫描限制。
 */
class ScaleSyncActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scale_sync)
        title = findViewById(R.id.sync_title)
        hint = findViewById(R.id.sync_hint)
        if (!ScaleAutoLink.hasPerms(this)) {
            title.text = "需要蓝牙权限"
            hint.text = "用于连接体重秤\n请在弹窗中允许"
            requestPermissions(ScaleAutoLink.neededPerms(), REQ_PERM)
        } else {
            begin()
        }
    }

    private fun begin() {
        title.text = "正在连接体重秤…"
        hint.text = "请站上秤站稳\n读数锁定后自动入账"
        // 实时读数上浮层(2026-10-27:连上/踩秤全程有反馈,不再一句「正在连接」挂到底)
        ScaleAutoLink.liveWeightCb = { w ->
            title.text = "已连上 · 测量中"
            hint.text = String.format(java.util.Locale.US, "%.1f kg\n站稳别动,锁定后自动入账", w)
        }
        ScaleAutoLink.syncFromWidget(this) {
            // 收工结果(入账/超时/未找到)显示 1.2s 再关,不再默默消失
            val msg = ScaleAutoLink.lastOutcome
            if (!msg.isNullOrBlank()) {
                title.text = msg
                hint.text = ""
                main.postDelayed({ finish() }, 1200)
            } else {
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val ok = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (ok) {
            begin()
        } else {
            title.text = "没有蓝牙权限"
            hint.text = "到系统设置给本 APP\n「附近设备」权限后重试"
            Toast.makeText(this, "未授权蓝牙,无法连秤", Toast.LENGTH_SHORT).show()
            main.postDelayed({ finish() }, 1800)
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)   // 透明浮层去去留不留动画
    }

    override fun onDestroy() {
        ScaleAutoLink.liveWeightCb = null   // 防浮层泄漏后回调空指针
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERM = 9203
    }
}
