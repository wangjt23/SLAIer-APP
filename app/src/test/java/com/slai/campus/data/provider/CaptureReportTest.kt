package com.slai.campus.data.provider

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.web.CaptureRecord
import com.slai.campus.core.web.InterceptedRequest
import org.junit.Test

/**
 * The report is what a human reads when the learner cannot decide. It must therefore:
 *  - never leak a session cookie, student number or password;
 *  - still show the *structure* (URLs, parameter names, JSON keys) needed to write a provider.
 */
class CaptureReportTest {

    private val records = listOf(
        CaptureRecord(
            method = "POST",
            url = "https://sis.slai.edu.cn/yjsxt/xskbcx/xskbcx_cxXsKb.html?gnmkdm=N253508&code=SECRET_TICKET",
            requestBody = "xnm=2026&xqm=3&kzlx=ck&yhm=20260001",
            status = 200,
            body = """{"kbList":[{"kcmc":"数值分析","xqj":"1","jcs":"1-2","zcd":"1-16周","cdmc":"A201"}],"xqjmcMap":{"1":"星期一"}}""",
            pageUrl = "https://sis.slai.edu.cn/yjsxt/xskbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508"
        )
    )

    private val requests = listOf(
        InterceptedRequest("GET", "https://sis.slai.edu.cn/yjsxt/xtgl/index_initMenu.html", true),
        InterceptedRequest("POST", "https://sis.slai.edu.cn/yjsxt/xskbcx/xskbcx_cxXsKb.html?gnmkdm=N253508", false)
    )

    @Test
    fun `report describes the json shape`() {
        val report = CaptureReport.build(records, requests)
        assertThat(report).contains("kbList")
        assertThat(report).contains("kcmc")
        assertThat(report).contains("xqj")
        assertThat(report).contains("rows=")
    }

    @Test
    fun `report lists every page request including iframe ones`() {
        val report = CaptureReport.build(records, requests)
        assertThat(report).contains("页面发起的所有请求")
        assertThat(report).contains("index_initMenu.html")
        assertThat(report).contains("[iframe]")
    }

    @Test
    fun `report redacts secrets`() {
        val report = CaptureReport.build(records, requests)
        assertThat(report).doesNotContain("SECRET_TICKET")
        assertThat(report).doesNotContain("20260001")
    }

    @Test
    fun `query parameter names are kept so the endpoint is identifiable`() {
        val report = CaptureReport.build(records, requests)
        assertThat(report).contains("gnmkdm=")
    }

    @Test
    fun `empty capture says what to do`() {
        val report = CaptureReport.build(emptyList(), emptyList())
        assertThat(report).contains("没有捕获到")
    }

    @Test
    fun `non json bodies are labelled`() {
        val report = CaptureReport.build(
            listOf(CaptureRecord(method = "GET", url = "https://a/b.html", body = "<html>x</html>"))
        )
        assertThat(report).contains("not-json")
    }

    @Test
    fun `summary counts both channels`() {
        assertThat(CaptureReport.summary(records, requests)).contains("XHR 1")
        assertThat(CaptureReport.summary(records, requests)).contains("页面请求 2")
    }
}
