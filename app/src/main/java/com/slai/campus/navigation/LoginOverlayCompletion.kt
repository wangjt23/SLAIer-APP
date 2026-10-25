package com.slai.campus.navigation

import kotlinx.coroutines.delay

/** The page can be closed or replaced both before delivery and during the success notice. */
internal suspend fun closeCompletedLoginOverlay(
    flowId: String,
    currentOverlay: () -> Overlay?,
    close: () -> Unit,
    delayMs: Long = 600
): Boolean {
    fun matches(): Boolean {
        val page = currentOverlay() as? Overlay.Web ?: return false
        return page.flowId == flowId && page.isLoginFlow && !page.captureEnabled && page.closeOnLogin
    }
    if (!matches()) return false
    delay(delayMs)
    if (!matches()) return false
    close()
    return true
}
