package com.slai.campus.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollChromeMotionTest {
    @Test fun firstMovementFollowsTheListWithoutWaitingForAThreshold() {
        val drag = ScrollChromeDrag(8f)
        assertEquals(0.1f, drag.move(-10f, 100f), 0.0001f)
        assertEquals(0.25f, drag.move(-15f, 100f), 0.0001f)
    }

    @Test fun tinyReversalsDoNotMakeTheBarsFlicker() {
        val drag = ScrollChromeDrag(8f)
        drag.move(-50f, 100f)
        assertEquals(0.5f, drag.move(3f, 100f), 0.0001f)
        assertEquals(0.5f, drag.move(4f, 100f), 0.0001f)
        assertEquals(0.46f, drag.move(5f, 100f), 0.0001f)
        assertEquals(-1, drag.direction)
    }

    @Test fun originalDirectionDiscardsAnUnfinishedReversal() {
        val drag = ScrollChromeDrag(8f)
        drag.move(-50f, 100f)
        drag.move(7f, 100f)
        drag.move(-10f, 100f)
        assertEquals(0.6f, drag.move(7f, 100f), 0.0001f)
    }

    @Test fun positionRemainsInsideTheMeasuredTravel() {
        val drag = ScrollChromeDrag(8f)
        assertEquals(1f, drag.move(-1_000f, 80f), 0f)
        assertEquals(0f, drag.move(1_000f, 80f), 0f)
    }

    @Test fun missingOrInvalidMeasurementsDoNotMoveTheBars() {
        val drag = ScrollChromeDrag(8f)
        assertEquals(0f, drag.move(-10f, 0f), 0f)
        assertEquals(0f, drag.move(Float.NaN, 80f), 0f)
        assertEquals(0f, drag.move(-10f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun bothBarsShareOneFractionUsingTheLargerMeasuredHeight() = runTest {
        val motion = motion()
        motion.updateTopBarHeight(64f)
        motion.updateBottomBarHeight(100f)
        motion.frame(-25f)
        assertEquals(0.25f, motion.hiddenFraction, 0.0001f)
        // Font/height changes retain the position and use the newly measured travel thereafter.
        motion.updateBottomBarHeight(200f)
        motion.frame(-50f)
        assertEquals(0.5f, motion.hiddenFraction, 0.0001f)
    }

    @Test fun toolbarNeverConsumesListDistanceOrFlingVelocity() = runTest {
        val motion = motion()
        val connection = motion.nestedScrollConnection
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -20f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, -500f)))
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity(0f, -500f), Velocity.Zero))
    }

    @Test fun unconsumedUpwardSwipeDoesNotHideControls() = runTest {
        val motion = motion()
        motion.nestedScrollConnection.onPostScroll(Offset.Zero, Offset(0f, -200f), NestedScrollSource.UserInput)
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun partialSlowDragSettlesToItsNearestEndWithoutOvershoot() = runTest {
        val motion = motion()
        motion.frame(-30f)
        motion.nestedScrollConnection.onPreFling(Velocity.Zero)
        motion.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
        var previous = motion.hiddenFraction
        repeat(25) {
            advanceTimeBy(16)
            runCurrent()
            assertTrue(motion.hiddenFraction in 0f..previous)
            previous = motion.hiddenFraction
        }
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun clearUpwardReleaseCompletesAHideEvenBeforeHalfway() = runTest {
        val motion = motion()
        motion.frame(-30f)
        motion.nestedScrollConnection.onPreFling(Velocity(0f, -1_000f))
        motion.nestedScrollConnection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1f, motion.hiddenFraction, 0f)
    }

    @Test fun reachingTheEndRevealsWithoutWaitingForFlingCompletion() = runTest {
        val motion = motion()
        motion.frame(-100f)
        motion.updateContentBounds(canScroll = true, atStart = false, atEnd = true)
        advanceTimeBy(64)
        runCurrent()
        assertTrue(motion.hiddenFraction < 1f)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0f, motion.hiddenFraction, 0f)
        // A high-velocity release must not hide bars over the terminal content padding again.
        motion.nestedScrollConnection.onPreFling(Velocity(0f, -1_000f))
        motion.nestedScrollConnection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        advanceTimeBy(500)
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun repeatedBoundaryFramesDoNotRestartTheReveal() = runTest {
        val motion = motion()
        motion.frame(-100f)
        motion.updateContentBounds(canScroll = true, atStart = false, atEnd = true)
        repeat(30) {
            motion.nestedScrollConnection.onPostScroll(Offset.Zero, Offset(0f, -5f), NestedScrollSource.SideEffect)
            advanceTimeBy(16)
            runCurrent()
        }
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun repeatedUserOverscrollFramesDoNotCancelAndRestartTheReveal() = runTest {
        val motion = motion()
        motion.frame(-100f)
        motion.updateContentBounds(canScroll = true, atStart = false, atEnd = true)
        repeat(30) {
            motion.nestedScrollConnection.onPreScroll(Offset(0f, -5f), NestedScrollSource.UserInput)
            motion.nestedScrollConnection.onPostScroll(Offset.Zero, Offset(0f, -5f), NestedScrollSource.UserInput)
            advanceTimeBy(16)
            runCurrent()
        }
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun trailingBarHeightRevealsInLockstepWithTheRemainingContentPadding() = runTest {
        val motion = motion()
        var remaining = 100f
        motion.updateEndDistanceProvider { remaining }
        motion.frame(-100f)
        assertEquals(1f, motion.hiddenFraction, 0f)
        remaining = 50f
        motion.frame(-50f)
        assertEquals(0.5f, motion.hiddenFraction, 0.0001f)
        remaining = 0f
        motion.frame(-50f)
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun trailingLimitUsesTheMeasuredNavigationHeightAndRetainsTheContentMargin() = runTest {
        val motion = motion()
        motion.updateBottomBarHeight(160f)
        var remaining = 160f
        motion.updateEndDistanceProvider { remaining }
        motion.frame(-160f)
        repeat(8) {
            remaining -= 20f
            motion.frame(-20f)
            val viewportBottom = 1_000f
            val navTop = viewportBottom - motion.bottomBarHeightPx * (1f - motion.hiddenFraction)
            val lastItemBottom = viewportBottom - motion.bottomBarHeightPx - 16f + remaining
            assertEquals(16f, navTop - lastItemBottom, 0.0001f)
        }
    }

    @Test fun reversingNearTheEndStartsFromTheClampedPosition() = runTest {
        val motion = motion()
        var remaining = 200f
        motion.updateEndDistanceProvider { remaining }
        motion.frame(-100f)
        remaining = 50f
        motion.frame(-150f)
        assertEquals(0.5f, motion.hiddenFraction, 0f)
        remaining = 70f
        motion.frame(20f)
        assertEquals(0.38f, motion.hiddenFraction, 0.0001f)
        remaining = 50f
        motion.frame(-20f)
        assertEquals(0.5f, motion.hiddenFraction, 0.0001f)
    }

    @Test fun releaseNearTheEndRevealsEvenWithAnUpwardVelocity() = runTest {
        val motion = motion()
        motion.updateEndDistanceProvider { 80f }
        motion.frame(-100f)
        assertEquals(0.8f, motion.hiddenFraction, 0.0001f)
        motion.nestedScrollConnection.onPreFling(Velocity(0f, -1_000f))
        motion.nestedScrollConnection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun missingAndUnmeasurableTrailingDistancesRetainNormalMotion() = runTest {
        val motion = motion()
        motion.updateEndDistanceProvider { Float.POSITIVE_INFINITY }
        motion.frame(-30f)
        assertEquals(0.3f, motion.hiddenFraction, 0.0001f)
        motion.updateEndDistanceProvider { Float.NaN }
        motion.frame(-20f)
        assertEquals(0.5f, motion.hiddenFraction, 0.0001f)
        motion.updateEndDistanceProvider(null)
        motion.frame(-20f)
        assertEquals(0.7f, motion.hiddenFraction, 0.0001f)
    }

    @Test fun negativeTrailingRoundingDoesNotLeaveControlsPartiallyHidden() = runTest {
        val motion = motion()
        motion.updateEndDistanceProvider { -0.1f }
        motion.frame(-30f)
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun contentBecomingShortRevealsSmoothlyRatherThanJumping() = runTest {
        val motion = motion()
        motion.frame(-100f)
        motion.updateContentBounds(canScroll = false, atStart = true, atEnd = true)
        assertEquals(1f, motion.hiddenFraction, 0f)
        advanceTimeBy(64)
        runCurrent()
        assertTrue(motion.hiddenFraction > 0f && motion.hiddenFraction < 1f)
        advanceTimeBy(500)
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    @Test fun newInputImmediatelyCancelsTheOldSettleBeforeDirectionThreshold() = runTest {
        val motion = motion()
        motion.frame(-60f)
        motion.nestedScrollConnection.onPreFling(Velocity.Zero)
        motion.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
        advanceTimeBy(64)
        runCurrent()
        motion.nestedScrollConnection.onPreScroll(Offset(0f, 1f), NestedScrollSource.UserInput)
        val heldPosition = motion.hiddenFraction
        motion.frame(1f)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(heldPosition, motion.hiddenFraction, 0f)
        motion.frame(17f)
        assertTrue(motion.hiddenFraction < heldPosition)
    }

    @Test fun programmaticScrollIsIgnoredButUserFlingFramesContinue() = runTest {
        val motion = motion()
        val connection = UserScrollConnection(motion.nestedScrollConnection) { true }
        connection.onPostScroll(Offset(0f, -30f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(0f, motion.hiddenFraction, 0f)
        connection.onPostScroll(Offset(0f, -30f), Offset.Zero, NestedScrollSource.UserInput)
        connection.onPreFling(Velocity(0f, -1_000f))
        connection.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(0.5f, motion.hiddenFraction, 0.0001f)
        connection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        advanceTimeBy(500)
        runCurrent()
        connection.onPostScroll(Offset(0f, 50f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(1f, motion.hiddenFraction, 0f)
    }

    @Test fun resetAndDisposalCancelLateAnimationAndGestureUpdates() = runTest {
        val motion = motion()
        motion.frame(-60f)
        motion.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
        motion.reset()
        advanceTimeBy(500)
        assertEquals(0f, motion.hiddenFraction, 0f)
        motion.frame(-60f)
        motion.dispose()
        motion.frame(-60f)
        motion.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
        advanceTimeBy(500)
        assertEquals(0f, motion.hiddenFraction, 0f)
    }

    private fun TestScope.motion(): ScrollChromeMotionState {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return ScrollChromeMotionState(CoroutineScope(backgroundScope.coroutineContext + clock), 8f, 350f).apply {
            updateTopBarHeight(80f)
            updateBottomBarHeight(100f)
            updateContentBounds(canScroll = true, atStart = false, atEnd = false)
        }
    }

    private fun ScrollChromeMotionState.frame(deltaY: Float) {
        nestedScrollConnection.onPostScroll(Offset(0f, deltaY), Offset.Zero, NestedScrollSource.UserInput)
    }
}
