package com.slai.campus.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateManifestTest {
    private val payload = """{
        "tagName":"v1.2.0", "versionName":"1.2.0", "versionCode":9,
        "notes":"Test", "apkName":"slaier-1.2.0.apk", "apkSize":2700000,
        "apkUrl":"https://github.com/wangjt23/SLAIer-APP/releases/download/v1.2.0/slaier-1.2.0.apk",
        "sha256":"${"a".repeat(64)}", "futureField":true
    }"""

    @Test fun `valid manifest carries exact APK version and digest`() {
        val release = UpdateManifest.parse(payload)!!
        assertThat(release.versionCode).isEqualTo(9)
        assertThat(release.sha256).isEqualTo("a".repeat(64))
        assertThat(release.hasApk).isTrue()
    }

    @Test fun `unsafe downloads or mismatched versions are rejected`() {
        listOf(payload.replace("https://github.com/", "http://github.com/"),
            payload.replace("wangjt23/", "someone/"),
            payload.replace("\"versionName\":\"1.2.0\"", "\"versionName\":\"1.3.0\""),
            payload.replace("\"versionCode\":9", "\"versionCode\":0"),
            payload.replace("a".repeat(64), "bad"),
            payload.replace("\"apkSize\":2700000", "\"apkSize\":0"),
            payload.replace("\"notes\":\"Test\"", "\"notes\":{}"),
            "not json", "{}").forEach { assertThat(UpdateManifest.parse(it)).isNull() }
    }
}
