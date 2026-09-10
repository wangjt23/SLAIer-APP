package com.slai.campus.data.sis

import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionProbe
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "is the SIS session still alive?" with a lightweight protected GET.
 *
 * This is intentionally separate from `refresh()`: the login-recovery flow needs a cheap check after
 * the user finishes authenticating in the WebView, and the home screen needs it before deciding
 * whether to show the "重新登录" banner.
 */
@Singleton
class SisSessionDetector @Inject constructor(
    private val remote: SisRemoteDataSource,
    private val sessionStore: SessionStore
) : SessionProbe {

    override val system: SchoolSystem = SchoolSystem.SIS

    override suspend fun probe(): SessionState {
        val baseUrl = SisConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.SIS))
        return remote.probeSession(baseUrl)
    }
}
