package dev.xd.fitnessbutler.ui

import android.app.AlertDialog
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.data.MoveDemoData

/**
 * 动作演示弹窗 v2(2026-10-18):标题+要领+帧序列演示区+控制条。
 * 控制:播放/暂停、快进(1/4 周期)、重播、倍速(0.5/1/1.5/2)、镜像开关、视角(正面/侧面)。
 * 语音:打开与重播时播一遍 assets/movedemo/voice/<dir>.mp3(中文要领),不随倍速。
 * 无演示数据时:仍开面板显示文字要领+「暂无动画」,控制条隐藏(行为一致,不猜配)。
 * 生命周期:dismiss 时 release() + 音频释放,防泄漏。
 */
object MoveDemoDialog {

    private val SPEEDS = floatArrayOf(0.5f, 1f, 1.5f, 2f)
    private var showing: AlertDialog? = null
    private var player: MediaPlayer? = null

    /** 页面销毁时兜底关闭(防 WindowLeak) */
    fun dismissIfShowing() {
        runCatching { showing?.dismiss() }
        showing = null
        releaseAudio()
    }

    private fun releaseAudio() {
        runCatching { player?.release() }
        player = null
    }

    fun show(act: MainActivity, title: String, tip: String, demo: MoveDemoData.FrameDemo?) {
        val v = LayoutInflater.from(act).inflate(R.layout.dialog_move_demo, null)
        v.findViewById<TextView>(R.id.demo_title).text = title
        v.findViewById<TextView>(R.id.demo_tip).text = tip

        val demoView = v.findViewById<MoveDemoView>(R.id.demo_view)
        val controls = v.findViewById<View>(R.id.demo_controls)
        demoView.setMove(demo)

        val dlg = AlertDialog.Builder(act).setView(v).create()
        dlg.setOnDismissListener {
            demoView.release()
            releaseAudio()
            showing = null
        }

        if (demo == null) {
            controls.visibility = View.GONE
        } else {
            val btnPlay = v.findViewById<TextView>(R.id.demo_btn_play)
            val btnFf = v.findViewById<TextView>(R.id.demo_btn_ff)
            val btnReplay = v.findViewById<TextView>(R.id.demo_btn_replay)
            val btnSpeed = v.findViewById<TextView>(R.id.demo_btn_speed)
            val btnMirror = v.findViewById<TextView>(R.id.demo_btn_mirror)
            val btnView = v.findViewById<TextView>(R.id.demo_btn_view)

            var speedIdx = 1
            var mirrored = false
            var side = "front"

            fun paintPlay() {
                btnPlay.text = if (demoView.isPlaying()) "⏸ 暂停" else "▶ 播放"
            }

            fun playVoice() {
                releaseAudio()
                runCatching {
                    val afd = act.assets.openFd("movedemo/voice/${demo.dir}.mp3")
                    val mp = MediaPlayer()
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    mp.prepare()
                    mp.start()
                    player = mp
                    afd.close()
                }
            }

            btnPlay.setOnClickListener {
                if (demoView.isPlaying()) {
                    demoView.pause()
                    runCatching { player?.pause() }
                } else {
                    demoView.play()
                    runCatching { player?.start() }
                }
                paintPlay()
            }
            // 快进:1/4 周期(连点 4 次≈一个循环)
            btnFf.setOnClickListener { demoView.seek(demoView.currentPhase() + 0.25f) }
            btnReplay.setOnClickListener {
                demoView.seek(0f)
                if (!demoView.isPlaying()) demoView.play()
                playVoice() // 重播配音
                paintPlay()
            }
            btnSpeed.setOnClickListener {
                speedIdx = (speedIdx + 1) % SPEEDS.size
                demoView.setSpeed(SPEEDS[speedIdx])
                btnSpeed.text = "×${SPEEDS[speedIdx]}"
            }
            btnMirror.setOnClickListener {
                mirrored = !mirrored
                demoView.setMirror(mirrored)
                btnMirror.text = if (mirrored) "镜像 开" else "镜像 关"
            }
            btnView.setOnClickListener {
                side = if (side == "front") "side" else "front"
                demoView.setView(side)
                btnView.text = if (side == "front") "视角 正" else "视角 侧"
            }

            demoView.play()
            paintPlay()
            playVoice() // 打开播一遍要领
        }

        dlg.show()
        showing = dlg
        // 弹窗宽度撑满(AlertDialog 默认会缩)
        dlg.window?.let { w: Window ->
            w.setLayout(
                (act.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
