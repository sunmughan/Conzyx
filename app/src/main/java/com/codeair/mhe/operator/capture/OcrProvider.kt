package com.codeair.mhe.operator.capture

import android.graphics.Bitmap

data class OcrBlock(val text: String, val bounds: android.graphics.Rect)
data class OcrResult(val text: String, val blocks: List<OcrBlock> = emptyList())

interface OcrProvider {
    fun recognize(bitmap: Bitmap): OcrResult
}
