package com.slai.campus.core.network

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.CookieSessionGuard
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real OkHttp redirect chain: application interceptor mocks cannot exercise per-hop cookies. */
class WebCookieRedirectTest {
    @Test fun `redirect takes cookies from each actual host and commits to correct origin`() {
        checkRedirect(secondCookie = "sso=existing")
    }

    @Test fun `redirect to cookie-less host does not carry first host session`() {
        checkRedirect(secondCookie = null)
    }

    @Test fun `revoked SSO flow cannot persist next hop cookies despite taking a fresh snapshot`() {
        checkRedirect(secondCookie = "sso=existing", revokeOnSecondHop = true)
    }

    @Test fun `clear between redirects revokes untagged original call despite fresh next-hop snapshot`() {
        checkRedirect(secondCookie = "sso=existing", clearOnSecondHop = true)
    }

    @Test fun `network failure retry cannot acquire a new write permit after clearing`() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val requests = executor.submit(Callable {
            repeat(2) { attempt ->
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    // The first wire attempt fails without a Response.request to preserve tags.
                    if (attempt == 1) {
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" +
                            "Set-Cookie: session=late; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            }
        })
        val guard = CookieSessionGuard()
        var attempts = 0
        var written = false
        val network = Interceptor { chain -> bridgeWebViewCookies(chain, {
            if (++attempts == 2) guard.invalidate("retry.fixture.invalid")
            guard.snapshot("retry.fixture.invalid", null)
        }, { _, snapshot, _ -> written = snapshot != null && guard.claimWrite(snapshot, null) }) }
        val requestGuard = Interceptor { chain -> guardWebViewCookieRequest(chain) {
            guard.snapshot("retry.fixture.invalid", null)
        } }
        // Two local routes make this first-connection EOF retryable without relying on a pooled socket.
        val client = HttpClientFactory.webClient(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                listOf(java.net.InetAddress.getByName("127.0.0.1"), java.net.InetAddress.getByName("127.0.0.1"))
        }).callTimeout(10, TimeUnit.SECONDS),
            network, requestGuard)
        try {
            client.newCall(Request.Builder().url("http://retry.fixture.invalid:${server.localPort}/fixture").build())
                .execute().use { assertThat(it.code).isEqualTo(200) }
            requests.get(10, TimeUnit.SECONDS)
            assertThat(attempts).isEqualTo(2)
            assertThat(written).isFalse()
        } finally {
            server.close()
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun checkRedirect(secondCookie: String?, revokeOnSecondHop: Boolean = false,
                              clearOnSecondHop: Boolean = false) {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val captured = executor.submit(Callable {
            List(2) { hop ->
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        request += line
                    }
                    val response = if (hop == 0) {
                        "HTTP/1.1 302 Found\r\nLocation: http://localhost:${server.localPort}/final?code=next-code&state=next-state\r\nSet-Cookie: app=renewed; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\nSet-Cookie: sso=fresh; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                    }
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                    request
                }
            }
        })
        val guard = CookieSessionGuard()
        val cookies = mutableMapOf("127.0.0.1" to "app=old", "localhost" to secondCookie)
        val writes = mutableListOf<Pair<String, List<String>>>()
        var permitted = true
        val interceptor = Interceptor { chain ->
            bridgeWebViewCookies(chain,
                readCookie = { url ->
                    val host = url.toHttpUrl().host
                    if (host == "localhost" && revokeOnSecondHop) permitted = false
                    if (host == "localhost" && clearOnSecondHop) {
                        guard.invalidate("127.0.0.1")
                        cookies["127.0.0.1"] = null
                    }
                    guard.snapshot(host, cookies[host])
                },
                saveCookies = { url, snapshot, headers ->
                    val host = url.toHttpUrl().host
                    if (snapshot != null && guard.claimWrite(snapshot, cookies[host])) {
                        writes += host to headers
                        cookies[host] = headers.joinToString("; ") { it.substringBefore(';') }
                    }
                })
        }
        val requestGuard = Interceptor { chain -> guardWebViewCookieRequest(chain) { url ->
            val host = url.toHttpUrl().host
            guard.snapshot(host, cookies[host])
        } }
        val client = HttpClientFactory.webClient(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS), interceptor, requestGuard)
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/start?code=first-code&state=first-state#fragment")
                .apply { if (!clearOnSecondHop) tag(CookieWritePermit::class.java, CookieWritePermit { permitted }) }
                .build())
                .execute().use { assertThat(it.code).isEqualTo(200) }
            val requests = captured.get(10, TimeUnit.SECONDS)
            assertThat(requests[0]).contains("Cookie: app=old")
            assertThat(requests[1].firstOrNull { it.startsWith("Cookie:", true) })
                .isEqualTo(secondCookie?.let { "Cookie: $it" })
            assertThat(requests[0].firstOrNull { it.startsWith("Referer:", true) })
                .isEqualTo("Referer: http://127.0.0.1:${server.localPort}/")
            assertThat(requests[1].firstOrNull { it.startsWith("Referer:", true) })
                .isEqualTo(secondCookie?.let { "Referer: http://localhost:${server.localPort}/" })
            if (revokeOnSecondHop || clearOnSecondHop) assertThat(writes.map { it.first }).containsExactly("127.0.0.1")
            else assertThat(writes.map { it.first }).containsExactly("127.0.0.1", "localhost").inOrder()
            assertThat(cookies["127.0.0.1"]).isEqualTo(if (clearOnSecondHop) null else "app=renewed")
            assertThat(cookies["localhost"]).isEqualTo(if (revokeOnSecondHop || clearOnSecondHop) secondCookie else "sso=fresh")
        } finally {
            server.close()
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
