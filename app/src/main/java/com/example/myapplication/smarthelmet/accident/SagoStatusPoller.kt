// app/src/main/java/com/example/myapplication/smarthelmet/accident/SagoStatusPoller.kt
package com.example.myapplication.smarthelmet.accident

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SagoStatusPoller(
    private val scope: CoroutineScope,
    private val baseUrl: String,              // 예: "http://10.42.0.1:5001"
    private val intervalMs: Long = 1000L      // 폴링 간격(1초)
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var job: Job? = null
    @Volatile private var running = false
    private var lastSeenTs: String? = null

    fun start(
        onNewSago: (String) -> Unit,          // 새 sago 발생 시(ISO-UTC 타임스탬프 전달)
        onError: (Throwable) -> Unit = {}     // 네트워크 에러 등
    ) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            while (running) {
                try {
                    val req = Request.Builder()
                        .url("$baseUrl/accident/status")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            val sago = json.optBoolean("sago", false)
                            val ts   = json.optString("last_sago_ts", "")
                            if (sago && ts.isNotBlank() && ts != lastSeenTs) {
                                lastSeenTs = ts
                                withContext(Dispatchers.Main) { onNewSago(ts) }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { onError(e) }
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }
}
