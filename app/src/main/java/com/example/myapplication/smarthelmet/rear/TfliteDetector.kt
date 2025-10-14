package com.example.myapplication.smarthelmet.rear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** 단일 검출 결과 */
data class Det(
    val rect: RectF,   // 원본 프레임 기준 좌표 (px)
    val label: String,
    val score: Float
)

/**
 * MobileNet-SSD(TFLite) 간단 검출기
 * - 입력: ARGB_8888 Bitmap
 * - 출력: NMS 적용한 박스/라벨/스코어 리스트
 *
 * 주의:
 * - assets/ 에 모델/라벨 파일이 있어야 함:
 *   - mobilenet_ssd.tflite
 *   - labelmap.txt  (첫 줄이 background)
 * - build.gradle.kts: implementation("org.tensorflow:tensorflow-lite:2.10.0")
 * - android { androidResources { noCompress += "tflite" } } 유지
 */
class TfliteDetector(
    context: Context,
    modelAsset: String = "mobilenet_ssd.tflite",
    labelAsset: String = "labelmap.txt",
    private val inputSize: Int = 300,        // SSD Mobilenet v1 기본
    private val scoreThresh: Float = 0.5f,   // 스코어 임계값
    private val iouThresh: Float = 0.5f      // NMS IoU 임계값
) : Closeable {

    private val labels: List<String>
    private val interpreter: Interpreter

    init {
        // 모델 로드 (간단 ByteBuffer 방식)
        val modelBytes = context.assets.open(modelAsset).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        model.put(modelBytes).rewind()

        val opts = Interpreter.Options().apply {
            // 가벼운 가속 옵션 (추가 의존성 없이 사용 가능)
            // setUseNNAPI(true)  // 디바이스에 따라 ON/OFF 테스트 권장
            // setNumThreads(2)   // 상황에 맞게 조정
        }
        interpreter = Interpreter(model, opts)

        // 라벨 로드
        labels = runCatching {
            context.assets.open(labelAsset).bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrElse {
            // 최소 라벨 세트 (문제 시 fallback)
            listOf("background", "person", "bicycle", "car", "motorcycle", "bus", "truck")
        }
    }

    override fun close() {
        interpreter.close()
    }

    /** 단일 프레임 검출 */
    fun detect(src: Bitmap): List<Det> {
        // 1) 입력 전처리: 300x300 RGB float32, -1..1 정규화
        val input = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val scaled = Bitmap.createScaledBitmap(src, inputSize, inputSize, true)
        val px = IntArray(inputSize * inputSize)
        scaled.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)

        var k = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val c = px[k++]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                input.putFloat((r - 0.5f) * 2f)
                input.putFloat((g - 0.5f) * 2f)
                input.putFloat((b - 0.5f) * 2f)
            }
        }
        input.rewind()

        // 2) 출력 버퍼 (SSD-Mobilenet v1 표준)
        val outLoc = Array(1) { Array(10) { FloatArray(4) } } // [ymin,xmin,ymax,xmax] (0..1)
        val outCls = Array(1) { FloatArray(10) }              // class index
        val outScr = Array(1) { FloatArray(10) }              // score
        val outCnt = FloatArray(1)

        val outputs = mapOf(
            0 to outLoc,
            1 to outCls,
            2 to outScr,
            3 to outCnt
        )

        // 3) 추론
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        // 4) 후처리: 스케일 복원 + 스코어 필터 + NMS
        val W = src.width.toFloat()
        val H = src.height.toFloat()
        val raw = mutableListOf<Det>()
        val n = min(10, outCnt[0].toInt())
        for (i in 0 until n) {
            val score = outScr[0][i]
            if (score < scoreThresh) continue

            val clsIdx = outCls[0][i].toInt().coerceAtLeast(0)
            val label = labels.getOrElse(clsIdx) { "obj" }

            val ymin = (outLoc[0][i][0] * H).coerceIn(0f, H)
            val xmin = (outLoc[0][i][1] * W).coerceIn(0f, W)
            val ymax = (outLoc[0][i][2] * H).coerceIn(0f, H)
            val xmax = (outLoc[0][i][3] * W).coerceIn(0f, W)

            if (xmax > xmin && ymax > ymin) {
                raw += Det(RectF(xmin, ymin, xmax, ymax), label, score)
            }
        }
        return nms(raw, iouThresh)
    }

    // --------------------------- utils ---------------------------

    private fun nms(dets: List<Det>, iouThresh: Float): List<Det> {
        val pool = dets.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<Det>()
        while (pool.isNotEmpty()) {
            val a = pool.removeAt(0)
            keep += a
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (iou(a.rect, b.rect) > iouThresh) it.remove()
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val uni = a.width() * a.height() + b.width() * b.height() - inter
        return if (uni <= 0f) 0f else inter / uni
    }
}
