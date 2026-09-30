package com.slai.campus.core.web

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.SavedLoginStatus
import org.junit.Test

class SchoolLoginTest {
    @Test fun `only exact school HTTPS AD FS pages can receive credentials`() {
        assertThat(SchoolLoginScript.isTrustedLogin("https://sts.slai.edu.cn/adfs/ls/?wa=wsignin1.0")).isTrue()
        assertThat(SchoolLoginScript.isTrustedLogin("https://sts.slai.edu.cn:443/adfs/ls")).isTrue()
        assertThat(SchoolLoginScript.isTrustedLogin("https://sts.slai.edu.cn/adfs/oauth2/authorize?client_id=test")).isTrue()
        listOf(null, "http://sts.slai.edu.cn/adfs/ls/", "https://sts.slai.edu.cn.evil.example/adfs/ls/",
            "https://sts.slai.edu.cn:444/adfs/ls/", "https://user@sts.slai.edu.cn/adfs/ls/",
            "https://sis.slai.edu.cn/adfs/ls/", "https://sts.slai.edu.cn/adfs/lsfake",
            "https://sts.slai.edu.cn/other", "not a url").forEach {
            assertThat(SchoolLoginScript.isTrustedLogin(it)).isFalse()
        }
    }

    @Test fun `redirects and reloads cannot submit a saved password twice`() {
        val attempt = SchoolLoginAttempt()
        assertThat(attempt.claim(false)).isTrue()
        assertThat(attempt.claim(false)).isFalse()
        assertThat(attempt.claim(true)).isTrue()
        assertThat(attempt.claim(true)).isFalse()
        assertThat(attempt.claim(false)).isFalse()
    }

    @Test fun `captcha or SSL failure permanently stops this flow`() {
        val attempt = SchoolLoginAttempt()
        attempt.stop()
        assertThat(attempt.claim(false)).isFalse()
        assertThat(attempt.claim(true)).isFalse()
    }

    @Test fun `saved credentials require opt in and unpaused state`() {
        assertThat(SavedLoginStatus().canAttempt).isFalse()
        assertThat(SavedLoginStatus(saved = true).canAttempt).isFalse()
        assertThat(SavedLoginStatus(saved = true, enabled = true, paused = true).canAttempt).isFalse()
        assertThat(SavedLoginStatus(saved = true, enabled = true).canAttempt).isTrue()
    }
}
