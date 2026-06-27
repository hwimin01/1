package com.ebookreader.accessibility

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image

object ImageToBitmapConverter {
    fun convert(image: Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) return null

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 실제 해상도로 크롭 (패딩 제거)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}
