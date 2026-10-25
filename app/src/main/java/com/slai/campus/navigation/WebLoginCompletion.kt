package com.slai.campus.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One visible login page owns its probe; a later page never waits behind the previous one. */
internal class WebLoginCompletion {
    private var visibleFlowId: String? = null
    private var completedFlowId: String? = null
    private var job: Job? = null
    private val completing = MutableStateFlow(false)
    val isCompleting = completing.asStateFlow()

    fun update(flowId: String?) {
        if (visibleFlowId == flowId) return
        job?.cancel()
        job = null
        visibleFlowId = flowId
        completedFlowId = null
        completing.value = false
    }

    fun isCurrent(flowId: String): Boolean = visibleFlowId == flowId

    fun markCompleted(flowId: String) {
        if (isCurrent(flowId)) completedFlowId = flowId
    }

    fun start(flowId: String, scope: CoroutineScope, probe: suspend () -> Unit): Boolean {
        if (!isCurrent(flowId) || completedFlowId == flowId || completing.value) return false
        completing.value = true
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { probe() }
            finally {
                // A cancelled blocking probe may finish after the replacement page has started.
                if (job === coroutineContext[Job]) {
                    job = null
                    completing.value = false
                }
            }
        }
        job?.start()
        return true
    }
}
