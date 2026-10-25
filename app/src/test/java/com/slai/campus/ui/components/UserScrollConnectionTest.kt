package com.slai.campus.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserScrollConnectionTest {
    @Test fun programmaticFramesAreFilteredEvenAfterADragWithoutFling() = runTest {
        val delegate = RecordingConnection()
        val connection = UserScrollConnection(delegate) { true }
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(0, delegate.frames)
        connection.frame(NestedScrollSource.UserInput)
        assertEquals(1, delegate.frames)
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(1, delegate.frames)
    }

    @Test fun userFlingFramesContinueUntilNormalCompletion() = runTest {
        val delegate = RecordingConnection()
        val connection = UserScrollConnection(delegate) { true }
        connection.frame(NestedScrollSource.UserInput)
        connection.onPreFling(Velocity(0f, -1_000f))
        repeat(4) { connection.frame(NestedScrollSource.SideEffect) }
        assertEquals(5, delegate.frames)
        connection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        assertEquals(1, delegate.settles)
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(5, delegate.frames)
    }

    @Test fun cancelledFlingCannotAuthorizeLaterProgrammaticFrames() = runTest {
        val delegate = RecordingConnection()
        val connection = UserScrollConnection(delegate) { true }
        connection.frame(NestedScrollSource.UserInput)
        val fling = launch {
            connection.onPreFling(Velocity(0f, -1_000f))
            awaitCancellation()
        }
        runCurrent()
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(2, delegate.frames)
        fling.cancelAndJoin() // Models Scrollable cancellation without onPostFling.
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(2, delegate.frames)
        assertEquals(0, delegate.settles)
    }

    @Test fun oldFlingCancellationCannotResetANewerFling() = runTest {
        val delegate = RecordingConnection()
        val connection = UserScrollConnection(delegate) { true }
        connection.frame(NestedScrollSource.UserInput)
        val old = launch {
            connection.onPreFling(Velocity(0f, -1_000f))
            awaitCancellation()
        }
        runCurrent()
        connection.frame(NestedScrollSource.UserInput)
        val newer = launch {
            connection.onPreFling(Velocity(0f, 1_000f))
            awaitCancellation()
        }
        runCurrent()
        old.cancelAndJoin()
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(3, delegate.frames)
        newer.cancelAndJoin()
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(3, delegate.frames)
    }

    @Test fun oldPostFlingCannotClearOrSettleANewerGesture() = runTest {
        val delegate = RecordingConnection()
        val connection = UserScrollConnection(delegate) { true }
        val finishOld = CompletableDeferred<Unit>()
        connection.frame(NestedScrollSource.UserInput)
        val old = launch {
            connection.onPreFling(Velocity(0f, -1_000f))
            finishOld.await()
            connection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        }
        runCurrent()
        connection.frame(NestedScrollSource.UserInput)
        val newer = launch {
            connection.onPreFling(Velocity(0f, 1_000f))
            awaitCancellation()
        }
        runCurrent()
        finishOld.complete(Unit)
        old.join()
        assertEquals(0, delegate.settles)
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(3, delegate.frames)
        newer.cancelAndJoin()
    }

    @Test fun disablingConnectionClearsFlingAuthority() = runTest {
        val delegate = RecordingConnection()
        var enabled = true
        val connection = UserScrollConnection(delegate) { enabled }
        connection.frame(NestedScrollSource.UserInput)
        connection.onPreFling(Velocity(0f, -1_000f))
        enabled = false
        connection.frame(NestedScrollSource.SideEffect)
        enabled = true
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(1, delegate.frames)
        connection.onPostFling(Velocity.Zero, Velocity.Zero)
        assertEquals(0, delegate.settles)
    }

    @Test fun failedPreFlingDoesNotLeavePermissionBehind() = runTest {
        val delegate = object : RecordingConnection() {
            override suspend fun onPreFling(available: Velocity): Velocity = error("test failure")
        }
        val connection = UserScrollConnection(delegate) { true }
        connection.frame(NestedScrollSource.UserInput)
        try { connection.onPreFling(Velocity(0f, -1_000f)) } catch (_: IllegalStateException) { }
        connection.frame(NestedScrollSource.SideEffect)
        assertEquals(1, delegate.frames)
    }

    @Test fun disposedConnectionCannotSettleOrRearmOnLateInput() = runTest {
        val delegate = RecordingConnection()
        val connection = UserScrollConnection(delegate) { true }
        connection.frame(NestedScrollSource.UserInput)
        connection.onPreFling(Velocity(0f, -1_000f))
        connection.invalidate()
        connection.frame(NestedScrollSource.SideEffect)
        connection.frame(NestedScrollSource.UserInput)
        connection.onPostFling(Velocity(0f, -1_000f), Velocity.Zero)
        assertEquals(1, delegate.frames)
        assertEquals(0, delegate.settles)
    }

    private fun UserScrollConnection.frame(source: NestedScrollSource) {
        onPostScroll(Offset(0f, -12f), Offset.Zero, source)
    }

    private open class RecordingConnection : NestedScrollConnection {
        var frames = 0
        var settles = 0
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            frames++
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settles++
            return Velocity.Zero
        }
    }
}
