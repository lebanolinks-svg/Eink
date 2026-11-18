package app.inkphone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable

object IconStyler {
    const val ORIGINAL = 0
    const val INK = 1
    const val DOODLE = 2

    fun render(icon: Drawable, style: Int, size: Int, seed: Int): Bitmap {
        val base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Leave a margin for the doodle so the wobbling outline is not clipped.
        val pad = if (style == DOODLE) size / 14 else 0
        icon.setBounds(pad, pad, size - pad, size - pad)
        icon.draw(Canvas(base))

        return when (style) {
            INK -> {
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val gray = ColorMatrix().apply { setSaturation(0f) }
                val contrast = ColorMatrix(
                    floatArrayOf(
                        1.3f, 0f, 0f, 0f, -38f,
                        0f, 1.3f, 0f, 0f, -38f,
                        0f, 0f, 1.3f, 0f, -38f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
                contrast.preConcat(gray)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(contrast) }
                Canvas(out).drawBitmap(base, 0f, 0f, paint)
                out
            }
            DOODLE -> {
                val px = IntArray(size * size)
                base.getPixels(px, 0, size, 0, 0, size, size)
                base.setPixels(Doodle.sketch(px, size, size, seed), 0, size, 0, 0, size, size)
                base
            }
            else -> base
        }
    }
}
