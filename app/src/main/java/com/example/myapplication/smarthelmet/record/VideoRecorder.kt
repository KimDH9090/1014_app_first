package com.example.myapplication.smarthelmet.record
import kotlin.coroutines.coroutineContext
import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
class VideoRecorder(private val context: Context) {
    companion object {
        const val FRONT_CAM_URL = "http://<FRONT_PI_IP>:<PORT>/stream.mjpg"
        const val REAR_CAM_URL  = "http://<REAR_PI_IP>:<PORT>/stream.mjpg"
    }
    private var frontJob: Job? = null
    private var rearJob: Job?  = null
    fun start(durationMs: Long) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "accidents")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val frontFile = File(dir, "front_$ts.mjpg")
        val rearFile  = File(dir, "rear_$ts.mjpg")
        // launch 확장 함수를 사용하려면 CoroutineScope.launch 를 임포트해야 합니다
        frontJob = CoroutineScope(Dispatchers.IO).launch {
            recordStream(FRONT_CAM_URL, frontFile, durationMs)
        }
        rearJob = CoroutineScope(Dispatchers.IO).launch {
            recordStream(REAR_CAM_URL, rearFile, durationMs)
        }
    }
    fun stop() {
        frontJob?.cancel()
        rearJob?.cancel()
        frontJob = null
        rearJob  = null
    }
    private suspend fun recordStream(
        url: String,
        outFile: File,
        durationMs: Long
    ) {
        var conn: HttpURLConnection? = null
        var fos: FileOutputStream?   = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                doInput       = true
                connectTimeout= 5_000
                readTimeout   = 5_000
                connect()
            }
            fos = FileOutputStream(outFile)
            val buffer = ByteArray(8 * 1024)
            val stopAt = System.currentTimeMillis() + durationMs
            val input  = conn.inputStream
            // isActive 도 코루틴 컨텍스트에서만 인식됩니다
            while (coroutineContext.isActive && System.currentTimeMillis() < stopAt) {
                val len = input.read(buffer)
                if (len <= 0) break
                fos.write(buffer, 0, len)
            }
        } catch (_: Exception) {
        } finally {
            fos?.flush()
            fos?.close()
            conn?.disconnect()
        }
    }
}