package com.slai.campus.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** One position drives both bars; pages keep their viewport and content padding unchanged. */
val LocalScrollChromeMotion = staticCompositionLocalOf<ScrollChromeMotionState?> { null }

@Composable
fun rememberScrollChromeMotionState(): ScrollChromeMotionState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(scope, density) {
        ScrollChromeMotionState(scope, with(density) { 8.dp.toPx() }, 350f * density.density)
    }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/**
 * Observe scrolling after the list moves, without consuming any distance or velocity. During a
 * drag the position changes directly; only the last partial movement animates when released.
 * Wrap [nestedScrollConnection] in rememberUserScrollConnection so only real user flings, rather
 * than scrollToItem/animateScrollToItem, can continue moving the controls after a drag.
 */
@Stable
class ScrollChromeMotionState internal constructor(
    private val scope: CoroutineScope,
    reversalThresholdPx: Float,
    private val decisiveVelocityPxPerSecond: Float
) {
    private val drag = ScrollChromeDrag(reversalThresholdPx)
    private var settleJob: Job? = null
    private var animationGeneration = 0L
    private var valid = true
    private var atStart = true
    private var atEnd = true
    private var releaseVelocity = 0f
    private var endDistancePx: (() -> Float)? = null

    var hiddenFraction by mutableFloatStateOf(0f)
        private set
    var topBarHeightPx by mutableFloatStateOf(0f)
        private set
    var bottomBarHeightPx by mutableFloatStateOf(0f)
        private set
    var canScroll by mutableStateOf(false)
        private set

    fun updateTopBarHeight(heightPx: Float) {
        if (heightPx.isFinite() && heightPx >= 0f) topBarHeightPx = heightPx
    }

    fun updateBottomBarHeight(heightPx: Float) {
        if (heightPx.isFinite() && heightPx >= 0f) bottomBarHeightPx = heightPx
    }

    /** Read list geometry during a scroll event without recomposing the page for every pixel. */
    fun updateEndDistanceProvider(provider: (() -> Float)?) {
        if (valid) endDistancePx = provider
    }

    fun updateContentBounds(canScroll: Boolean, atStart: Boolean, atEnd: Boolean) {
        if (!valid) return
        val reachedBoundary = (!this.atStart && atStart) || (!this.atEnd && atEnd)
        val becameShort = this.canScroll && !canScroll
        this.canScroll = canScroll
        this.atStart = atStart
        this.atEnd = atEnd
        // A boundary's fixed content padding belongs underneath its visible controls. Reveal them
        // as soon as it is reached, including data changes that turn a long page into a short one.
        if ((reachedBoundary || becameShort) && hiddenFraction > 0f) settleTo(0f)
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) {
                val pushesPastBoundary = (atStart && available.y > 0f) || (atEnd && available.y < 0f)
                // Continuing to push beyond an edge must not restart the same reveal every frame.
                // Actual list movement below, or input away from that edge, still takes over now.
                if (!pushesPastBoundary || settleJob?.isActive != true || settlingTarget != 0f) cancelSettle()
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!valid || !canScroll) return Offset.Zero
            if (consumed.y.isFinite() && consumed.y != 0f) {
                cancelSettle()
                val position = drag.move(consumed.y, max(topBarHeightPx, bottomBarHeightPx))
                // During the last bar-height of scrolling, content and controls arrive together:
                // the list's fixed trailing padding stays covered rather than becoming a gap.
                hiddenFraction = position.coerceAtMost(endHiddenLimit() ?: 1f)
                drag.position = hiddenFraction
            }
            // No list movement remains at an edge. Finish revealing without competing with the
            // list's overscroll/fling, and without consuming its leftover gesture distance.
            if ((available.y > 0f && atStart) || (available.y < 0f && atEnd)) settleTo(0f)
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            releaseVelocity = available.y.takeIf { it.isFinite() } ?: 0f
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!valid) return Velocity.Zero
            val target = when {
                !canScroll || atStart || atEnd -> 0f
                (endHiddenLimit() ?: 1f) < 1f -> 0f
                releaseVelocity <= -decisiveVelocityPxPerSecond && drag.direction > 0 -> 1f
                releaseVelocity >= decisiveVelocityPxPerSecond && drag.direction < 0 -> 0f
                hiddenFraction >= 0.5f -> 1f
                else -> 0f
            }
            settleTo(target)
            releaseVelocity = 0f
            return Velocity.Zero
        }
    }

    /** Tabs, overlays and accessibility changes make controls immediately available. */
    fun reset() {
        cancelSettle()
        drag.reset()
        hiddenFraction = 0f
        releaseVelocity = 0f
    }

    fun dispose() {
        valid = false
        reset()
        endDistancePx = null
    }

    private fun endHiddenLimit(): Float? {
        if (bottomBarHeightPx <= 0f) return null
        val remaining = endDistancePx?.invoke() ?: return null
        return if (remaining.isNaN()) null else (remaining / bottomBarHeightPx).coerceIn(0f, 1f)
    }

    private fun cancelSettle() {
        animationGeneration++
        settleJob?.cancel()
        settleJob = null
    }

    private fun settleTo(target: Float) {
        if (!valid || hiddenFraction == target) return
        // Repeated edge frames should not restart the same animation on every frame.
        if (settleJob?.isActive == true && settlingTarget == target) return
        cancelSettle()
        settlingTarget = target
        val generation = animationGeneration
        settleJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animate(
                initialValue = hiddenFraction,
                targetValue = target,
                animationSpec = spring(dampingRatio = 1f, stiffness = 1_200f, visibilityThreshold = 0.005f)
            ) { value, _ ->
                if (valid && generation == animationGeneration) {
                    hiddenFraction = value.coerceIn(0f, 1f)
                    drag.position = hiddenFraction
                }
            }
            if (valid && generation == animationGeneration) {
                hiddenFraction = target
                drag.position = target
            }
        }
    }

    private var settlingTarget = 0f
}

/** Direction hysteresis filters tiny reversals while the first movement stays directly responsive. */
internal class ScrollChromeDrag(private val reversalThresholdPx: Float) {
    var position = 0f
    var direction = 0
        private set
    private var reversalDistance = 0f

    fun move(deltaY: Float, travelPx: Float): Float {
        if (!deltaY.isFinite() || !travelPx.isFinite() || travelPx <= 0f || deltaY == 0f) return position
        val nextDirection = if (deltaY < 0f) 1 else -1
        val distance: Float
        if (direction != 0 && nextDirection != direction) {
            reversalDistance += abs(deltaY)
            if (reversalDistance < reversalThresholdPx) return position
            distance = reversalDistance - reversalThresholdPx
            reversalDistance = 0f
        } else {
            distance = abs(deltaY)
            reversalDistance = 0f
        }
        direction = nextDirection
        position = (position + nextDirection * distance / travelPx).coerceIn(0f, 1f)
        return position
    }

    fun reset() {
        position = 0f
        direction = 0
        reversalDistance = 0f
    }
}
