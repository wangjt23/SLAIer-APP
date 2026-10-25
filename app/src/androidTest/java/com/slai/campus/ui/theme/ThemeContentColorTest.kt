package com.slai.campus.ui.theme

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slai.campus.feature.attendance.AttendanceMonthDetailHeading
import com.slai.campus.feature.attendance.WeekHeader
import com.slai.campus.domain.attendance.AttendanceWeek
import com.slai.campus.ui.ScrollChromeTestActivity
import com.slai.campus.ui.components.ScrollChromePage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual Material text/icon drawing without relying on a Scaffold or Surface ancestor. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ThemeContentColorTest {
    @Test fun lightPageTextAndIconUseTheThemeInsteadOfAnAncestorColor() = withFixture(false) { probe ->
        assertPageContent(probe, verifyInitialLayout = true)
    }

    @Test fun darkPageTextAndIconUseTheThemeInsteadOfAnAncestorColor() = withFixture(true) { probe ->
        assertPageContent(probe, verifyInitialLayout = true)
    }

    @Test fun attendanceMonthAndWeekHeadingsActuallyDrawReadableTextInBothThemes() {
        for (dark in listOf(false, true)) withFixture(dark) { probe ->
            val expected = probe.expected.get()!!
            val bitmap = capture(probe)
            try {
                val heading = probe.heading.get()!!
                val count = countPixels(bitmap, heading, expected.content.toArgb())
                assertTrue("The actual attendance heading must draw its theme foreground (dark=$dark, matching pixels=$count)", count > 16)
                val weekCount = countPixels(bitmap, probe.weekHeading.get()!!, expected.content.toArgb())
                assertTrue("The actual weekly date range must draw its theme foreground (dark=$dark, matching pixels=$weekCount)", weekCount > 16)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun defaultMaterialComponentsKeepTheirOwnThemeColorPairs() {
        for (dark in listOf(false, true)) withFixture(dark) { probe ->
            val expected = probe.expected.get()!!
            assertEquals("A default Card must retain its own foreground", expected.card, probe.defaultCardText.get())
            assertEquals("A default Button must retain its own foreground", expected.button, probe.defaultButtonText.get())
            assertEquals("A default toolbar must retain its own foreground", expected.toolbar, probe.defaultToolbarText.get())
        }
    }

    @Test fun componentContentColorsStillOverrideThePageDefault() {
        for (dark in listOf(false, true)) withFixture(dark) { probe ->
            assertEquals("Card retains its own content color", CardContent, probe.cardText.get())
            assertEquals("Button retains its own content color", ButtonContent, probe.buttonText.get())
            assertEquals("Toolbar retains its own title color", ToolbarContent, probe.toolbarText.get())
        }
    }

    @Test fun runtimeThemeChangesUpdateDrawingWithoutResettingRememberedContent() = withFixture(false) { probe ->
        onMain { probe.increment!!() }
        awaitFrames()
        assertEquals(8, probe.remembered.get())
        assertPageContent(probe, verifyInitialLayout = true)
        for (dark in listOf(true, false)) {
            onMain { probe.dark.value = dark }
            awaitFrames()
            assertEquals(dark, probe.expected.get()!!.dark)
            assertEquals("Theme changes must preserve the page's remembered state", 8, probe.remembered.get())
            assertPageContent(probe)
            assertEquals(CardContent, probe.cardText.get())
            assertEquals(ButtonContent, probe.buttonText.get())
            assertEquals(ToolbarContent, probe.toolbarText.get())
        }
    }

    private fun assertPageContent(probe: Probe, verifyInitialLayout: Boolean = false) {
        val expected = probe.expected.get()!!
        if (verifyInitialLayout) {
            assertEquals("Bare Text must initially resolve its style from the active theme", expected.content, probe.bareText.get())
        }
        assertTrue("Page foreground must remain readable on the theme background", contrast(expected.content, expected.background) >= 4.5f)
        val bitmap = capture(probe)
        try {
            // Compose may repaint a text-color change without another onTextLayout callback.
            // Inspect the actual glyph pixels on every assertion, including runtime theme changes.
            val textPixels = countPixels(bitmap, probe.bareTextBounds.get()!!, expected.content.toArgb())
            assertTrue("Bare Text must draw the active theme foreground (dark=${expected.dark}, matching pixels=$textPixels)",
                textPixels > 16)
            // The center of the filled Home vector is opaque, so this checks Icon's actual tint,
            // including CompositionLocal inheritance and drawing, instead of a copied color value.
            val center = probe.icon.get()!!.center
            val actual = bitmap.getPixel(center.x.roundToInt(), center.y.roundToInt())
            assertTrue("Bare Icon must draw the theme foreground (dark=${expected.dark}, actual=${actual.toUInt().toString(16)})",
                colorsMatch(actual, expected.content.toArgb()))
        } finally {
            bitmap.recycle()
        }
    }

    private fun withFixture(dark: Boolean, check: (Probe) -> Unit) {
        val probe = Probe(dark)
        ActivityScenario.launch(ScrollChromeTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                probe.activity = activity
                activity.setContent { Fixture(probe) }
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (probe.icon.get() == null || probe.heading.get() == null || probe.weekHeading.get() == null ||
                probe.toolbarText.get() == null || probe.bareText.get() == null || probe.bareTextBounds.get() == null ||
                probe.defaultToolbarText.get() == null ||
                probe.remembered.get() == null) {
                assertTrue("Theme fixture did not lay out within five seconds", SystemClock.uptimeMillis() < deadline)
                awaitFrames()
            }
            awaitFrames()
            check(probe)
        }
    }

    @Composable
    private fun Fixture(probe: Probe) {
        // Black is also the platform fallback that exposed the regression after removing Scaffold.
        // A theme must establish a default even when its caller supplies an unrelated content color.
        CompositionLocalProvider(LocalContentColor provides Color.Black) {
            SlaiCampusTheme(darkTheme = probe.dark.value) {
                val scheme = MaterialTheme.colorScheme
                val defaultCard = CardDefaults.cardColors()
                val defaultButton = ButtonDefaults.buttonColors()
                val defaultToolbar = TopAppBarDefaults.topAppBarColors()
                var remembered by rememberSaveable { mutableIntStateOf(7) }
                SideEffect {
                    probe.expected.set(Expected(probe.dark.value, scheme.background, scheme.onBackground,
                        defaultCard.contentColor, defaultButton.contentColor, defaultToolbar.titleContentColor))
                    probe.increment = { remembered += 1 }
                }
                Box(Modifier.fillMaxSize().background(scheme.background)) {
                    ScrollChromePage(
                        canScrollBackward = false,
                        canScrollForward = false,
                        topBar = {
                            TopAppBar(
                                title = { Text("Toolbar", onTextLayout = { probe.toolbarText.set(it.layoutInput.style.color) }) },
                                colors = TopAppBarDefaults.topAppBarColors(titleContentColor = ToolbarContent),
                                windowInsets = WindowInsets(0)
                            )
                        }
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Unwrapped page text",
                                modifier = Modifier.onGloballyPositioned { probe.bareTextBounds.set(it.windowRect()) },
                                onTextLayout = { probe.bareText.set(it.layoutInput.style.color) })
                            Icon(Icons.Default.Home, contentDescription = null,
                                modifier = Modifier.size(48.dp).onGloballyPositioned { probe.icon.set(it.windowRect()) })
                            AttendanceMonthDetailHeading(Modifier.onGloballyPositioned { probe.heading.set(it.windowRect()) })
                            WeekHeader(
                                AttendanceWeek("Week 1", "2026-10-19,2026-10-25", emptyList()),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                                Modifier.onGloballyPositioned { probe.weekHeading.set(it.windowRect()) }
                            )
                            Card(colors = defaultCard) {
                                Text("Default card", Modifier.padding(8.dp), onTextLayout = { probe.defaultCardText.set(it.layoutInput.style.color) })
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = CardContent)) {
                                Text("Card", Modifier.padding(8.dp), onTextLayout = { probe.cardText.set(it.layoutInput.style.color) })
                            }
                            Button(onClick = {}, colors = ButtonDefaults.buttonColors(contentColor = ButtonContent)) {
                                Text("Button", onTextLayout = { probe.buttonText.set(it.layoutInput.style.color) })
                            }
                            Button(onClick = {}, colors = defaultButton) {
                                Text("Default button", onTextLayout = { probe.defaultButtonText.set(it.layoutInput.style.color) })
                            }
                            TopAppBar(
                                title = { Text("Default toolbar", onTextLayout = { probe.defaultToolbarText.set(it.layoutInput.style.color) }) },
                                colors = defaultToolbar,
                                windowInsets = WindowInsets(0)
                            )
                            Text("Remembered $remembered", onTextLayout = {
                                // Observe the actual child text: its subcomposition can update
                                // without rerunning the parent fixture's SideEffect.
                                probe.remembered.set(it.layoutInput.text.text.removePrefix("Remembered ").toInt())
                            })
                        }
                    }
                }
            }
        }
    }

    private fun capture(probe: Probe): Bitmap {
        awaitFrames()
        val bitmap = onMain {
            val view = probe.activity!!.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        }
        val completed = CountDownLatch(1)
        val result = AtomicReference<Int>()
        onMain {
            PixelCopy.request(probe.activity!!.window, bitmap, {
                result.set(it)
                completed.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Window capture timed out", completed.await(5, TimeUnit.SECONDS))
        assertEquals("Window capture must succeed", PixelCopy.SUCCESS, result.get())
        return bitmap
    }

    private fun countPixels(bitmap: Bitmap, rect: Rect, expected: Int): Int {
        var matches = 0
        for (y in rect.top.roundToInt().coerceAtLeast(0) until rect.bottom.roundToInt().coerceAtMost(bitmap.height)) {
            for (x in rect.left.roundToInt().coerceAtLeast(0) until rect.right.roundToInt().coerceAtMost(bitmap.width)) {
                if (colorsMatch(bitmap.getPixel(x, y), expected)) matches += 1
            }
        }
        return matches
    }

    private fun colorsMatch(actual: Int, expected: Int): Boolean =
        listOf(0, 8, 16, 24).all { shift -> abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 2 }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }

    private fun LayoutCoordinates.windowRect() = Rect(localToWindow(Offset.Zero), Size(size.width.toFloat(), size.height.toFloat()))

    private fun awaitFrames() {
        repeat(3) {
            val completed = CountDownLatch(1)
            onMain {
                Choreographer.getInstance().postFrameCallback {
                    Handler(Looper.getMainLooper()).post { completed.countDown() }
                }
            }
            assertTrue("Main thread did not draw a frame within two seconds", completed.await(2, TimeUnit.SECONDS))
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        Handler(Looper.getMainLooper()).post(task)
        return try { task.get(5, TimeUnit.SECONDS) } finally { if (!task.isDone) task.cancel(false) }
    }

    private data class Expected(val dark: Boolean, val background: Color, val content: Color,
        val card: Color, val button: Color, val toolbar: Color)

    private class Probe(dark: Boolean) {
        val dark = mutableStateOf(dark)
        var activity: ScrollChromeTestActivity? = null
        val expected = AtomicReference<Expected>()
        val bareText = AtomicReference<Color>()
        val bareTextBounds = AtomicReference<Rect>()
        val cardText = AtomicReference<Color>()
        val buttonText = AtomicReference<Color>()
        val toolbarText = AtomicReference<Color>()
        val defaultCardText = AtomicReference<Color>()
        val defaultButtonText = AtomicReference<Color>()
        val defaultToolbarText = AtomicReference<Color>()
        val icon = AtomicReference<Rect>()
        val heading = AtomicReference<Rect>()
        val weekHeading = AtomicReference<Rect>()
        val remembered = AtomicReference<Int>()
        var increment: (() -> Unit)? = null
    }

    private companion object {
        val CardContent = Color(0xFF00695C)
        val ButtonContent = Color(0xFFFFFF00)
        val ToolbarContent = Color(0xFF8D6E63)
    }
}
