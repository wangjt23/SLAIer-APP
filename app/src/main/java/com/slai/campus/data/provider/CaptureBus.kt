package com.slai.campus.data.provider

import com.slai.campus.core.web.CaptureSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a finished capture from the WebView overlay to the provider screen.
 *
 * The two live in different ViewModels (the overlay is owned by `MainViewModel`, the analysis by
 * `ProviderViewModel`), and a capture is a one-shot payload, so a tiny in-memory channel is simpler
 * and more predictable than threading it through navigation arguments.
 */
@Singleton
class CaptureBus @Inject constructor() {

    private val _pending = MutableStateFlow<CaptureSession?>(null)
    val pending: StateFlow<CaptureSession?> = _pending.asStateFlow()

    fun publish(session: CaptureSession) {
        _pending.value = session
    }

    fun clear() {
        _pending.value = null
    }
}
