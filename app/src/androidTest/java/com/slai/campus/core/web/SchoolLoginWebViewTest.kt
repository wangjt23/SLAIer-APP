package com.slai.campus.core.web

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slai.campus.core.session.SchoolCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/** Exercise real Android WebView layout/JavaScript off screen, with local forms and fake credentials. */
@RunWith(AndroidJUnit4::class)
class SchoolLoginWebViewTest {
    @Test fun twoPageFormSubmitsExpectedAccountAndPassword() = checkForm("test@example.invalid", false)
    @Test fun rememberedPasswordPageReplacesHiddenPreviousAccount() = checkForm("2026000000", true)

    private fun checkForm(account: String, passwordFirst: Boolean) = runBlocking {
        withContext(Dispatchers.Main) {
            val view = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                val loaded = CompletableDeferred<Unit>()
                view.settings.configureForSchool()
                view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1080, 1920)
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { loaded.complete(Unit) }
                }
                // submit() only records a local result: no password is sent to any server.
                view.loadDataWithBaseURL("https://sts.slai.edu.cn/adfs/oauth2/authorize", fixture(passwordFirst), "text/html", "UTF-8", null)
                withTimeout(15_000) { loaded.await() }
                // HTML password inputs are single-line; use valid special characters, not a newline.
                val credentials = SchoolCredentials(account, "test-only'\\\"password")
                if (!passwordFirst) {
                    assertEquals("username", view.script(SchoolLoginScript.inspect()))
                    assertEquals("submitted", view.script(SchoolLoginScript.submit(credentials, false)))
                }
                assertEquals("password", view.script(SchoolLoginScript.inspect()))
                assertEquals("submitted", view.script(SchoolLoginScript.submit(credentials, true)))
                val result = JSONObject(view.script("JSON.stringify(window.posted)"))
                assertEquals(if (account.contains('@')) account else "slai\\$account", result.getString("UserName"))
                assertEquals(credentials.password, result.getString("Password"))
                assertEquals(1, result.getInt("count"))
            } finally { view.destroy() }
        }
    }

    private suspend fun WebView.script(script: String): String = withTimeout(5_000) {
        suspendCancellableCoroutine { c ->
            evaluateJavascript(script) { raw ->
                if (c.isActive) c.resume(JSONArray("[$raw]").getString(0))
            }
        }
    }

    private fun fixture(passwordFirst: Boolean) = """
        <!doctype html><html><body>
        <div id="usernamePage" style="display:${if (passwordFirst) "none" else "block"}">
          <form id="loginFormPaginated" action="/adfs/oauth2/authorize">
            <input id="userNameInput" name="UserName" type="email" value="previous@example.invalid">
            <input id="kmsiInput" name="Kmsi" type="checkbox" value="true">
          </form>
          <span id="nextButton" onclick="next()">Next</span>
        </div>
        <div id="passwordPage" style="display:${if (passwordFirst) "block" else "none"}">
          <form id="loginForm" action="/adfs/oauth2/authorize">
            <input id="userNameInputHolder" name="UserName" type="hidden" value="previous@example.invalid">
            <input id="passwordInput" name="Password" type="password">
            <span id="submitButton" onclick="submitLogin()">Sign in</span>
          </form>
        </div>
        <span role="alert" style="visibility:hidden">hidden stale error</span>
        <script>
          function next() {
            userNameInputHolder.value = userNameInput.value;
            usernamePage.style.display = 'none'; passwordPage.style.display = 'block';
          }
          function submitLogin() {
            if (!userNameInput.value || !passwordInput.value) return;
            if (!/[@\\]/.test(userNameInput.value)) userNameInputHolder.value = 'slai\\' + userNameInput.value;
            window.posted = {UserName: userNameInputHolder.value, Password: passwordInput.value, count: (window.posted ? window.posted.count : 0) + 1};
          }
        </script></body></html>
    """.trimIndent()
}
