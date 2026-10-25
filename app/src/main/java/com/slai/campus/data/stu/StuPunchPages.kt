package com.slai.campus.data.stu

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.map
import com.slai.campus.domain.attendance.AttendancePunch
import java.time.LocalDate

/** A cache replacement is allowed only after all advertised rows have been read exactly once. */
internal suspend fun readPunchPages(
    maxPages: Int = 100,
    fetchPage: suspend (Int) -> RemoteResult<StuPunchPage>
): RemoteResult<List<AttendancePunch>> {
    val rows = mutableListOf<AttendancePunch>()
    val ids = mutableSetOf<String>()
    var expectedCount: Int? = null
    for (pageNumber in 1..maxPages) {
        val result = fetchPage(pageNumber)
        if (result !is RemoteResult.Success) return result.map { it.punches }
        val page = result.data
        if (page.totalCount < 0 || (expectedCount != null && expectedCount != page.totalCount)) {
            return RemoteResult.SchemaChanged("刷卡记录总数在分页过程中发生变化，请重新刷新")
        }
        expectedCount = page.totalCount
        if (page.punches.any { !ids.add(it.id) }) {
            return RemoteResult.SchemaChanged("刷卡分页包含重复记录，已保留原有缓存")
        }
        rows += page.punches
        if (rows.size == expectedCount) return RemoteResult.Success(rows)
        if (rows.size > expectedCount || page.punches.isEmpty()) {
            return RemoteResult.SchemaChanged("刷卡分页提前结束：已读取 ${rows.size}/$expectedCount 条")
        }
    }
    return RemoteResult.SchemaChanged("刷卡记录超过分页读取上限，已保留原有缓存")
}

/** If the school ignores the date syntax, the fallback must also finish every page. */
internal suspend fun readPunchRange(
    from: LocalDate,
    to: LocalDate,
    fetch: suspend (LocalDate?, LocalDate?) -> RemoteResult<List<AttendancePunch>>
): RemoteResult<List<AttendancePunch>> {
    val filtered = fetch(from, to)
    if (filtered !is RemoteResult.Success) return filtered
    fun List<AttendancePunch>.inRange() = filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    val inRange = filtered.data.inRange()
    if (inRange.isNotEmpty()) return RemoteResult.Success(inRange)
    return fetch(null, null).map { it.inRange() }
}
