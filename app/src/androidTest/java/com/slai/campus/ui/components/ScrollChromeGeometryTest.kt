package com.slai.campus.ui.components

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slai.campus.core.web.AutomaticLoginState
import com.slai.campus.navigation.Tab
import com.slai.campus.ui.ScrollChromeTestActivity
import com.slai.campus.ui.theme.SlaiCampusTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual touch dispatch and production page/navigation layouts; no accounts or remote data. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ScrollChromeGeometryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var heldDrag: Drag? = null

    @Test fun barsTranslateWhileViewportAndMeasuredHeightsStayFixed() = withFixture { probe ->
        val expanded = awaitGeometry(probe)
        swipeUp(probe)
        eventually { geometry(probe).fraction > .98f }
        val hidden = geometry(probe)
        assertFixedGeometry(expanded, hidden)
        assertTrue("Top bar must leave the viewport", hidden.top.bottom <= hidden.viewport.top + 1.5f)
        assertTrue("Navigation labels must leave the viewport", hidden.label.top >= hidden.viewport.bottom - 1.5f)
        assertEquals(probe.systemInsetPx, hidden.root.bottom - hidden.viewport.bottom, 1.5f)
    }

    @Test fun firstEffectiveDragMovesContentAndBothBarsTogether() = withFixture { probe ->
        val expanded = awaitGeometry(probe)
        val drag = Drag(expanded.viewport.center.x, expanded.viewport.center.y)
        drag.moveBy(-probe.dp(20f))
        eventually { geometry(probe).listOffset > 0 }
        val first = geometry(probe)
        assertTrue("Bars must move on the first effective content drag", first.fraction > 0f)
        assertTrue("A short drag must not instantly collapse the bars", first.fraction < .95f)
        assertSynchronizedTranslations(expanded, first)
        assertFixedGeometry(expanded, first)
        drag.moveBy(-probe.dp(16f))
        eventually { geometry(probe).fraction > first.fraction }
        val second = geometry(probe)
        assertTrue(second.listOffset > first.listOffset)
        assertSynchronizedTranslations(expanded, second)
        assertFixedGeometry(expanded, second)
        drag.release()
    }

    @Test fun realFlingContinuesMovingBarsInsideInertialFrames() = withFixture { probe ->
        val expanded = awaitGeometry(probe)
        val drag = Drag(expanded.viewport.center.x, expanded.viewport.top + expanded.viewport.height * .7f)
        repeat(3) { drag.moveBy(-probe.dp(12f)) }
        val released = geometry(probe)
        assertTrue("A brief drag must leave room for inertia", released.fraction in .01f..0.95f)
        drag.release()
        // Settle animations mutate state directly; only real nested-scroll SideEffect frames count.
        eventually { probe.inertialMotion.get() > 0 }
        assertTrue("Expected native inertial frames", probe.inertialFrames.get() > 0)
        assertFixedGeometry(expanded, geometry(probe))
    }

    @Test fun reversingAnActiveDragRestoresFromItsCurrentPositionWithoutJumping() = withFixture { probe ->
        val expanded = awaitGeometry(probe)
        // Keep this direction-change regression away from both edge-restoration policies. Those
        // policies intentionally reveal the controls at an edge and have separate tests below.
        scrollTo(probe, 10)
        val drag = Drag(expanded.viewport.center.x, expanded.viewport.center.y)
        drag.moveBy(-probe.dp(36f))
        eventually { geometry(probe).fraction > .1f }
        val before = geometry(probe)
        drag.moveBy(probe.dp(16f))
        eventually { geometry(probe).fraction < before.fraction }
        val reversed = geometry(probe)
        val diagnostic = "before=${before.fraction}/${before.listOffset}, " +
            "reversed=${reversed.fraction}/${reversed.listOffset}, " +
            "heights=${before.topHeight}/${before.bottomHeight}"
        assertTrue("Reverse movement must preserve partial progress: $diagnostic", reversed.fraction > 0f)
        // Native input resampling can consume a different distance than a single injected MOVE.
        // Compare with the list's actual travel, so a chrome jump still fails independently of
        // event coalescing, prediction and the direction-change hysteresis.
        val consumedReverse = before.listOffset - reversed.listOffset
        val maximumChange = (consumedReverse + 2f) / maxOf(before.topHeight, before.bottomHeight)
        assertTrue("Reversal must not jump to an endpoint (max=$maximumChange): $diagnostic",
            before.fraction - reversed.fraction <= maximumChange)
        assertTrue("The list must reverse too: $diagnostic", consumedReverse > 0f)
        assertSynchronizedTranslations(expanded, reversed)
        assertFixedGeometry(expanded, reversed)
        drag.release()
        eventually { !probe.listState!!.isScrollInProgress }
        assertTrue(geometry(probe).fraction in 0f..1f)
    }

    @Test fun hiddenNavigationLeavesNoStripAndListEndRestoresControlsAboveFinalRow() = withFixture { probe ->
        awaitGeometry(probe)
        swipeUp(probe)
        eventually { geometry(probe).fraction > .98f }
        val hidden = geometry(probe)
        assertEquals(hidden.app.bottom, hidden.viewport.bottom, 1.5f)
        assertTrue("Rows must continue behind hidden navigation instead of ending above a spacer",
            hidden.lastVisibleRow!!.bottom >= hidden.viewport.bottom - 1.5f)
        scrollTo(probe, 39)
        eventually { !probe.listState!!.canScrollForward && geometry(probe).fraction < .02f }
        val end = geometry(probe)
        val navigationTop = end.app.bottom - end.bottomHeight
        assertTrue("The final row must remain readable", end.lastVisibleRow!!.bottom <= navigationTop + 1.5f)
        assertEquals("Only the deliberate content margin should remain", probe.dp(16f), navigationTop - end.lastVisibleRow.bottom, 2f)
        assertFixedGeometry(hidden, end)
    }

    @Test fun continuingUpwardDragBringsNavigationBackWithTheFinalRowBeforeRelease() = withFixture { probe ->
        assertHeldEndNavigationClearance(probe)
    }

    @Test fun themeChangesKeepPartialChromeAndViewportFixedDuringAnActiveDrag() = withFixture { probe ->
        val expanded = awaitGeometry(probe)
        scrollTo(probe, 10)
        val drag = Drag(expanded.viewport.center.x, expanded.viewport.center.y)
        drag.moveBy(-probe.dp(36f))
        eventually { geometry(probe).fraction in .1f..0.9f }
        val partial = geometry(probe)
        for (darkMode in listOf(true, false)) {
            changeTheme(probe, darkMode)
            val switched = geometry(probe)
            assertThemeGeometryUnchanged(partial, switched)
            assertSynchronizedTranslations(expanded, switched)
        }
        drag.moveBy(probe.dp(16f))
        eventually { geometry(probe).fraction < partial.fraction }
        val reversed = geometry(probe)
        assertTrue("The list must still respond immediately after theme changes", reversed.listOffset < partial.listOffset)
        assertTrue("Reversing after theme changes must preserve partial progress", reversed.fraction > 0f)
        assertSynchronizedTranslations(expanded, reversed)
        assertFixedGeometry(expanded, reversed)
        drag.release()
    }

    @Test fun themeChangesKeepHiddenNavigationGapFreeAndDarkHeldEndKeepsItsMargin() = withFixture(darkMode = true) { probe ->
        awaitGeometry(probe)
        swipeUp(probe)
        eventually { geometry(probe).fraction > .98f && !probe.listState!!.isScrollInProgress }
        val hidden = geometry(probe)
        for (darkMode in listOf(false, true)) {
            changeTheme(probe, darkMode)
            val switched = geometry(probe)
            assertThemeGeometryUnchanged(hidden, switched)
            assertEquals("Theme changes must not insert an empty navigation strip", switched.app.bottom, switched.viewport.bottom, 1.5f)
            assertTrue("Rows must still reach behind hidden navigation after a theme change",
                switched.lastVisibleRow!!.bottom >= switched.viewport.bottom - 1.5f)
        }
        // Repeat the real held end-of-list gesture under the production dark theme. Its final
        // 16 dp margin is checked before ACTION_UP, when overscroll is still actively held.
        assertHeldEndNavigationClearance(probe)
    }

    private fun assertHeldEndNavigationClearance(probe: Probe) {
        val expanded = awaitGeometry(probe)
        Log.d("ScrollChromeTest", "held-tail: initial geometry ready")
        scrollTo(probe, 39)
        Log.d("ScrollChromeTest", "held-tail: scrollTo final row completed")
        eventually { !probe.listState!!.canScrollForward && geometry(probe).fraction < .02f }
        scrollBy(probe, -expanded.bottomHeight * 2f)
        Log.d("ScrollChromeTest", "held-tail: moved back two navigation heights; remaining=${endDistance(probe)}")
        val drag = Drag(expanded.viewport.center.x, expanded.viewport.top + expanded.viewport.height * .8f)
        var halfway: Geometry? = null
        repeat(40) {
            if (halfway == null) {
                drag.moveBy(-probe.dp(8f))
                awaitUiFrame()
                val remaining = endDistance(probe)
                Log.d("ScrollChromeTest", "held-tail: drag[$it], remaining=$remaining, fraction=${geometry(probe).fraction}")
                if (remaining in expanded.bottomHeight * .2f..expanded.bottomHeight * .75f) {
                    halfway = geometry(probe)
                }
            }
        }
        val returning = checkNotNull(halfway) { "The held drag never entered the last navigation-height of scrolling" }
        assertTrue("The bar should return continuously while the finger still moves upward", returning.fraction in .05f..0.95f)
        val visibleNavigationTop = returning.app.bottom - returning.bottomHeight * (1f - returning.fraction)
        assertEquals("The final row and returning navigation must keep only their content margin",
            probe.dp(16f), visibleNavigationTop - returning.lastVisibleRow!!.bottom, 2f)
        assertFixedGeometry(expanded, returning)
        Log.d("ScrollChromeTest", "held-tail: intermediate gap verified; continue to end")
        repeat(20) {
            if (endDistance(probe) > .5f) {
                drag.moveBy(-probe.dp(8f))
                awaitUiFrame()
            }
        }
        eventually { !probe.listState!!.canScrollForward }
        val endBeforeRelease = geometry(probe)
        assertEquals("No empty navigation-height strip may appear even before release", 0f, endBeforeRelease.fraction, .02f)
        assertEquals(probe.dp(16f), endBeforeRelease.app.bottom - endBeforeRelease.bottomHeight -
            endBeforeRelease.lastVisibleRow!!.bottom, 2f)
        Log.d("ScrollChromeTest", "held-tail: gap verified at end before release")
        drag.release()
    }

    @Test fun gestureAndThreeButtonAreasStayReservedWhenBarsAreHidden() {
        for (systemInsetDp in listOf(24, 48)) withFixture(systemInsetDp = systemInsetDp) { probe ->
            val expanded = awaitGeometry(probe)
            assertEquals(probe.systemInsetPx, expanded.root.bottom - expanded.viewport.bottom, 1.5f)
            swipeUp(probe)
            eventually { geometry(probe).fraction > .98f }
            val hidden = geometry(probe)
            assertEquals(probe.systemInsetPx, hidden.root.bottom - hidden.viewport.bottom, 1.5f)
            assertFixedGeometry(expanded, hidden)
        }
    }

    @Test fun sideSystemInsetsRemainOutsideTheFixedViewport() = withFixture(systemInsetDp = 0, sideInsetDp = 30) { probe ->
        val expanded = awaitGeometry(probe)
        assertEquals(probe.dp(30f), expanded.viewport.left - expanded.root.left, 1.5f)
        assertEquals(probe.dp(30f), expanded.root.right - expanded.viewport.right, 1.5f)
        assertEquals(expanded.root.bottom, expanded.viewport.bottom, 1.5f)
        swipeUp(probe)
        eventually { geometry(probe).fraction > .98f }
        val hidden = geometry(probe)
        assertEquals(expanded.viewport.left, hidden.viewport.left, 1.5f)
        assertEquals(expanded.viewport.right, hidden.viewport.right, 1.5f)
        assertFixedGeometry(expanded, hidden)
    }

    @Test fun shortContentAndProgrammaticScrollDoNotHideControls() {
        withFixture(itemCount = 1) { probe ->
            val expanded = awaitGeometry(probe)
            swipeUp(probe)
            assertEquals(0f, geometry(probe).fraction, .001f)
            assertFixedGeometry(expanded, geometry(probe))
        }
        withFixture { probe ->
            val expanded = awaitGeometry(probe)
            scrollTo(probe, 15, animated = true)
            assertEquals(0f, geometry(probe).fraction, .001f)
            assertFixedGeometry(expanded, geometry(probe))
        }
    }

    @Test fun homeAndScheduleKeepControlsVisibleWhileOverflowContentFollowsRealDrags() {
        for (tab in listOf(Tab.HOME, Tab.SCHEDULE)) withFixture(tab = tab) { probe ->
            val expanded = awaitGeometry(probe)
            assertTrue("$tab fixture must overflow so this tests scrolling, not a short list", probe.listState!!.canScrollForward)
            val drag = Drag(expanded.viewport.center.x, expanded.viewport.center.y)
            drag.moveBy(-probe.dp(36f))
            eventually { geometry(probe).listOffset > expanded.listOffset }
            val first = geometry(probe)
            assertPinnedControls(expanded, first)
            drag.moveBy(-probe.dp(64f))
            eventually { geometry(probe).listOffset > first.listOffset }
            assertPinnedControls(expanded, geometry(probe))
            drag.release()
            eventually { !probe.listState!!.isScrollInProgress }
            assertPinnedControls(expanded, geometry(probe))
        }
    }

    @Test fun largeFontHomeAndScheduleReachFinalRowByTouchWithoutNavigationGap() {
        for (tab in listOf(Tab.HOME, Tab.SCHEDULE)) withFixture(tab = tab, itemCount = 14, fontScale = 2f) { probe ->
            val expanded = awaitGeometry(probe)
            assertTrue("Large-font content must remain scrollable on $tab", probe.listState!!.canScrollForward)
            swipeToEnd(probe)
            val end = geometry(probe)
            assertTrue("The real gesture must move content on $tab", end.listOffset > expanded.listOffset)
            assertEquals("The last row must be reachable on $tab", 13,
                onMain { probe.listState!!.layoutInfo.visibleItemsInfo.last().index })
            assertPinnedControls(expanded, end)
            assertEquals("The final row must keep only the intended content margin on $tab", probe.dp(16f),
                end.app.bottom - end.bottomHeight - end.lastVisibleRow!!.bottom, 2f)
            assertEquals("The system navigation area must remain reserved on $tab", probe.systemInsetPx,
                end.root.bottom - end.viewport.bottom, 1.5f)
        }
    }

    @Test fun attendanceAndSettingsStillHideTogetherAndReturnAtTheRealGestureListEnd() {
        for (tab in listOf(Tab.ATTENDANCE, Tab.SETTINGS)) withFixture(tab = tab) { probe ->
            val expanded = awaitGeometry(probe)
            swipeUp(probe)
            eventually { geometry(probe).fraction > .98f }
            val hidden = geometry(probe)
            assertTrue("The real gesture must move content on $tab", hidden.listOffset > expanded.listOffset)
            assertSynchronizedTranslations(expanded, hidden)
            assertFixedGeometry(expanded, hidden)
            swipeToEnd(probe)
            eventually { geometry(probe).fraction == 0f }
            val end = geometry(probe)
            assertPinnedControls(expanded, end)
            assertEquals("The returning navigation must leave no extra bottom space on $tab", probe.dp(16f),
                end.app.bottom - end.bottomHeight - end.lastVisibleRow!!.bottom, 2f)
        }
    }

    @Test fun largerFontsUseMeasuredNavigationHeightWithoutClippingOrResizingViewport() = withFixture(fontScale = 2f) { probe ->
        val expanded = awaitGeometry(probe)
        assertTrue(expanded.label.top >= expanded.bottom.top)
        assertTrue(expanded.label.bottom <= expanded.bottom.bottom + 1f)
        assertEquals(expanded.bottom.height, expanded.bottomHeight, 1.5f)
        swipeUp(probe)
        eventually { geometry(probe).fraction > .98f }
        assertFixedGeometry(expanded, geometry(probe))
    }

    @Test fun loginNoticeFloatsAboveNavigationWithoutReservingAnEmptyStrip() = withFixture { probe ->
        val expanded = awaitGeometry(probe)
        assertEquals(0f, expanded.notice.height, .5f)
        onMain { probe.noticeState.value = AutomaticLoginState.COOLDOWN }
        eventually { geometry(probe).notice.height > 0 }
        val shown = geometry(probe)
        assertTrue(shown.notice.bottom <= shown.app.bottom - shown.bottomHeight + 1.5f)
        assertFixedGeometry(expanded, shown)
        onMain { probe.noticeState.value = AutomaticLoginState.IDLE }
        eventually { geometry(probe).notice.height == 0f }
        assertFixedGeometry(expanded, geometry(probe))
        swipeUp(probe)
        eventually { geometry(probe).fraction > .98f }
        onMain { probe.noticeState.value = AutomaticLoginState.NETWORK_UNAVAILABLE }
        eventually { geometry(probe).notice.height > 0 }
        val hidden = geometry(probe)
        assertTrue(hidden.notice.bottom <= hidden.app.bottom + 1.5f)
        assertFixedGeometry(expanded, hidden)
        eventually { geometry(probe).notice.height == 0f }
        assertFixedGeometry(expanded, geometry(probe))
    }

    private fun assertSynchronizedTranslations(expanded: Geometry, current: Geometry) {
        val topProgress = (expanded.top.top - current.top.top) / current.topHeight
        val bottomProgress = (current.label.top - expanded.label.top) / current.bottomHeight
        assertEquals("Top bar must use shared progress", current.fraction, topProgress, .025f)
        assertEquals("Bottom bar must use shared progress", current.fraction, bottomProgress, .025f)
    }

    private fun assertFixedGeometry(before: Geometry, after: Geometry) {
        assertEquals("List viewport must not move", before.viewport.top, after.viewport.top, 1.5f)
        assertEquals("List viewport must not resize", before.viewport.bottom, after.viewport.bottom, 1.5f)
        assertEquals("Top bar must keep its measured height", before.top.height, after.top.height, 1.5f)
        assertEquals("Navigation must keep its measured height", before.bottom.height, after.bottom.height, 1.5f)
    }

    private fun assertPinnedControls(expanded: Geometry, current: Geometry) {
        assertEquals("Pinned controls must remain fully visible", 0f, current.fraction, .001f)
        assertEquals("The toolbar must stay in place while content scrolls", expanded.top.top, current.top.top, 1.5f)
        assertEquals("Navigation labels must stay in place while content scrolls", expanded.label.top, current.label.top, 1.5f)
        assertFixedGeometry(expanded, current)
    }

    private fun assertThemeGeometryUnchanged(before: Geometry, after: Geometry) {
        assertFixedGeometry(before, after)
        assertEquals("Theme changes must preserve shared scroll progress", before.fraction, after.fraction, .001f)
        assertEquals("Theme changes must preserve list position", before.listOffset, after.listOffset, .5f)
        assertEquals("Theme changes must preserve viewport left edge", before.viewport.left, after.viewport.left, 1.5f)
        assertEquals("Theme changes must preserve viewport right edge", before.viewport.right, after.viewport.right, 1.5f)
        assertEquals("Theme changes must preserve top bar position", before.top.top, after.top.top, 1.5f)
        assertEquals("Theme changes must preserve navigation position", before.bottom.top, after.bottom.top, 1.5f)
        assertEquals("Theme changes must preserve navigation label position", before.label.top, after.label.top, 1.5f)
        assertEquals("Theme changes must preserve measured top bar height", before.topHeight, after.topHeight, 1.5f)
        assertEquals("Theme changes must preserve measured navigation height", before.bottomHeight, after.bottomHeight, 1.5f)
        assertEquals("Theme changes must preserve the reserved system area", before.root.bottom - before.viewport.bottom,
            after.root.bottom - after.viewport.bottom, 1.5f)
    }

    private fun changeTheme(probe: Probe, darkMode: Boolean) {
        onMain { probe.darkMode.value = darkMode }
        eventually { probe.renderedDarkMode == darkMode }
    }

    private fun awaitGeometry(probe: Probe): Geometry {
        eventually { probe.motion != null && probe.viewport.get() != null && probe.top.get() != null &&
            probe.bottom.get() != null && probe.label.get() != null && probe.notice.get() != null &&
            probe.motion!!.topBarHeightPx > 0 && probe.motion!!.bottomBarHeightPx > 0 }
        return geometry(probe)
    }

    private fun geometry(probe: Probe): Geometry {
        lateinit var snapshot: Geometry
        onMain {
            fun LayoutCoordinates.rect() = Rect(localToWindow(Offset.Zero), Size(size.width.toFloat(), size.height.toFloat()))
            fun AtomicReference<LayoutCoordinates?>.rect() = get()!!.rect()
            val lastIndex = probe.listState!!.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val last = lastIndex?.let { probe.rows[it] }?.takeIf { it.isAttached }?.rect()
            // boundsInWindow clips translated children. Keep actual measured size to distinguish
            // pure translation from the old navigation/viewport resizing implementation.
            snapshot = Geometry(probe.root.rect(), probe.app.rect(), probe.viewport.rect(), probe.top.rect(),
                probe.bottom.rect(), probe.label.rect(), probe.notice.rect(), probe.motion!!.hiddenFraction,
                probe.motion!!.topBarHeightPx, probe.motion!!.bottomBarHeightPx,
                probe.listState!!.firstVisibleItemIndex * probe.dp(64f) + probe.listState!!.firstVisibleItemScrollOffset, last)
        }
        return snapshot
    }

    private fun scrollTo(probe: Probe, index: Int, animated: Boolean = false) = performScroll(probe) {
        if (animated) probe.listState!!.animateScrollToItem(index) else probe.listState!!.scrollToItem(index)
    }

    private fun scrollBy(probe: Probe, distance: Float) = performScroll(probe) {
        probe.listState!!.scrollBy(distance)
        Unit
    }

    private fun performScroll(probe: Probe, operation: suspend () -> Unit) {
        // Real UI animation needs the composition's frame clock, but waiting for it is bounded.
        val scroll = onMain { probe.animationScope!!.async { operation() } }
        try {
            runBlocking { withTimeout(5_000) { scroll.await() } }
        } finally {
            scroll.cancel()
        }
        awaitUiFrame()
    }

    private fun endDistance(probe: Probe): Float = onMain { probe.endDistance!!() }

    private fun withFixture(itemCount: Int = 40, fontScale: Float = 1f, systemInsetDp: Int = 24,
        sideInsetDp: Int = 0, darkMode: Boolean = false, tab: Tab = Tab.ATTENDANCE, check: (Probe) -> Unit) {
        val probe = Probe(darkMode)
        ActivityScenario.launch(ScrollChromeTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { Fixture(probe, itemCount, fontScale, systemInsetDp, sideInsetDp, tab) } }
            try {
                check(probe)
            } finally {
                // An assertion can fail while ACTION_DOWN is still held. End that stream before
                // closing the activity so it cannot contaminate the next instrumentation fixture.
                heldDrag?.release()
            }
        }
    }

    @Composable
    private fun Fixture(probe: Probe, itemCount: Int, fontScale: Float, systemInsetDp: Int, sideInsetDp: Int, tab: Tab) {
        val deviceDensity = LocalDensity.current
        val density = remember(deviceDensity, fontScale) { Density(deviceDensity.density, fontScale) }
        CompositionLocalProvider(LocalDensity provides density) {
            SlaiCampusTheme(darkTheme = probe.darkMode.value) {
                val motion = rememberScrollChromeMotionState()
                val list = rememberLazyListState()
                val endDistance = rememberScrollEndDistance(list)
                val animationScope = rememberCoroutineScope()
                val observer = remember(motion, probe) {
                    object : NestedScrollConnection {
                        var previous = 0f
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source == NestedScrollSource.SideEffect) {
                                probe.inertialFrames.incrementAndGet()
                                if (motion.hiddenFraction > previous + .001f) probe.inertialMotion.incrementAndGet()
                            }
                            previous = motion.hiddenFraction
                            return Offset.Zero
                        }
                    }
                }
                SideEffect {
                    probe.motion = motion
                    probe.listState = list
                    probe.density = density.density
                    probe.systemInsetPx = with(density) { systemInsetDp.dp.toPx() }
                    probe.animationScope = animationScope
                    probe.endDistance = endDistance
                    probe.renderedDarkMode = probe.darkMode.value
                }
                CompositionLocalProvider(LocalScrollChromeMotion provides motion,
                    LocalScrollChromeEnabled provides tab.hidesChromeOnScroll) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { probe.root.set(it) }) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(
                            left = sideInsetDp.dp, right = sideInsetDp.dp, bottom = systemInsetDp.dp))) {
                            Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { probe.app.set(it) }) {
                                ScrollChromePage(canScrollBackward = list.canScrollBackward, canScrollForward = list.canScrollForward,
                                    distanceToEndPx = endDistance,
                                    modifier = Modifier.nestedScroll(observer), topBar = {
                                        TopAppBar(title = { Text("Local content") }, windowInsets = WindowInsets(0),
                                            modifier = Modifier.onGloballyPositioned { probe.top.set(it) })
                                    }) { padding ->
                                    LazyColumn(state = list, contentPadding = padding,
                                        modifier = Modifier.fillMaxSize().onGloballyPositioned { probe.viewport.set(it) }) {
                                        items(itemCount) { index ->
                                            Box(Modifier.fillMaxWidth().height(64.dp).onGloballyPositioned { probe.rows[index] = it }) {
                                                Text("Row $index")
                                            }
                                        }
                                    }
                                }
                                ScrollingNavigationBar(state = motion,
                                    modifier = Modifier.align(Alignment.BottomCenter).onGloballyPositioned { probe.bottom.set(it) }) {
                                    repeat(4) { index ->
                                        NavigationBarItem(selected = index == 0, onClick = {},
                                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                            label = { Text("导航", Modifier.onGloballyPositioned { probe.label.set(it) }) })
                                    }
                                }
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer {
                                    translationY = -motion.bottomBarHeightPx * (1f - motion.hiddenFraction)
                                }.onGloballyPositioned { probe.notice.set(it) }) {
                                    AutomaticLoginNotice(probe.noticeState.value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun swipeUp(probe: Probe) {
        val viewport = geometry(probe).viewport
        val drag = Drag(viewport.center.x, viewport.top + viewport.height * .8f)
        repeat(20) { drag.moveBy(-viewport.height * .6f / 20) }
        drag.release()
        awaitUiFrame()
    }

    private fun swipeToEnd(probe: Probe) {
        repeat(10) {
            if (onMain { probe.listState!!.canScrollForward }) swipeUp(probe)
        }
        eventually { !probe.listState!!.canScrollForward && !probe.listState!!.isScrollInProgress }
    }

    private inner class Drag(private val x: Float, private var y: Float) {
        private val start = SystemClock.uptimeMillis()
        private var released = false
        init {
            check(heldDrag == null) { "A previous test drag was not released" }
            heldDrag = this
            send(MotionEvent.ACTION_DOWN)
        }
        fun moveBy(delta: Float) {
            SystemClock.sleep(16)
            y += delta
            send(MotionEvent.ACTION_MOVE)
        }
        fun release() {
            if (released) return
            released = true
            try { send(MotionEvent.ACTION_UP) } finally { if (heldDrag === this) heldDrag = null }
        }
        private fun send(action: Int) {
            // Frame/geometry waits take real time. Artificial 16 ms event timestamps would lag
            // behind dispatch and make Android's native resampling predict unrelated distances.
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also {
                instrumentation.sendPointerSync(it)
                it.recycle()
            }
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            awaitUiFrame()
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue("Compose geometry did not reach expected state", condition())
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        Handler(Looper.getMainLooper()).post(task)
        return try { task.get(5, TimeUnit.SECONDS) } finally { if (!task.isDone) task.cancel(false) }
    }

    /** Wait for one traversal, not a completely idle queue: held overscroll can keep frames active. */
    private fun awaitUiFrame() {
        val frame = CountDownLatch(1)
        onMain {
            Choreographer.getInstance().postFrameCallback {
                Handler(Looper.getMainLooper()).post { frame.countDown() }
            }
        }
        assertTrue("Main thread did not complete a UI frame within two seconds", frame.await(2, TimeUnit.SECONDS))
    }

    private data class Geometry(
        val root: Rect, val app: Rect, val viewport: Rect, val top: Rect, val bottom: Rect,
        val label: Rect, val notice: Rect, val fraction: Float, val topHeight: Float,
        val bottomHeight: Float, val listOffset: Float, val lastVisibleRow: Rect?
    )
    private class Probe(initialDarkMode: Boolean = false) {
        val root = AtomicReference<LayoutCoordinates?>()
        val app = AtomicReference<LayoutCoordinates?>()
        val bottom = AtomicReference<LayoutCoordinates?>()
        val top = AtomicReference<LayoutCoordinates?>()
        val viewport = AtomicReference<LayoutCoordinates?>()
        val label = AtomicReference<LayoutCoordinates?>()
        val notice = AtomicReference<LayoutCoordinates?>()
        val rows = mutableMapOf<Int, LayoutCoordinates>() // Main-thread writes and snapshots only.
        val noticeState = mutableStateOf(AutomaticLoginState.IDLE)
        val darkMode = mutableStateOf(initialDarkMode)
        @Volatile var renderedDarkMode = initialDarkMode
        @Volatile var motion: ScrollChromeMotionState? = null
        @Volatile var listState: LazyListState? = null
        @Volatile var density = 1f
        @Volatile var systemInsetPx = 0f
        @Volatile var animationScope: CoroutineScope? = null
        @Volatile var endDistance: (() -> Float)? = null
        val inertialFrames = AtomicInteger()
        val inertialMotion = AtomicInteger()
        fun dp(value: Float) = value * density
    }
}
