package com.example.myapplication.smarthelmet.record

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.io.use

class RearCamTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Surface(Modifier.fillMaxSize()) { RearCamTestScreen() } }
    }
}

/* ----------------------- UI ----------------------- */
@Composable
private fun RearCamTestScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var dets by remember { mutableStateOf<RearCamDetectionEngine.RearDetectionResult?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            RearCamDetectionEngine(ctx.assets).use { engine ->
                engine.run(
                    url = "http://10.42.0.1:5000/usb_feed?dev=2",
                    onFrame = { bmp -> frame = bmp },
                    onDetections = { result -> dets = result },
                )
            }
        }
    }

    // 영상 + 오버레이를 함께 180° 회전
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer(rotationZ = 180f)
    ) {
        val result = dets
        frame?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Rear Stream",
                modifier = Modifier.fillMaxSize()
            )
            DetectionOverlay(bmp, result, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DetectionOverlay(
    bmp: Bitmap,
    result: RearCamDetectionEngine.RearDetectionResult?,
    modifier: Modifier,
) {
    if (result == null) return
    Canvas(modifier = modifier) {
        val vw = size.width
        val vh = size.height
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val ox = (vw - dw) / 2f
        val oy = (vh - dh) / 2f

        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val textP = Paint().apply {
            textSize = 36f
            isAntiAlias = true
        }
        val fill = Paint().apply { style = Paint.Style.FILL }

        val canvas = drawContext.canvas.nativeCanvas
        result.detections.forEach { d ->
            val l = ox + d.box.left * dw
            val t = oy + d.box.top * dh
            val r = ox + d.box.right * dw
            val b = oy + d.box.bottom * dh

            val sc = RearDetectionColors.strokeColor(d.label)
            val fc = RearDetectionColors.fillColor(d.label, alpha = 80)
            val tc = RearDetectionColors.textColor(d.label)

            fill.color = fc
            stroke.color = sc
            textP.color = tc

            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, stroke)
            canvas.drawText("${d.label} ${(d.score * 100).toInt()}%", l + 6f, (t - 8f).coerceAtLeast(24f), textP)
        }
    }
}