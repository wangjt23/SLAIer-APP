package com.slai.campus.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The list keeps one viewport while the two bars slide above it. Padding belongs to the scrollable
 * content, not the viewport: changing the bar position must never resize or push a moving list.
 * At either end of the list the shared motion restores the controls, so this trailing content
 * padding belongs beneath the visible navigation bar rather than becoming an empty strip.
 * System insets are consumed by CampusRoot and must not be applied again here.
 */
@Composable
fun ScrollChromePage(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    distanceToEndPx: (() -> Float)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val motion = LocalScrollChromeMotion.current
    val connection = motion?.let {
        rememberUserScrollConnection(
            it.nestedScrollConnection,
            LocalScrollChromeEnabled.current && (canScrollBackward || canScrollForward)
        )
    }
    SideEffect {
        motion?.updateEndDistanceProvider(distanceToEndPx)
        motion?.updateContentBounds(
            canScroll = canScrollBackward || canScrollForward,
            atStart = !canScrollBackward,
            atEnd = !canScrollForward
        )
    }
    // Measure the toolbar before composing the list. onSizeChanged would apply its padding one
    // frame later and visibly move the first item on initial display or after a font-size change.
    SubcomposeLayout(modifier.fillMaxSize().then(connection?.let { Modifier.nestedScroll(it) } ?: Modifier)) { constraints ->
        val toolbar = subcompose(ScrollChromeSlot.Toolbar) {
            Box(Modifier.graphicsLayer {
                translationY = -size.height * (motion?.hiddenFraction ?: 0f)
            }) { topBar() }
        }.single().measure(constraints.copy(minHeight = 0))
        motion?.updateTopBarHeight(toolbar.height.toFloat())
        val padding = PaddingValues(
            start = 16.dp,
            top = toolbar.height.toDp() + 16.dp,
            end = 16.dp,
            bottom = (motion?.bottomBarHeightPx ?: 0f).toDp() + 16.dp
        )
        val page = subcompose(ScrollChromeSlot.Content) {
            Box(Modifier.fillMaxSize()) { content(padding) }
        }.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            page.placeRelative(0, 0)
            toolbar.placeRelative(0, 0)
        }
    }
}

private enum class ScrollChromeSlot { Toolbar, Content }

/** Read positions only in the scroll callback; do not recompose the page for every scroll pixel. */
@Composable
fun rememberScrollEndDistance(state: LazyListState): () -> Float = remember(state) {
    {
        val layout = state.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        if (last != null && last.index == layout.totalItemsCount - 1) {
            (last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset)
                .toFloat().coerceAtLeast(0f)
        } else {
            Float.POSITIVE_INFINITY
        }
    }
}

@Composable
fun rememberScrollEndDistance(state: ScrollState): () -> Float = remember(state) {
    { (state.maxValue - state.value).toFloat().coerceAtLeast(0f) }
}

/** Keep the refresh indicator immediately below the visible part of the overlaid toolbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollChromeRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    topPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val motion = LocalScrollChromeMotion.current
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = refreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = refreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).offset {
                    IntOffset(0, (topPadding.toPx() * (1f - (motion?.hiddenFraction ?: 0f))).roundToInt())
                }
            )
        },
        content = content
    )
}
