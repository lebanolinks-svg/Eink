package app.inkphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class DoodleTest {
    private val size = 168
    private val c = size / 2f

    private fun icon(color: (x: Float, y: Float) -> Int): IntArray =
        IntArray(size * size) { i -> color(i % size + 0.5f, i / size + 0.5f) }

    private fun inDisc(x: Float, y: Float, r: Float) = hypot(x - c, y - c) < r
    private fun inTile(x: Float, y: Float): Boolean {
        val half = c - 12f
        val round = 26f
        val dx = maxOf(abs(x - c) - (half - round), 0f)
        val dy = maxOf(abs(y - c) - (half - round), 0f)
        return hypot(dx, dy) < round
    }

    private fun samples(): List<IntArray> = listOf(
        // green disc, white bubble
        icon { x, y -> if (!inDisc(x, y, 72f)) 0 else if (inDisc(x, y, 34f)) WHITE else 0xFF25D366.toInt() },
        // dark blue tile, white bar "glyph"
        icon { x, y ->
            if (!inTile(x, y)) 0
            else if (abs(x - c) < 9f && abs(y - c) < 44f || abs(y - c + 14f) < 8f && abs(x - c) < 30f) WHITE
            else 0xFF102A6B.toInt()
        },
        // white tile, red play triangle
        icon { x, y ->
            if (!inTile(x, y)) 0
            else if (x > 64f && abs(y - c) < (116f - x) * 0.62f) 0xFFE51D1D.toInt()
            else WHITE
        },
        // black tile, white ring
        icon { x, y -> if (!inTile(x, y)) 0 else if (inDisc(x, y, 38f) && !inDisc(x, y, 27f)) WHITE else BLACK },
    )

    @Test
    fun transparentStaysBlankAndEdgesGetInk() {
        val blank = Doodle.sketch(IntArray(size * size), size, size, 1)
        assertTrue("empty icon must stay empty", blank.all { it ushr 24 == 0 })

        val out = Doodle.sketch(samples()[0], size, size, 7)
        val inked = out.count { it ushr 24 > 128 }
        assertTrue("outline should be drawn", inked > size)
        assertTrue("sketch should stay mostly paper", inked < size * size / 3)
        assertEquals("corner is outside the icon", 0, out[0] ushr 24)
    }

    @Test
    fun sameSeedIsStable() {
        val src = samples()[1]
        assertTrue(Doodle.sketch(src, size, size, 42).contentEquals(Doodle.sketch(src, size, size, 42)))
    }

    /** Writes a before/after contact sheet (PPM) to build/doodle-preview.ppm for eyeballing. */
    @Test
    fun writePreview() {
        val all = samples()
        val w = size * all.size
        val sheet = IntArray(w * size * 2) { PAPER }
        all.forEachIndexed { i, px ->
            val sketch = Doodle.sketch(px, size, size, i * 31 + 5)
            for (p in 0 until size * size) {
                val x = i * size + p % size
                val y = p / size
                sheet[y * w + x] = over(px[p], PAPER)
                sheet[(y + size) * w + x] = over(sketch[p], PAPER)
            }
        }
        val bytes = ByteArray(sheet.size * 3)
        sheet.forEachIndexed { i, v ->
            bytes[i * 3] = (v shr 16).toByte(); bytes[i * 3 + 1] = (v shr 8).toByte(); bytes[i * 3 + 2] = v.toByte()
        }
        val out = File("build/doodle-preview.ppm")
        out.parentFile.mkdirs()
        out.outputStream().use { it.write("P6 $w ${size * 2} 255\n".toByteArray()); it.write(bytes) }
    }

    private fun over(src: Int, dst: Int): Int {
        val a = (src ushr 24) / 255f
        fun ch(shift: Int) = ((src shr shift and 255) * a + (dst shr shift and 255) * (1 - a)).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val PAPER = 0xF2EFE6
    }
}
