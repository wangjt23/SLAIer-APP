package com.slai.campus.data.stu

import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionProbe
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight "is the STU session alive?" probe, independent of the SIS session. */
@Singleton
class StuSessionDetector @Inject constructor(
    private val remote: StuRemoteDataSource,
    private val sessionStore: SessionStore
) : SessionProbe {

    override val system: SchoolSystem = SchoolSystem.STU

    override suspend fun probe(): SessionState {
        val baseUrl = StuConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.STU))
        return remote.probeSession(baseUrl)
    }
}
