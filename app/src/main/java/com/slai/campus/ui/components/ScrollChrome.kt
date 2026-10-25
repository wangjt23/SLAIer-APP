package com.slai.campus.ui.components

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Full-screen overlays and touch exploration keep the app's navigation controls visible. */
val LocalScrollChromeEnabled = staticCompositionLocalOf { true }

/**
 * Only let an actual user drag/fling drive the shared motion controller.
 * A scrollToItem/animateScrollToItem caused by data or a tab change must not hide navigation.
 */
@Composable
fun rememberUserScrollConnection(
    delegate: NestedScrollConnection,
    enabled: Boolean = LocalScrollChromeEnabled.current
): NestedScrollConnection {
    val enabledNow by rememberUpdatedState(enabled)
    val connection = remember(delegate, enabled) { UserScrollConnection(delegate) { enabledNow } }
    DisposableEffect(connection) { onDispose { connection.invalidate() } }
    return connection
}

internal class UserScrollConnection(
    private val delegate: NestedScrollConnection,
    private val enabled: () -> Boolean
) : NestedScrollConnection {
    private val lock = Any()
    private var valid = true
    private var userGesture = false
    private var gesture = 0L
    private var flingGesture = -1L
    private var flingJob: Job? = null
    private var flingCompletion: DisposableHandle? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!acceptScroll(source)) return Offset.Zero
        return delegate.onPreScroll(available, source)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!acceptScroll(source)) return Offset.Zero
        return delegate.onPostScroll(consumed, available, source)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val job = currentCoroutineContext()[Job]
        val currentGesture = synchronized(lock) {
            if (!valid || !enabled() || !userGesture || job?.isActive != true) {
                reset()
                return Velocity.Zero
            }
            clearFling()
            flingJob = job
            flingGesture = gesture
            gesture
        }
        // Scrollable may cancel an interrupted fling without dispatching onPostFling. Bind the
        // permission to its actual coroutine too, so later programmatic scroll cannot inherit it.
        val completion = job!!.invokeOnCompletion {
            synchronized(lock) {
                if (flingJob === job && flingGesture == currentGesture && gesture == currentGesture) reset()
            }
        }
        synchronized(lock) {
            if (flingJob === job && flingGesture == currentGesture) flingCompletion = completion
            else completion.dispose()
        }
        return try {
            delegate.onPreFling(available)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (flingJob === job && flingGesture == currentGesture) reset()
            }
            throw failure
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val job = currentCoroutineContext()[Job]
        val settle = synchronized(lock) {
            val current = valid && enabled() && userGesture && flingJob === job && flingGesture == gesture
            // A superseded fling must not clear a newer drag or its subsequent fling.
            if (flingJob === job) reset()
            current
        }
        return if (settle) delegate.onPostFling(consumed, available) else Velocity.Zero
    }

    private fun acceptScroll(source: NestedScrollSource): Boolean = synchronized(lock) {
        if (!valid || !enabled()) {
            reset()
            return false
        }
        if (source == NestedScrollSource.UserInput) {
            clearFling()
            gesture++
            userGesture = true
            return true
        }
        // The inertial frames of a real user fling are SideEffect too. Let those continue moving
        // the bars until the fling completes; unrelated programmatic events remain filtered.
        source == NestedScrollSource.SideEffect && flingJob?.isActive == true && flingGesture == gesture
    }

    private fun reset() {
        userGesture = false
        clearFling()
    }

    fun invalidate() = synchronized(lock) {
        valid = false
        reset()
    }

    private fun clearFling() {
        flingCompletion?.dispose()
        flingCompletion = null
        flingJob = null
        flingGesture = -1L
    }
}

/** A fixed measured bar translates out of the content viewport without changing list geometry. */
@Composable
fun ScrollingNavigationBar(
    state: ScrollChromeMotionState,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth().onSizeChanged {
            state.updateBottomBarHeight(it.height.toFloat())
        }.graphicsLayer { translationY = state.bottomBarHeightPx * state.hiddenFraction },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(0),
        content = content
    )
}

/** Observe changes too: accessibility can be enabled while the app remains open. */
@Composable
fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
