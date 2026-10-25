package com.slai.campus.core.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CompletableDeferred
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Local TLS fixture: the disposable key is generated on the emulator and never saved to Git. */
internal class DelayedCookieHttpsServer(cookie: String) : AutoCloseable {
    private val alias = "cookie-test-${UUID.randomUUID()}"
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val executor = Executors.newSingleThreadExecutor()
    private val release = CountDownLatch(1)
    val entered = CompletableDeferred<Unit>()
    val returned = CompletableDeferred<Unit>()
    val failure = CompletableDeferred<Throwable?>()
    private val server: SSLServerSocket
    @Volatile private var connection: Socket? = null
    val url: String get() = "https://localhost:${server.localPort}/a/cookie-fixture"

    init {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                // Conscrypt encodes TLS RSA-PSS itself, then invokes raw RSA/ECB/NoPadding.
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=localhost"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(1_577_836_800_000L))
                .setCertificateNotAfter(Date(4_102_444_800_000L))
            // TLS 1.3 RSA-PSS uses a matching MGF1 digest; recent KeyMint requires explicit permission.
            if (android.os.Build.VERSION.SDK_INT >= 35) spec.setMgf1Digests(
                KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            initialize(spec.build())
            generateKeyPair()
        }
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                arrayOf(alias).takeIf { keyType?.startsWith("RSA") == true }
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                alias.takeIf { keyType?.startsWith("RSA") == true }
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?,
                                                engine: javax.net.ssl.SSLEngine?) =
                alias.takeIf { keyType?.startsWith("RSA") == true }
            override fun getCertificateChain(selected: String?) = arrayOf(keys.getCertificate(alias) as X509Certificate)
            override fun getPrivateKey(selected: String?) = keys.getKey(alias, null) as PrivateKey
        }
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
        server = tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        executor.submit {
            var certificateAlert: javax.net.ssl.SSLHandshakeException? = null
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                fun remainingMillis(): Int {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw java.net.SocketTimeoutException("local TLS/HTTP fixture deadline exceeded")
                    return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
                }
                var served = false
                repeat(4) {
                    if (served) return@repeat
                    server.soTimeout = remainingMillis()
                    try {
                        server.accept().use { socket ->
                            connection = socket
                            socket.soTimeout = remainingMillis()
                            val request = socket.getInputStream().bufferedReader()
                            val line = request.readLine() ?: throw java.io.EOFException("no HTTP request")
                            check(line == "GET /a/cookie-fixture HTTP/1.1") { "unexpected local request: $line" }
                            while (true) {
                                val header = request.readLine() ?: throw java.io.EOFException("incomplete HTTP headers")
                                if (header.isEmpty()) break
                            }
                            entered.complete(Unit)
                            check(release.await((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)) {
                                "local response release timed out"
                            }
                            val body = "<!doctype html><html><body>cookie fixture</body></html>"
                            val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                                "Set-Cookie: $cookie=late; Path=/a; Secure; HttpOnly\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
                            socket.getOutputStream().write((headers + body).toByteArray())
                            socket.getOutputStream().flush()
                            served = true
                        }
                    } catch (error: javax.net.ssl.SSLHandshakeException) {
                        // Chromium initially rejects the self-signed fixture, asks SslErrorHandler,
                        // then reconnects after the test permits this exact localhost URL.
                        if (entered.isCompleted || !error.message.orEmpty().contains("CERTIFICATE_UNKNOWN")) throw error
                        certificateAlert = error
                    }
                }
                if (!served) throw certificateAlert ?: java.io.IOException("local HTTP request not received")
                failure.complete(null)
            } catch (error: Exception) {
                if (error !== certificateAlert) certificateAlert?.let(error::addSuppressed)
                failure.complete(error)
            }
            finally { returned.complete(Unit) }
        }
    }

    fun respond() { release.countDown() }

    override fun close() {
        release.countDown()
        runCatching { connection?.close() }
        server.close()
        executor.shutdownNow()
        keys.deleteEntry(alias)
    }
}
