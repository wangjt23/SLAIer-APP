package com.slai.campus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slai.campus.R
import com.slai.campus.core.web.AutomaticLoginState
import kotlinx.coroutines.delay

/** Small theme-colored status above the bottom navigation; it never takes focus or blocks taps. */
@Composable
fun AutomaticLoginNotice(state: AutomaticLoginState) {
    var visible by remember { mutableStateOf(false) }
    var displayedState by remember { mutableStateOf(AutomaticLoginState.IDLE) }
    val accessibility = LocalAccessibilityManager.current

    LaunchedEffect(state) {
        if (state == AutomaticLoginState.IDLE) {
            visible = false
            return@LaunchedEffect
        }
        displayedState = state
        visible = true
        if (state != AutomaticLoginState.RUNNING) {
            val duration = if (state == AutomaticLoginState.SUCCEEDED) 1_200L else 2_800L
            delay(accessibility?.calculateRecommendedTimeoutMillis(duration,
                containsIcons = true, containsText = true, containsControls = false) ?: duration)
            visible = false
        }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 4 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 6 }
        ) {
            AutomaticLoginNoticeContent(displayedState)
        }
    }
}

@Composable
internal fun AutomaticLoginNoticeContent(state: AutomaticLoginState) {
    if (state == AutomaticLoginState.IDLE) return
    val colors = MaterialTheme.colorScheme
    val accent = if (state == AutomaticLoginState.FAILED) colors.error else colors.primary
    val message = when (state) {
        AutomaticLoginState.RUNNING -> R.string.automatic_login_running
        AutomaticLoginState.SUCCEEDED -> R.string.automatic_login_succeeded
        else -> R.string.automatic_login_failed
    }
    Surface(
        modifier = Modifier.widthIn(max = 360.dp).animateContentSize(tween(180))
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        shadowElevation = 4.dp
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state == AutomaticLoginState.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp).clearAndSetSemantics {},
                    color = accent, strokeWidth = 2.dp, trackColor = accent.copy(alpha = 0.12f))
            } else {
                Icon(if (state == AutomaticLoginState.SUCCEEDED) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                    contentDescription = null, modifier = Modifier.size(20.dp), tint = accent)
            }
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
        }
    }
}
