package com.slai.campus.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.slai.campus.core.common.AppDispatchers
import com.slai.campus.core.session.SessionStore
import com.slai.campus.domain.update.UpdateFailure
import com.slai.campus.domain.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Real repository, DataStore and OkHttp; no school credentials or real update requests. */
@RunWith(AndroidJUnit4::class)
class UpdateRepositoryCancellationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manifest = """{
        "tagName":"v99.0.0","versionName":"99.0.0","versionCode":999,
        "apkName":"slaier-99.0.0.apk","apkSize":8,"sha256":"${"a".repeat(64)}",
        "apkUrl":"https://github.com/wangjt23/SLAIer-APP/releases/download/v99.0.0/slaier-99.0.0.apk"
    }"""

    @Test fun cancelledCheckRestoresIdleAndCanCheckAgain() = runBlocking<Unit> {
        val entered = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = client { chain ->
            entered.complete(chain.call())
            assertTrue(release.await(10, TimeUnit.SECONDS))
            manifest.toResponseBody()
        }
        withRepository(client) { repository ->
            val check = launch { repository.check(true) }
            val call = withTimeout(5_000) { entered.await() }
            try {
                assertEquals(UpdateState.Checking, repository.state.value)
                withTimeout(2_000) { check.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertEquals(UpdateState.Idle, repository.state.value)
            } finally { release.countDown(); check.cancelAndJoin() }
            repository.check(true)
            assertTrue(repository.state.value is UpdateState.Available)
        }
    }

    @Test fun concurrentChecksIgnoreAndAcknowledgeDoNotOverwriteActiveCheck() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var requests = 0
        val client = client { _ ->
            requests++
            entered.complete(Unit)
            assertTrue(release.await(10, TimeUnit.SECONDS))
            manifest.toResponseBody()
        }
        withRepository(client) { repository ->
            val check = launch { repository.check(true) }
            withTimeout(5_000) { entered.await() }
            try {
                withTimeout(2_000) {
                    repository.check(false)
                    repository.check(true)
                    repository.downloadAndInstall()
                    repository.ignoreCurrentVersion()
                    repository.acknowledge()
                }
                assertEquals(UpdateState.Checking, repository.state.value)
                assertEquals(1, requests)
            } finally { release.countDown(); check.join() }
            assertTrue(repository.state.value is UpdateState.Available)
        }
    }

    @Test fun cancelledDownloadRestoresOfferAndLateInstallerResultIsIgnored() = runBlocking<Unit> {
        val entered = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = client { chain ->
            if (chain.request().url.toString() == UpdateManifest.URL) manifest.toResponseBody()
            else {
                entered.complete(chain.call())
                assertTrue(release.await(10, TimeUnit.SECONDS))
                "fake-apk".toResponseBody()
            }
        }
        withRepository(client) { repository ->
            repository.check(true)
            val offer = repository.state.value
            val download = launch { repository.downloadAndInstall() }
            val call = withTimeout(5_000) { entered.await() }
            try {
                assertTrue(repository.state.value is UpdateState.Downloading)
                withTimeout(2_000) { download.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertEquals(offer, repository.state.value)
                UpdateEvents.publish(123, InstallEvent.Cancelled)
                UpdateEvents.publish(123, InstallEvent.Success)
            } finally { release.countDown(); download.cancelAndJoin() }
            // Retry gets as far as the unchanged security check, proving cancellation is recoverable.
            repository.downloadAndInstall()
            assertEquals(UpdateFailure.CHECKSUM_MISMATCH,
                (repository.state.value as UpdateState.Failed).reason)
        }
    }

    @Test fun cancelledStreamingDownloadCannotDeleteOrOverwriteRetry() = runBlocking<Unit> {
        val firstProgress = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val oldBodyClosed = CompletableDeferred<Unit>()
        var requests = 0
        val client = client { _ ->
            if (++requests == 1) object : ResponseBody() {
                private var reads = 0
                private val content = object : ForwardingSource(Buffer().writeUtf8("old-apk")) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (++reads > 1) assertTrue(release.await(10, TimeUnit.SECONDS))
                        return super.read(sink, byteCount)
                    }
                    override fun close() {
                        super.close()
                        oldBodyClosed.complete(Unit)
                    }
                }.buffer()
                override fun contentType() = null
                override fun contentLength() = 7L
                override fun source() = content
            } else "new-apk".toResponseBody()
        }
        val downloader = ApkDownloader(client, context)
        try {
            val first = launch { downloader.download("https://example.invalid/local-test", "cancellation-test.apk") { _, _ ->
                firstProgress.complete(Unit)
            } }
            withTimeout(5_000) { firstProgress.await() }
            withTimeout(2_000) { first.cancelAndJoin() }
            val result = downloader.download("https://example.invalid/local-test", "cancellation-test.apk") { _, _ -> }
            assertTrue(result is DownloadOutcome.Ok)
            release.countDown()
            withTimeout(5_000) { oldBodyClosed.await() }
            assertEquals("new-apk", downloader.targetFile("cancellation-test.apk").readText())
        } finally { release.countDown(); downloader.clean() }
    }

    private suspend fun withRepository(client: OkHttpClient, test: suspend (UpdateRepositoryImpl) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = File.createTempFile("update-test-", ".preferences_pb", context.cacheDir)
        val store = SessionStore(PreferenceDataStoreFactory.create(scope = scope) { settings })
        try {
            val repository = UpdateRepositoryImpl(GitHubReleaseDataSource(client, store),
                ApkDownloader(client, context), ApkVerifier(context), ApkInstaller(context),
                store, AppDispatchers(), scope)
            test(repository)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            settings.delete()
        }
    }

    private fun client(body: (okhttp3.Interceptor.Chain) -> ResponseBody) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("local fixture").body(body(chain)).build()
        }.build()
}
