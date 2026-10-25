package com.slai.campus.navigation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebLoginCompletionTest {
    @Test fun `new page finishes while the old transport is still returning`() = runTest {
        val completion = WebLoginCompletion()
        val oldResponse = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Unit>()
        val finished = mutableListOf<String>()
        completion.update("first")
        assertThat(completion.start("first", this) {
            // Models a blocking transport body that cannot stop immediately on cancellation.
            withContext(NonCancellable) { oldResponse.await() }
            if (completion.isCurrent("first")) finished += "first"
        }).isTrue()
        runCurrent()
        assertThat(completion.isCompleting.value).isTrue()

        completion.update("second")
        assertThat(completion.start("second", this) {
            newResponse.await()
            completion.markCompleted("second")
            finished += "second"
        }).isTrue()
        runCurrent()
        newResponse.complete(Unit)
        runCurrent()
        assertThat(finished).containsExactly("second")
        assertThat(completion.isCompleting.value).isFalse()
        oldResponse.complete(Unit)
        advanceUntilIdle()
        assertThat(finished).containsExactly("second")
        assertThat(completion.start("second", this) { finished += "duplicate" }).isFalse()
    }

    @Test fun `old probe finally cannot clear the newer page progress`() = runTest {
        val completion = WebLoginCompletion()
        val oldResponse = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Unit>()
        completion.update("first")
        completion.start("first", this) { withContext(NonCancellable) { oldResponse.await() } }
        runCurrent()
        completion.update("second")
        completion.start("second", this) { newResponse.await() }
        runCurrent()
        oldResponse.complete(Unit)
        runCurrent()
        assertThat(completion.isCompleting.value).isTrue()
        assertThat(completion.start("first", this) {}).isFalse()
        newResponse.complete(Unit)
        advanceUntilIdle()
        assertThat(completion.isCompleting.value).isFalse()
    }
}
