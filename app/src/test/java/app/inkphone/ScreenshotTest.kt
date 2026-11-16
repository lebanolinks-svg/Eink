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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrialTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun daysLeftWhenStarted(daysAgo: Double): Int {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        Prefs(context).sp.edit().putLong("trial_start", System.currentTimeMillis() - (daysAgo * day).toLong()).commit()
        return Pro.trialDaysLeft(context)
    }

    @Test
    fun trialCountsDownAndEnds() {
        org.junit.Assert.assertEquals(7, daysLeftWhenStarted(0.0))
        org.junit.Assert.assertEquals(1, daysLeftWhenStarted(6.5))
        org.junit.Assert.assertEquals(0, daysLeftWhenStarted(7.01))
        org.junit.Assert.assertEquals(0, daysLeftWhenStarted(30.0))
        // a clock set far back must not hand out a longer trial
        org.junit.Assert.assertEquals(0, daysLeftWhenStarted(-3.0))
    }

    @Test
    fun purchaseOutlivesTheTrial() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        daysLeftWhenStarted(30.0)
        Pro.setOwned(context, true)
        org.junit.Assert.assertTrue(Pro.owned(context))
        org.junit.Assert.assertEquals(Prefs(context).iconStyle, Pro.iconStyle(context, Prefs(context)))
    }
}
