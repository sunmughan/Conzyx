package com.codeair.mhe.operator.capture

import android.graphics.Bitmap

class StubOcrProvider : OcrProvider {
    override fun recognize(bitmap: Bitmap): OcrResult = OcrResult("")
}
