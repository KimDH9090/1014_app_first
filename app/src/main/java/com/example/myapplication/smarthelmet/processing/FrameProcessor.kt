// app/src/main/java/com/example/myapplication/smarthelmet/processing/FrameProcessor.kt
package com.example.myapplication.smarthelmet.processing

import android.graphics.Bitmap
import com.example.myapplication.smarthelmet.ui.OverlayModel

interface FrameProcessor {
    /** 입력 프레임(ARGB_8888 가정)을 처리해 오버레이를 반환(없으면 null) */
    fun processFrame(bmp: Bitmap): OverlayModel?
}
