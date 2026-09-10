package com.slai.campus.di

import android.content.Context
import androidx.room.Room
import com.slai.campus.core.common.AppDispatchers
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.database.AppDatabase
import com.slai.campus.core.database.ScheduleDao
import com.slai.campus.core.database.SyncMetaDao
import com.slai.campus.core.network.HttpClientFactory
import com.slai.campus.core.network.SisApiClient
import com.slai.campus.core.network.SisWebClient
import com.slai.campus.core.network.StuApiClient
import com.slai.campus.core.network.StuWebClient
import com.slai.campus.core.network.WebViewCookieInterceptor
import com.slai.campus.core.session.SessionProbe
import com.slai.campus.data.attendance.AttendanceRepositoryImpl
import com.slai.campus.data.schedule.ScheduleRepositoryImpl
import com.slai.campus.data.sis.SisSessionDetector
import com.slai.campus.data.stu.StuSessionDetector
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.domain.schedule.ScheduleRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Unqualified `Context` for the small number of classes that only need a context to reach a
     * system service (WebView, AlarmManager, ConnectivityManager, DataStore).
     */
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideDispatchers(): AppDispatchers = AppDispatchers()

    /**
     * The one clock the app reads. Injecting it (instead of calling LocalDate.now() inline) keeps
     * "today's classes", week numbers and reminder scheduling testable.
     */
    @Provides
    @Singleton
    fun provideClock(): java.time.Clock = java.time.Clock.system(java.time.ZoneId.systemDefault())

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(dispatchers: AppDispatchers): CoroutineDispatcher = dispatchers.io

    @Provides
    @Singleton
    fun provideDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // The cache is a pure derivative of the server: if the schema changes, rebuilding it is
            // always safe, and losing it must never lose anything the user typed.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideScheduleDao(database: AppDatabase): ScheduleDao = database.scheduleDao()

    @Provides
    fun provideSyncMetaDao(database: AppDatabase): SyncMetaDao = database.syncMetaDao()

    @Provides
    fun provideAttendanceDao(database: AppDatabase): com.slai.campus.core.database.AttendanceDao =
        database.attendanceDao()

    @Provides
    fun provideAttendanceMetaDao(database: AppDatabase): com.slai.campus.core.database.AttendanceMetaDao =
        database.attendanceMetaDao()

    @Provides
    fun provideAttendancePunchDao(database: AppDatabase): com.slai.campus.core.database.AttendancePunchDao =
        database.attendancePunchDao()

    @Provides
    @Singleton
    fun provideOkHttpBase(): OkHttpClient.Builder = OkHttpClient.Builder()
        // Bounded hard: a blocking OkHttp call ignores coroutine cancellation, so the ONLY thing
        // that can stop a dead-network refresh is these timeouts. Keep the worst case small.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // No HTTP/2 push, no proxy authenticator, no cookie jar: cookies come from the WebView.
        .followRedirects(true)

    @Provides
    @Singleton
    @SisApiClient
    fun provideSisApiClient(
        builder: OkHttpClient.Builder,
        interceptor: WebViewCookieInterceptor
    ): OkHttpClient = HttpClientFactory.apiClient(builder, interceptor)

    @Provides
    @Singleton
    @SisWebClient
    fun provideSisWebClient(
        builder: OkHttpClient.Builder,
        interceptor: WebViewCookieInterceptor
    ): OkHttpClient = HttpClientFactory.webClient(builder, interceptor)

    @Provides
    @Singleton
    @StuApiClient
    fun provideStuApiClient(
        builder: OkHttpClient.Builder,
        interceptor: WebViewCookieInterceptor
    ): OkHttpClient = HttpClientFactory.apiClient(builder, interceptor)

    @Provides
    @Singleton
    @StuWebClient
    fun provideStuWebClient(
        builder: OkHttpClient.Builder,
        interceptor: WebViewCookieInterceptor
    ): OkHttpClient = HttpClientFactory.webClient(builder, interceptor)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindScheduleRepository(impl: ScheduleRepositoryImpl): ScheduleRepository

    @Binds
    @Singleton
    abstract fun bindAttendanceRepository(impl: AttendanceRepositoryImpl): AttendanceRepository

    /** Both session probes are contributed into one set consumed by `SessionManager`. */
    @Binds
    @IntoSet
    abstract fun bindSisSessionProbe(impl: SisSessionDetector): SessionProbe

    @Binds
    @IntoSet
    abstract fun bindStuSessionProbe(impl: StuSessionDetector): SessionProbe
}
