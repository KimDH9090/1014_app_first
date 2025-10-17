package com.example.myapplication.smarthelmet

import android.content.Context
import android.util.Log
import com.example.myapplication.smarthelmet.record.RearCamDetectionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object RearCamDetectionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RearCamDetectionEngine.RearDetectionResult?>(null)
    val state: StateFlow<RearCamDetectionEngine.RearDetectionResult?> = _state.asStateFlow()

    private var job: Job? = null

    fun start(context: Context) {
        val appCtx = context.applicationContext
        val running = job?.isActive == true
        if (running) return
        val url = PiEndpoint.streamHttpUrl(appCtx, "/usb_feed?dev=2")
        val engine = RearCamDetectionEngine(appCtx.assets)
        job = scope.launch {
            try {
                engine.run(
                    url = url,
                    onFrame = null,
                    onDetections = { result -> _state.value = result },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e("RearCamDetection", "Detection loop failed", t)
            } finally {
                runCatching { engine.close() }
                if (_state.value != null) {
                    _state.value = null
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = null
    }

    fun refreshIfRunning(context: Context) {
        val wasRunning = job?.isActive == true
        if (!wasRunning) return
        stop()
        start(context)
    }
}