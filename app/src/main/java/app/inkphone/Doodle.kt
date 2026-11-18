package app.inkphone

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns any icon into a hand-drawn ink sketch: wobbly outlines traced from the
 * icon's edges, with pen hatching where the original is dark.
 *
 * Works on plain ARGB pixel arrays so it runs (and is tested) off-device.
 */
object Doodle {
    private const val INK = 0x1C1C1C

    fun sketch(src: IntArray, w: Int, h: Int, seed: Int): IntArray {
        val n = w * h
        var lum = FloatArray(n)
        var alp = FloatArray(n)
        for (i in 0 until n) {
            val c = src[i]
            val a = (c ushr 24) / 255f
            val l = (0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)) / 255f
            lum[i] = l * a + (1f - a) // composite over white paper
            alp[i] = a
        }
        lum = blur(lum, w, h)
        alp = blur(alp, w, h)

        val edge = FloatArray(n)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                edge[y * w + x] = max(sobel(lum, w, x, y), sobel(alp, w, x, y))
            }
        }

        // Low-frequency wobble so straight lines look drawn by hand; seeded per app
        // so every icon gets its own, stable, "handwriting".
        val rnd = java.util.Random(seed.toLong())
        val p = FloatArray(4) { rnd.nextFloat() * 6.283f }
        val amp = w / 110f
        val f1 = 6.283f / (w * 0.45f)
        val f2 = 6.283f / (w * 0.17f)
        val spacing = max(5f, w / 15f)

        val out = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x + amp * (sin(y * f1 + p[0]) + 0.5f * sin(y * f2 + p[1]))
                val sy = y + amp * (sin(x * f1 + p[2]) + 0.5f * sin(x * f2 + p[3]))
                var ink = smooth(0.16f, 0.42f, sample(edge, w, h, sx, sy))

                val a = sample(alp, w, h, sx, sy)
                val l = sample(lum, w, h, sx, sy)
                if (a > 0.5f && l < 0.5f) {
                    val dark = min(1f, (0.5f - l) / 0.08f)
                    ink = max(ink, 0.8f * dark * hatch(sx + sy, spacing))
                    if (l < 0.2f) {
                        val darker = min(1f, (0.2f - l) / 0.06f)
                        ink = max(ink, 0.8f * darker * hatch(sx - sy, spacing))
                    }
                }
                out[y * w + x] = ((ink * 255f).toInt() shl 24) or INK
            }
        }
        return out
    }

    /** 1 on a hatch line, falling to 0 between lines. */
    private fun hatch(t: Float, spacing: Float): Float {
        val m = t - spacing * floor(t / spacing)
        return 1f - smooth(0.45f, 1.25f, abs(m - spacing / 2f))
    }

    private fun smooth(lo: Float, hi: Float, v: Float): Float {
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sobel(a: FloatArray, w: Int, x: Int, y: Int): Float {
        val i = y * w + x
        val gx = a[i - w + 1] + 2f * a[i + 1] + a[i + w + 1] - a[i - w - 1] - 2f * a[i - 1] - a[i + w - 1]
        val gy = a[i + w - 1] + 2f * a[i + w] + a[i + w + 1] - a[i - w - 1] - 2f * a[i - w] - a[i - w + 1]
        return sqrt(gx * gx + gy * gy)
    }

    private fun blur(a: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        s += a[yy * w + (x + dx).coerceIn(0, w - 1)]
                    }
                }
                o[y * w + x] = s / 9f
            }
        }
        return o
    }

    private fun sample(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, w - 1.001f)
        val y = fy.coerceIn(0f, h - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val tx = x - x0
        val ty = y - y0
        val i = y0 * w + x0
        val top = a[i] * (1f - tx) + a[i + 1] * tx
        val bot = a[i + w] * (1f - tx) + a[i + w + 1] * tx
        return top * (1f - ty) + bot * ty
    }
}
