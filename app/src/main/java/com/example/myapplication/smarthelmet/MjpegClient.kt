package com.example.myapplication.smarthelmet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class MjpegClient(
    private val client: OkHttpClient = OkHttpClient(),          // 필요 시 .readTimeout() 등 조정
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val _errors = MutableSharedFlow<Throwable>(replay = 0, extraBufferCapacity = 1)
    val errors = _errors.asSharedFlow()

    fun start(url: String, imageView: ImageView, scope: CoroutineScope): Job {
        var callRef: Call? = null
        val job = scope.launch(io) {
            val req = Request.Builder().url(url).build()
            val call = client.newCall(req).also { callRef = it }
            try {
                val resp = call.execute()
                resp.use { r ->
                    val body = r.body ?: error("Empty body")
                    val ctype = r.header("Content-Type") ?: ""
                    val boundary = Regex("boundary=([^;]+)")
                        .find(ctype)?.groupValues?.get(1) ?: "frame"
                    val source = body.source()

                    readStream(source, boundary) { bmp ->
                        withContext(Dispatchers.Main) { imageView.setImageBitmap(bmp) }
                    }
                }
            } catch (e: Throwable) {
                // 취소 시 Socket/IO 예외가 올 수 있음 → 그대로 보고만 함
                _errors.emit(e)
            }
        }
        // 화면 파괴/정지 시 네트워크 즉시 끊기
        job.invokeOnCompletion { callRef?.cancel() }
        return job
    }

    @Throws(IOException::class)
    private suspend fun readStream(
        source: BufferedSource,
        boundary: String,
        onFrame: suspend (Bitmap) -> Unit
    ) {
        val dashBoundary = "--$boundary"
        while (true) {
            coroutineContext.ensureActive()

            // 1) boundary 라인까지 스킵
            var line = source.readUtf8LineStrict()
            while (line.isNotEmpty() && !line.startsWith(dashBoundary)) {
                coroutineContext.ensureActive()
                line = source.readUtf8LineStrict()
            }

            // 2) 헤더 파싱
            var contentLength = -1
            while (true) {
                coroutineContext.ensureActive()
                val hdr = source.readUtf8LineStrict()
                if (hdr.isEmpty()) break
                val idx = hdr.indexOf(':')
                if (idx > 0) {
                    val key = hdr.substring(0, idx).trim().lowercase()
                    val value = hdr.substring(idx + 1).trim()
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: -1
                }
            }

            if (contentLength <= 0) continue

            // 3) JPEG 바디 읽기
            val jpeg = source.readByteArray(contentLength.toLong())

            // 서버가 붙이는 trailing \r\n 소비 시도(없어도 무해)
            if (!source.exhausted()) {
                try { source.readUtf8LineStrict() } catch (_: Exception) {}
            }

            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
            onFrame(bmp)
        }
    }
}
