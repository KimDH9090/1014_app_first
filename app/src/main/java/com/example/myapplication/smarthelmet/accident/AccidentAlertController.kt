package com.example.myapplication.smarthelmet.accident

import android.content.Context
import android.os.Looper
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
 * 1. Raspberry Pi에서 새로운 "sago" 이벤트를 받으면 즉시 팝업을 띄운다.
 * 2. 사용자가 "사고 아님"을 누르면 팝업을 닫고 상태를 초기화한다.
 * 3. 30초가 지나도록 사용자가 응답하지 않으면 "119 신고" 안내 팝업으로 전환한다.
 *    팝업이 활성화된 동안에는 사고 폴러를 일시 정지시켜 중복 이벤트가 쌓이지 않도록 한다.
 */
class AccidentAlertController(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val autoReportDelayMs: Long = 30_000L,
    private val onRequirePause: () -> Unit = {},
    private val onAllowResume: () -> Unit = {}
) {
    private enum class Phase { IDLE, ALERT, REPORT }

    private var phase: Phase = Phase.IDLE
    private var dialog: AlertDialog? = null
    private var timerJob: Job? = null

    /** 새 사고 타임스탬프 수신 시 호출 */
    fun onAccident(@Suppress("UNUSED_PARAMETER") tsIsoUtc: String) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (phase != Phase.IDLE) return

        phase = Phase.ALERT
        onRequirePause.invoke()
        scheduleAutoReport()
        showAlertDialog()
    }

    /** 사용자 확인 등으로 다음 사고를 수용하고 싶을 때 호출 */
    fun reset() {
        resetInternal(resumePolling = true)
    }

    fun dispose() {
        resetInternal(resumePolling = true)
    }

    private fun scheduleAutoReport() {
        timerJob?.cancel()
        timerJob = lifecycleOwner.lifecycleScope.launch {
            delay(autoReportDelayMs)
            if (phase == Phase.ALERT && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                withContext(Dispatchers.Main) { showReportDialog() }
            }
        }
    }

    private fun showAlertDialog() {
        runOnMain {
            dismissDialog()
            val dlg = AlertDialog.Builder(context)
                .setMessage("사고가 감지되었습니다")
                .setPositiveButton("사고 아님", null)
                .setCancelable(false)
                .create()

            dlg.setCanceledOnTouchOutside(false)
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    handleUserDismiss()
                }
            }
            dlg.setOnDismissListener {
                dialog = null
                if (phase == Phase.ALERT) {
                    handleUserDismiss()
                }
            }

            dialog = dlg
            dlg.show()
        }
    }

    private fun showReportDialog() {
        phase = Phase.REPORT
        runOnMain {
            dismissDialog()
            val dlg = AlertDialog.Builder(context)
                .setMessage("119 신고")
                .setPositiveButton("확인", null)
                .setCancelable(false)
                .create()

            dlg.setCanceledOnTouchOutside(false)
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    completeReport()
                }
            }
            dlg.setOnDismissListener {
                dialog = null
                if (phase == Phase.REPORT) {
                    completeReport()
                }
            }

            dialog = dlg
            dlg.show()
        }
    }

    private fun handleUserDismiss() {
        resetInternal(resumePolling = true)
    }

    private fun completeReport() {
        resetInternal(resumePolling = true)
    }

    private fun resetInternal(resumePolling: Boolean) {
        timerJob?.cancel()
        timerJob = null
        phase = Phase.IDLE
        dismissDialog()
        if (resumePolling) {
            onAllowResume.invoke()
        }
    }

    private fun dismissDialog() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { block() }
        }
    }
}
