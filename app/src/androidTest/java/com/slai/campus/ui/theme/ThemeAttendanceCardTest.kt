package com.slai.campus.ui.theme

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slai.campus.core.common.AppLanguage
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.domain.attendance.PunchDirection
import com.slai.campus.domain.attendance.PunchSession
import com.slai.campus.feature.attendance.AttendanceUiState
import com.slai.campus.feature.attendance.TodayCard
import com.slai.campus.ui.LocalizedApp
import com.slai.campus.ui.ScrollChromeTestActivity
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the actual attendance card's laid-out text; no parallel copy of its UI or color logic. */
@RunWith(AndroidJUnit4::class)
class ThemeAttendanceCardTest {
    @Test fun cardTextUsesItsMatchingForegroundInBothThemesAndAllThreeBackgrounds() {
        for (dark in listOf(false, true)) for (variant in Variant.entries) {
            val expected = AtomicReference<Pair<Color, Color>>()
            val state = attendanceState(variant)
            ActivityScenario.launch(ScrollChromeTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        LocalizedApp(AppLanguage.CHINESE, "zh-CN") {
                            SlaiCampusTheme(darkTheme = dark) {
                                val colors = MaterialTheme.colorScheme
                                val pair = when (variant) {
                                    Variant.EMPTY -> colors.onSurfaceVariant to colors.surfaceVariant
                                    Variant.IN_PROGRESS -> colors.onSecondaryContainer to colors.secondaryContainer
                                    Variant.GOAL_REACHED -> colors.onPrimaryContainer to colors.primaryContainer
                                }
                                SideEffect { expected.set(pair) }
                                Box(Modifier.fillMaxSize().background(colors.background).padding(16.dp)) {
                                    TodayCard(state)
                                }
                            }
                        }
                    }
                }
                awaitFrames()
                scenario.onActivity { activity ->
                    val root = requireNotNull(findRoot(activity.window.decorView))
                    val layouts = root.semanticsOwner.getAllSemanticsNodes(false).flatMap { node ->
                        val results = mutableListOf<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                        results
                    }
                    assertTrue("The real card must expose laid-out text", layouts.size >= 4)
                    val (foreground, background) = requireNotNull(expected.get())
                    val contrast = (maxOf(foreground.luminance(), background.luminance()) + .05f) /
                        (minOf(foreground.luminance(), background.luminance()) + .05f)
                    assertTrue("The card's text pair must meet 4.5:1 (dark=$dark, variant=$variant)", contrast >= 4.5f)
                    for (layout in layouts) {
                        assertEquals("Card text '${layout.layoutInput.text}' must match its background (dark=$dark, variant=$variant)",
                            foreground, layout.layoutInput.style.color)
                    }
                    if (variant != Variant.EMPTY) {
                        assertEquals("Both the heading and open session must report presence", 2,
                            layouts.count { it.layoutInput.text.text == "在馆中" })
                    }
                }
            }
        }
    }

    private fun attendanceState(variant: Variant): AttendanceUiState {
        val date = LocalDate.of(2026, 10, 24)
        val now = date.atTime(if (variant == Variant.GOAL_REACHED) 18 else 12, 0)
        if (variant == Variant.EMPTY) return AttendanceUiState(todayDate = date, nowDateTime = now)
        val from = date.atTime(10, 0)
        return AttendanceUiState(
            todayDate = date,
            nowDateTime = now,
            today = AttendanceRecord(date, firstSwipe = from.toLocalTime(), enterCount = 1, exitCount = 0,
                durationText = "0", dayType = "工作日", qualified = false),
            todayDaily = DailyAttendance(date, listOf(PunchSession(from)),
                listOf(AttendancePunch("synthetic-theme-test", from, PunchDirection.IN, place = "教学楼"))),
            locale = Locale.SIMPLIFIED_CHINESE
        )
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitFrames() {
        repeat(4) {
            val complete = CountDownLatch(1)
            onMain { Choreographer.getInstance().postFrameCallback { complete.countDown() } }
            assertTrue("Card frame timed out", complete.await(3, TimeUnit.SECONDS))
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        Handler(Looper.getMainLooper()).post(task)
        return try { task.get(5, TimeUnit.SECONDS) } finally { if (!task.isDone) task.cancel(false) }
    }

    private enum class Variant { EMPTY, IN_PROGRESS, GOAL_REACHED }
}
