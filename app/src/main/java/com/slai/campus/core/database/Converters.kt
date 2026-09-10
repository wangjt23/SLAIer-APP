package com.slai.campus.core.database

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Date/time storage as primitives so the schema stays stable and queryable:
 *  - `LocalDate`  -> epoch day (sortable, timezone-free)
 *  - `LocalTime`  -> second of day (sortable, timezone-free)
 *
 * Storing formatted strings would have made "today's classes" depend on the locale; storing instants
 * would have made a 09:00 class shift when the phone's timezone changes.
 */
class Converters {

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun localTimeToSecondOfDay(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun secondOfDayToLocalTime(value: Int?): LocalTime? = value?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    /**
     * 闸机时间戳存成 ISO 字符串（本地挂钟时间）。
     *
     * 不能用 epoch：闸机记录是"本地墙上时间"，跨时区/夏令时换算会把它挪到错误的一天。
     */
    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDateTime(value: String?): LocalDateTime? =
        value?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
}
