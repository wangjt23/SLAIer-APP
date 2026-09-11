package com.slai.campus.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager.NameNotFoundException
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.network.UpdateClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 下载好的安装包：文件 + 边下边算的 SHA-256。 */
data class DownloadedApk(val file: File, val sha256: String, val bytes: Long)

/** 下载失败的原因，够 UI 说一句人话即可。 */
sealed interface DownloadOutcome {
    data class Ok(val apk: DownloadedApk) : DownloadOutcome
    data class Failed(val reason: String) : DownloadOutcome
}

/**
 * 下载 APK。
 *
 * 哈希是**边下边算**的：这样"下完再校验"不会多读一遍 2.7 MB，也不会出现
 * "文件写完了但校验时才发现尾巴被截断"的中间态。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @UpdateClient private val client: OkHttpClient,
    @ApplicationContext private val context: Context
) {

    /** 安装包放在 cacheDir 下：系统安装器读得到，用户清缓存时也会自动回收。 */
    fun targetFile(fileName: String): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        return File(dir, fileName)
    }

    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (received: Long, total: Long) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val target = targetFile(fileName)
        val temp = File(target.parentFile, "$fileName.part")
        temp.delete()

        try {
            val request = Request.Builder().url(url).header("User-Agent", "SLAIer-Android").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadOutcome.Failed("HTTP ${response.code}")
                }
                val body = response.body ?: return@withContext DownloadOutcome.Failed("响应为空")
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L

                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            onProgress(received, if (total > 0) total else -1L)
                        }
                    }
                }

                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                AppLog.i("update apk downloaded: $fileName ($received bytes)")
                DownloadOutcome.Ok(DownloadedApk(target, hash, received))
            }
        } catch (e: Exception) {
            temp.delete()
            AppLog.w("update download failed: ${e.javaClass.simpleName}")
            DownloadOutcome.Failed(e.javaClass.simpleName)
        }
    }

    /** 清掉上一次留下的安装包（安装完成或用户取消后调用）。 */
    fun clean() {
        runCatching { File(context.cacheDir, "update").listFiles()?.forEach { it.delete() } }
    }
}

/** 下载到的 APK 的元信息。 */
data class ApkInfo(
    val versionCode: Long,
    val versionName: String?,
    val signerHashes: Set<String>
)

/** 校验结论。 */
sealed interface VerifyOutcome {
    data object Ok : VerifyOutcome
    data class Rejected(val failure: com.slai.campus.domain.update.UpdateFailure, val detail: String?) : VerifyOutcome
}

/**
 * 安装前的校验。
 *
 * 三层，缺一不可：
 *  1. **SHA-256** 对 `SHA256SUMS.txt` —— 防截断/传输损坏（也能发现镜像站给了个旧文件）；
 *  2. **versionCode** 大于当前 —— 防"标签写错、包其实是旧版"；
 *  3. **签名与本机一致** —— 这是**信任边界**：签名不同的包理论上装不上（系统会拒绝覆盖），
 *     但在这里先比对，才能把"应用未安装"翻译成一句人话。
 *
 * 注意 `getPackageArchiveInfo` 需要 `GET_SIGNING_CERTIFICATES`；minSdk 26 上直接可用。
 */
@Singleton
class ApkVerifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun inspect(file: File): ApkInfo? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info: PackageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return null
        info.applicationInfo?.sourceDir = file.absolutePath
        val signingInfo = info.signingInfo ?: return null
        val signers = signingInfo.apkContentsSigners ?: return null
        return ApkInfo(
            versionCode = info.longVersionCode,
            versionName = info.versionName,
            signerHashes = signers.mapNotNull { it.toByteArray().sha256Hex() }.toSet()
        )
    }

    /** 本机已安装版本的签名（`null` 表示读不到，此时跳过签名比对而不是误判）。 */
    fun installedSignerHashes(): Set<String>? = runCatching {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        info.signingInfo?.apkContentsSigners?.mapNotNull { it.toByteArray().sha256Hex() }?.toSet()
    }.getOrElse { error ->
        if (error is NameNotFoundException) AppLog.w("installed signing info unavailable") else AppLog.w("signing read failed")
        null
    }

    fun verify(
        file: File,
        expectedSha256: String?,
        downloadedSha256: String,
        currentVersionCode: Long
    ): VerifyOutcome {
        if (!expectedSha256.isNullOrBlank() && !expectedSha256.equals(downloadedSha256, ignoreCase = true)) {
            return VerifyOutcome.Rejected(
                com.slai.campus.domain.update.UpdateFailure.CHECKSUM_MISMATCH,
                "期望 ${expectedSha256.take(8)}… 实际 ${downloadedSha256.take(8)}…"
            )
        }

        val info = inspect(file)
            ?: return VerifyOutcome.Rejected(com.slai.campus.domain.update.UpdateFailure.UNKNOWN, "读不出安装包信息")

        if (info.versionCode <= currentVersionCode) {
            return VerifyOutcome.Rejected(
                com.slai.campus.domain.update.UpdateFailure.NOT_NEWER,
                "包内 versionCode=${info.versionCode}，当前=${currentVersionCode}"
            )
        }

        val installed = installedSignerHashes()
        if (installed != null && info.signerHashes.isNotEmpty() && info.signerHashes.none { it in installed }) {
            return VerifyOutcome.Rejected(
                com.slai.campus.domain.update.UpdateFailure.SIGNATURE_MISMATCH,
                "签名与本机版本不一致"
            )
        }

        return VerifyOutcome.Ok
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}

/** 安装调起失败的原因。 */
sealed interface InstallStart {
    data object Started : InstallStart
    data object NeedsPermission : InstallStart
    data class Failed(val reason: String) : InstallStart
}

/**
 * 调起系统安装器。
 *
 * 用 `PackageInstaller.Session` 而不是 `ACTION_VIEW` + FileProvider：APK 是**写进 session** 的，
 * 不需要把文件通过 FileProvider 暴露出去，而且能拿到"用户装了 / 取消了 / 失败"的回调。
 * 代价是必须声明 `REQUEST_INSTALL_PACKAGES`，并在首次引导用户去系统里允许"安装未知应用"。
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(file: File): InstallStart = withContext(Dispatchers.IO) {
        if (!canInstall()) return@withContext InstallStart.NeedsPermission

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            AppLog.w("createSession failed: ${e.javaClass.simpleName}")
            return@withContext InstallStart.Failed(e.javaClass.simpleName)
        }

        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = UpdateInstallReceiver.statusIntent(context)
                session.commit(intent)
            }
            AppLog.i("update install session committed: $sessionId")
            InstallStart.Started
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            AppLog.w("install session failed: ${e.javaClass.simpleName}")
            InstallStart.Failed(e.javaClass.simpleName)
        }
    }
}
