package com.slai.campus.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Test

class HttpClientFactoryTest {
    @Test fun `request guard runs once outside network redirect interceptors without mutating builder`() {
        val builder = OkHttpClient.Builder()
        val guard = Interceptor { it.proceed(it.request()) }
        val bridge = Interceptor { it.proceed(it.request()) }
        listOf(HttpClientFactory.apiClient(builder, bridge, guard),
            HttpClientFactory.webClient(builder, bridge, guard)).forEach {
            assertThat(it.interceptors).containsExactly(guard)
            assertThat(it.networkInterceptors).containsExactly(bridge)
        }
        assertThat(builder.interceptors()).isEmpty()
        assertThat(builder.networkInterceptors()).isEmpty()
    }

    @Test fun `building all clients never accumulates cookie interceptors on shared builder`() {
        val application = Interceptor { it.proceed(it.request()) }
        val existingNetwork = Interceptor { it.proceed(it.request()) }
        val builder = OkHttpClient.Builder().addInterceptor(application).addNetworkInterceptor(existingNetwork)
        val bridge = Interceptor { it.proceed(it.request()) }
        val clients = listOf(
            HttpClientFactory.apiClient(builder, bridge), HttpClientFactory.webClient(builder, bridge),
            HttpClientFactory.apiClient(builder, bridge), HttpClientFactory.webClient(builder, bridge)
        )
        clients.forEach {
            assertThat(it.interceptors).containsExactly(application)
            assertThat(it.networkInterceptors).containsExactly(existingNetwork, bridge).inOrder()
        }
        assertThat(builder.interceptors()).containsExactly(application)
        assertThat(builder.networkInterceptors()).containsExactly(existingNetwork)
        assertThat(clients.map { it.followRedirects }).containsExactly(false, true, false, true).inOrder()
    }
}
