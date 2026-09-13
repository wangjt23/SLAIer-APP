package com.slai.campus.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Test

class HttpClientFactoryTest {
    @Test fun `building all clients never accumulates cookie interceptors on shared builder`() {
        val builder = OkHttpClient.Builder()
        val bridge = Interceptor { it.proceed(it.request()) }
        val clients = listOf(
            HttpClientFactory.apiClient(builder, bridge), HttpClientFactory.webClient(builder, bridge),
            HttpClientFactory.apiClient(builder, bridge), HttpClientFactory.webClient(builder, bridge)
        )
        clients.forEach { assertThat(it.interceptors).containsExactly(bridge) }
        assertThat(builder.interceptors()).isEmpty()
        assertThat(clients.map { it.followRedirects }).containsExactly(false, true, false, true).inOrder()
    }
}
