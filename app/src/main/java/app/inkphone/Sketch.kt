package app.inkphone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DiscretePathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * A rounded box that looks drawn with a pen: two slightly different wobbly
 * outlines over each other, and an optional hatched shadow under it.
 */
class SketchDrawable(
    private val density: Float,
    private val fill: Int,
    private val stroke: Int,
    radiusDp: Float = 16f,
    private val shadow: Boolean = false,
    wobble: Float = 1f,
) : Drawable() {
    private val radius = radiusDp * density
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = stroke
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DiscretePathEffect(15f * density, 1.2f * density * wobble)
    }
    private val pen2 = Paint(pen).apply {
        strokeWidth = 1.1f * density
        alpha = 150
        pathEffect = DiscretePathEffect(23f * density, 1.8f * density * wobble)
    }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = stroke
        alpha = 150
        strokeWidth = 1.1f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = 3f * density
        val drop = if (shadow) 6f * density else 0f
        val box = RectF(b.left + inset, b.top + inset, b.right - inset - drop, b.bottom - inset - drop)
        val path = Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) }

        if (shadow) {
            canvas.save()
            canvas.clipPath(Path(path).apply { offset(drop, drop) })
            val step = 5f * density
            var x = b.left - b.height().toFloat()
            while (x < b.right) {
                canvas.drawLine(x, b.bottom.toFloat(), x + b.height(), b.top.toFloat(), hatch)
                x += step
            }
            canvas.restore()
        }
        if (fill != 0) canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, pen)
        canvas.drawPath(path, pen2)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The before/after strip at the top of the menu: the user's own app icons,
 * in colour on the left of a draggable pen line and as ink doodles on the right.
 */
class InkCompareView(context: Context, private val ink: Int, private val paper: Int) : View(context) {
    var pairs: List<Pair<Bitmap, Bitmap>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    private var split = 0.5f
    private val d = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ink
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
        pathEffect = DiscretePathEffect(9f * d, 1.3f * d)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = paper
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (tile(w) + 44 * d).toInt())
    }

    private fun tile(w: Int) = min(76f * d, (w - 5 * 12f * d) / 4f)

    override fun onDraw(canvas: Canvas) {
        if (pairs.isEmpty()) return
        val size = tile(width)
        val gap = (width - 4 * size) / 5f
        val top = 14f * d
        val cut = split * width
        pairs.take(4).forEachIndexed { i, (colour, doodle) ->
            val left = gap + i * (size + gap)
            val box = RectF(left, top, left + size, top + size)
            canvas.save()
            canvas.clipRect(0f, 0f, cut, height.toFloat())
            canvas.drawBitmap(colour, null, box, bitmapPaint)
            canvas.restore()
            canvas.save()
            canvas.clipRect(cut, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(doodle, null, box, bitmapPaint)
            canvas.restore()
        }
        // the pen line and its grab knob
        val knobY = height - 18f * d
        canvas.drawLine(cut, 2f * d, cut, knobY, line)
        canvas.drawCircle(cut, knobY, 14f * d, knob)
        val a = 4f * d
        canvas.drawPath(Path().apply {
            moveTo(cut - a, knobY - a); lineTo(cut - 2 * a, knobY); lineTo(cut - a, knobY + a)
            moveTo(cut + a, knobY - a); lineTo(cut + 2 * a, knobY); lineTo(cut + a, knobY + a)
        }, arrow)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                split = (event.x / width).coerceIn(0.05f, 0.95f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
