package com.slai.campus.data.update

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.update.AppRelease
import com.slai.campus.domain.update.ReleaseDecision
import com.slai.campus.domain.update.UpdateDecision
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ReleaseFeedReaderTest {
    private val release = AppRelease("v1.2.0", "1.2.0", apkUrl = "https://example.com/app.apk")

    @Test fun `304 still offers newer cached version after process restart`() {
        val persisted = Json.encodeToString(release)
        val restored = Json.decodeFromString<AppRelease>(persisted)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertThat(chain.request().header("If-None-Match")).isEqualTo("feed-etag")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(304)
                .message("Not Modified").body("".toResponseBody()).build()
        }.build()
        val result = readReleaseFeed(client, UpdateManifest.URL, restored, "feed-etag", { error("must use cache") })
        val cached = (result.result as RemoteResult.Success).data
        assertThat(ReleaseDecision.decide(cached, "1.1.4", null)).isInstanceOf(UpdateDecision.Available::class.java)
        assertThat(ReleaseDecision.decide(cached, "1.2.0", null)).isEqualTo(UpdateDecision.UpToDate)
        assertThat(ReleaseDecision.decide(cached, "1.1.4", "1.2.0")).isInstanceOf(UpdateDecision.Ignored::class.java)
    }

    @Test fun `orphan etag is not sent and an unexpected 304 retries without conditions`() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertThat(chain.request().header("If-None-Match")).isNull()
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (calls == 1) 304 else 200).message("test").header("ETag", "new-etag")
                .body("fixture".toResponseBody()).build()
        }.build()
        val fetch = readReleaseFeed(client, UpdateManifest.URL, null, "old-api-etag", { release })
        assertThat((fetch.result as RemoteResult.Success).data).isEqualTo(release)
        assertThat(fetch.etag).isEqualTo("new-etag")
        assertThat(calls).isEqualTo(2)
    }

    @Test fun `invalid feed and server failures do not report up to date`() {
        for (code in listOf(200, 403, 404, 429, 500)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("test").body("garbage".toResponseBody()).build()
            }.build()
            val fetch = readReleaseFeed(client, UpdateManifest.URL, release, "etag", { null })
            assertThat(fetch.result is RemoteResult.Success).isFalse()
        }
    }
}
