// app/src/main/java/com/example/myapplication/smarthelmet/ui/OverlayModel.kt
package com.example.myapplication.smarthelmet.ui

data class OverlayModel(
    val lines: List<LineN> = emptyList(),    // 0..1 정규화 좌표
    val texts: List<TextN> = emptyList()
)

data class LineN(
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val strokePx: Float = 4f
)

data class TextN(
    val x: Float, val y: Float,
    val text: String,
    val sizeSp: Float = 12f
)
