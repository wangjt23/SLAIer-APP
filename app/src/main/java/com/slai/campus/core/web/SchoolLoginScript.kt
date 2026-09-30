package com.slai.campus.core.web

import com.slai.campus.core.session.SchoolCredentials
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** Credentials can only enter the top-level school's AD FS login form, never a provider/iframe. */
object SchoolLoginScript {
    fun isTrustedLogin(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("https", true) && uri.host.equals("sts.slai.edu.cn", true) &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (uri.path.equals("/adfs/ls", true) || uri.path.lowercase().startsWith("/adfs/ls/") ||
                uri.path.equals("/adfs/oauth2/authorize", true))
    }.getOrDefault(false)

    fun inspect(): String = script("inspect", null)
    fun submit(credentials: SchoolCredentials, password: Boolean): String =
        script(if (password) "password" else "username", credentials)

    private fun script(mode: String, credentials: SchoolCredentials?): String = """
        (function() {
            function trusted(u) {
                return u.protocol === 'https:' && u.hostname === 'sts.slai.edu.cn' &&
                    (!u.port || u.port === '443') && !u.username && !u.password &&
                    (/^\/adfs\/ls(?:\/|${'$'})/i.test(u.pathname) || /^\/adfs\/oauth2\/authorize${'$'}/i.test(u.pathname));
            }
            if (window.top !== window || !trusted(new URL(window.location.href))) return 'refused';
            function visible(e) { return e && !e.disabled && e.getClientRects().length > 0; }
            function find(selector) { return Array.from(document.querySelectorAll(selector)).find(visible); }
            if (find('input[name*="captcha" i], input[id*="captcha" i], iframe[src*="recaptcha"], iframe[src*="hcaptcha"], input[autocomplete="one-time-code"]')) return 'manual';
            if (Array.from(document.querySelectorAll('#errorText, #error, .alert-danger, [role="alert"]')).some(function(e) {
                return visible(e) && e.textContent.trim().length > 0;
            })) return 'manual';
            var username = find('#userNameInput, #username, input[name="UserName"], input[name="loginfmt"], input[autocomplete="username"]');
            var password = find('input[type="password"]');
            var field = password || username;
            if (!field || !field.form || !trusted(new URL(field.form.action || window.location.href, window.location.href))) return 'none';
            // The school's paginated AD FS Next span lives outside the username form.
            var button = document.getElementById(password ? 'submitButton' : 'nextButton');
            if (!visible(button)) button = Array.from(field.form.querySelectorAll('#submitButton, #idSIButton9, #nextButton, #next, button[type="submit"], input[type="submit"]')).find(visible);
            if (!button) return 'manual';
            var mode = '$mode';
            if (mode === 'inspect') return password ? 'password' : 'username';
            if ((mode === 'password') !== !!password) return 'none';
            var account = ${credentials?.username?.let { JsonPrimitive(it).toString() } ?: "null"};
            var secret = ${credentials?.password?.let { JsonPrimitive(it).toString() } ?: "null"};
            function fill(e, value) {
                Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(e, value);
                e.dispatchEvent(new Event('input', {bubbles: true}));
                e.dispatchEvent(new Event('change', {bubbles: true}));
            }
            if (username) fill(username, account);
            if (password) fill(password, secret);
            // The school's persistent-session control is optional; cookie expiry stays server-owned.
            var keep = find('#kmsiInput, input[name="Kmsi"]');
            if (keep && keep.type === 'checkbox' && !keep.checked) keep.click();
            button.click();
            return 'submitted';
        })();
    """.trimIndent()
}

/** One username step and one password step per flow, including page reloads and redirects. */
class SchoolLoginAttempt {
    private var usernameSubmitted = false
    var passwordSubmitted = false
        private set
    var stopped = false
        private set

    fun claim(password: Boolean): Boolean {
        if (stopped || passwordSubmitted || (!password && usernameSubmitted)) return false
        if (password) passwordSubmitted = true else usernameSubmitted = true
        return true
    }

    fun stop() { stopped = true }
}
