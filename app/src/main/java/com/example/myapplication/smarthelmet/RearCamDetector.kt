package com.example.myapplication.smarthelmet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RearCamDetector(
    private val context: Context,
    private val usbCamUrl: String,
    private val onObjectDetected: (label: String) -> Unit
) {
    companion object {
        private const val TAG = "RearCamDetector"
        private const val MODEL_FILENAME = "mobilenet_ssd.tflite"
        private const val LABEL_FILENAME = "labelmap.txt"
        private const val INPUT_SIZE    = 300
        private const val NUM_RESULTS   = 10
        private const val THRESHOLD     = 0.5f
    }

    private val imgData = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val tflite: Interpreter
    private val labels: List<String>
    private var job: Job? = null

    init {
        // 모델 & 라벨 로드
        tflite  = Interpreter(loadModel())
        labels  = loadLabels()
    }

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL(usbCamUrl).openConnection() as HttpURLConnection).apply {
                    doInput = true
                    connectTimeout = 5000
                    readTimeout    = 5000
                    connect()
                }
                val stream = MjpegInputStream(conn.inputStream)

                while (isActive) {
                    val bmpFrame = stream.readMjpegFrame() ?: continue
                    val detected = detectObject(bmpFrame)
                    if (detected != null) {
                        withContext(Dispatchers.Main) {
                            onObjectDetected(detected)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rear cam stream failed", e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun detectObject(bitmap: Bitmap): String? {
        // 1) 리사이즈 + 전처리
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        imgData.rewind()
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val p = scaled.getPixel(x, y)
                imgData.putFloat((p shr 16 and 0xFF) / 255f)
                imgData.putFloat((p shr 8  and 0xFF) / 255f)
                imgData.putFloat((p       and 0xFF) / 255f)
            }
        }

        // 2) 추론
        val locations = Array(1) { Array(NUM_RESULTS) { FloatArray(4) } }
        val classes   = Array(1) { FloatArray(NUM_RESULTS) }
        val scores    = Array(1) { FloatArray(NUM_RESULTS) }
        val numDet    = FloatArray(1)
        val outputs   = mapOf(
            0 to locations,
            1 to classes,
            2 to scores,
            3 to numDet
        )
        tflite.runForMultipleInputsOutputs(arrayOf(imgData), outputs)

        // 3) 결과 중 person/bicycle 중 threshold 이상이면 리턴
        val count = numDet[0].toInt()
        for (i in 0 until count) {
            val score = scores[0][i]
            if (score < THRESHOLD) continue
            val label = labels[ classes[0][i].toInt() ]
            if (label == "person" || label == "bicycle") {
                Log.d(TAG, "Detected $label (${String.format("%.2f", score)})")
                return label
            }
        }
        return null
    }

    private fun loadModel(): ByteBuffer =
        context.assets.open(MODEL_FILENAME).use { it.readBytes() }
            .let { ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()) }

    private fun loadLabels(): List<String> =
        context.assets.open(LABEL_FILENAME)
            .bufferedReader()
            .use(BufferedReader::readLines)

    private class MjpegInputStream(private val input: java.io.InputStream) {
        fun readMjpegFrame(): Bitmap? {
            val baos = ByteArrayOutputStream()
            var prev = 0
            // SOI 0xFFD8
            while (true) {
                val curr = input.read().takeIf { it >= 0 } ?: return null
                if (prev == 0xFF && curr == 0xD8) {
                    baos.write(0xFF); baos.write(0xD8)
                    break
                }
                prev = curr
            }
            prev = 0
            // EOF 0xFFD9
            while (true) {
                val curr = input.read().takeIf { it >= 0 } ?: break
                baos.write(curr)
                if (prev == 0xFF && curr == 0xD9) break
                prev = curr
            }
            return BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
        }
    }

    fun destroy() {
        tflite.close()
    }
}