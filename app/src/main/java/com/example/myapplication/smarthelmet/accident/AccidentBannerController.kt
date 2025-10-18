package com.example.myapplication.smarthelmet.accident

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 사고 알림 팝업 컨트롤러
 * - 최초 감지 즉시 팝업으로 "사고가 감지되었습니다" 표출
 * - autoReportDelayMs 후 "119에 자동신고되었습니다"로 문구 전환
 * - 동일 사고 진행 중 중복 이벤트는 무시
 */
class AccidentBannerController(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val autoReportDelayMs: Long = 5_000L
) {
    private enum class Phase { IDLE, ALARM_SHOWN, REPORTED }

    private var phase: Phase = Phase.IDLE
    private var job: Job? = null
    private var dialog: AlertDialog? = null

    private val messageView: TextView by lazy {
        TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
    }

    /** 새 사고 타임스탬프 수신 시 호출 */
    fun onAccident(@Suppress("UNUSED_PARAMETER") tsIsoUtc: String) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        when (phase) {
            Phase.IDLE -> {
                phase = Phase.ALARM_SHOWN
                show("사고가 감지되었습니다", "#CCFF3333")
                job?.cancel()
                job = lifecycleOwner.lifecycleScope.launch {
                    delay(autoReportDelayMs)
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        withContext(Dispatchers.Main) {
                            showInternal("119에 자동신고되었습니다", "#CCFF9900")
                            phase = Phase.REPORTED
                        }
                    }
                }
            }
            Phase.ALARM_SHOWN, Phase.REPORTED -> {
                // 진행 중이면 타이머/문구 유지
            }
        }
    }

    /** 다음 사고를 수용하고 싶을 때 호출 */
    fun reset() {
        job?.cancel(); job = null
        phase = Phase.IDLE
        hide()
    }

    fun dispose() {
        job?.cancel(); job = null
        phase = Phase.IDLE
        hide()
    }

    private fun show(text: String, color: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(text, color)
        } else {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                showInternal(text, color)
            }
        }
    }

    private fun showInternal(text: String, color: String) {
        val parsed = Color.parseColor(color)
        val tv = messageView

        // AlertDialog#setView 는 부모가 없는 뷰만 허용하므로 선행 분리
        (tv.parent as? ViewGroup)?.removeView(tv)

        tv.text = text
        tv.setBackgroundColor(parsed)

        val dlg = dialog ?: AlertDialog.Builder(context)
            .setView(tv)
            .setCancelable(true)
            .create()
            .also { created ->
                created.setOnDismissListener {
                    dialog = null
                    if (phase != Phase.REPORTED) {
                        phase = Phase.IDLE
                    }
                }
                dialog = created
            }

        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setCanceledOnTouchOutside(true)
        if (!dlg.isShowing) {
            dlg.show()
        }
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun hide() {
        val action = {
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            dialog = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { action() }
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density + 0.5f).toInt()
    }
}