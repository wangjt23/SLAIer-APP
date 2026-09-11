package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「没网」和「有网但连不上学校」必须分开。
 *
 * 场景是实测反馈：刷脸出闸 → 走出校园网 → 学生系统在校外不可达。如果这时只说"离线"，
 * 用户看到的是"我明明出闸了，为什么还在馆里一直计时"；正确的提示是"回校园网再刷新"。
 */
class AttendanceNetworkFailureTest {

    @Test
    fun `device without a network is offline`() {
        val result = networkFailureResult(hasNetwork = false, hasCache = true, reason = "no network")

        assertThat(result).isInstanceOf(AttendanceRefreshResult.Offline::class.java)
        assertThat((result as AttendanceRefreshResult.Offline).cached).isTrue()
        assertThat(result.needsCampusNetworkHint).isFalse()
    }

    @Test
    fun `device with a network but an unreachable school host hints at the campus network`() {
        val result = networkFailureResult(
            hasNetwork = true,
            hasCache = true,
            reason = "Unable to resolve host \"stu.slai.edu.cn\""
        )

        assertThat(result).isInstanceOf(AttendanceRefreshResult.Unreachable::class.java)
        assertThat((result as AttendanceRefreshResult.Unreachable).reason).contains("stu.slai.edu.cn")
        assertThat(result.needsCampusNetworkHint).isTrue()
    }

    @Test
    fun `other outcomes never ask for the campus network`() {
        val others = listOf(
            AttendanceRefreshResult.Success("2026-09", 11),
            AttendanceRefreshResult.SessionExpired,
            AttendanceRefreshResult.SchemaChanged("missing data"),
            AttendanceRefreshResult.ServerError(500),
            AttendanceRefreshResult.Failed("boom")
        )

        others.forEach { assertThat(it.needsCampusNetworkHint).isFalse() }
    }
}
