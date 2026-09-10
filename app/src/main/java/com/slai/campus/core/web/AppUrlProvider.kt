package com.slai.campus.core.web

import com.slai.campus.BuildConfig
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The four URLs the UI needs: where to log in and where to open the original page. */
data class AppUrls(
    /**
     * SIS is not one URL but three (site / application / SSO entry), derived from the single address
     * the user typed — see [SisEndpoints] for why mixing them up breaks login *and* the timetable.
     */
    val sisEndpoints: SisEndpoints,
    val stuBase: String,
    val stuEntry: String
) {
    val sisBase: String get() = sisEndpoints.base
    val sisEntry: String get() = sisEndpoints.entry

    val sisHomePage: SchoolPage get() = SchoolPage.sisHome(sisBase)
    val sisSchedulePage: SchoolPage get() = SchoolPage.sisSchedule(sisBase)
    val sisCourseSelectionPage: SchoolPage get() = SchoolPage.sisCourseSelection(sisBase)
    val sisExamPage: SchoolPage get() = SchoolPage.sisExam(sisBase)
    val sisGradesPage: SchoolPage get() = SchoolPage.sisGrades(sisBase)
    val stuHomePage: SchoolPage get() = SchoolPage.stuHome(stuBase)
    val stuCheckInPage: SchoolPage get() = SchoolPage.stuCheckIn(stuBase)

    fun entryUrlFor(system: SchoolSystem): String = when (system) {
        SchoolSystem.SIS -> sisEntry
        SchoolSystem.STU -> stuEntry
    }

    fun baseUrlFor(system: SchoolSystem): String = when (system) {
        SchoolSystem.SIS -> sisBase
        SchoolSystem.STU -> stuBase
    }
}

/**
 * Resolves the school URLs, honouring the user's overrides from Settings.
 *
 * Keeping this in one place matters because the URLs appear in three roles — the login WebView, the
 * "open the original page" fallback, and the native adapter — and they must never drift apart.
 *
 * The defaults come from `BuildConfig` rather than from the `data` adapters, so this stays in `core`
 * without a `core -> data` dependency.
 */
@Singleton
class AppUrlProvider @Inject constructor(
    private val sessionStore: SessionStore
) {
    val urls: Flow<AppUrls> = sessionStore.baseUrls.map { (sis, stu) -> resolve(sis, stu) }

    suspend fun current(): AppUrls = resolve(
        sessionStore.baseUrl(SchoolSystem.SIS),
        sessionStore.baseUrl(SchoolSystem.STU)
    )

    private fun resolve(sisOverride: String?, stuOverride: String?): AppUrls {
        val sis = SisEndpoints.of(sisOverride)
        val stuBase = stuOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_STU_BASE_URL
        return AppUrls(
            sisEndpoints = sis,
            stuBase = stuBase.trimEnd('/'),
            stuEntry = BuildConfig.DEFAULT_STU_ENTRY_URL
        )
    }
}
