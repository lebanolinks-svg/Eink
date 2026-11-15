package app.inkphone

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real screens to PNG files in build/screens so layout can be reviewed without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class ScreenshotTest {
    private fun shoot(activity: Activity, name: String) {
        val root: View = activity.window.decorView
        val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        root.measure(w, View.MeasureSpec.makeMeasureSpec(9000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 9000)
        val bmp = Bitmap.createBitmap(1080, 9000, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("build/screens/$name.png")
        out.parentFile.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shoot(activity, "home")
    }

    @Test
    fun settings() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shoot(activity, "settings")
    }
}
