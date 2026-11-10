package dev.cheng.keyboard.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal data class RemoteRelease(
    val version: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
)

internal sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val release: RemoteRelease, val downloaded: Boolean) : UpdatePhase
    data class Downloading(val percent: Int) : UpdatePhase
    data class NeedsPermission(val file: File) : UpdatePhase
    data class Ready(val file: File) : UpdatePhase
    data class Failed(val message: String, val askToken: Boolean) : UpdatePhase
}

internal sealed interface UpdateFetch {
    data class Ok(val release: RemoteRelease) : UpdateFetch
    data object Unauthorized : UpdateFetch
    data object NoApk : UpdateFetch
    data class Failed(val message: String) : UpdateFetch
}

internal sealed interface UpdateDownload {
    data class Ok(val file: File) : UpdateDownload
    data object Unauthorized : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

private const val LATEST = "https://api.github.com/repos/n1ght-ow/myboard/releases/latest"
private const val USER_AGENT = "myboard"

internal class UpdateClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val redirecting = http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var active: Call? = null

    fun cancel() {
        active?.cancel()
    }

    fun latest(token: String): UpdateFetch {
        val request = Request.Builder()
            .url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val call = http.newCall(request)
        active = call
        return try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> parseLatestRelease(raw)?.let { UpdateFetch.Ok(it) } ?: UpdateFetch.NoApk
                    401 -> UpdateFetch.Unauthorized
                    404 -> if (token.isBlank()) UpdateFetch.Unauthorized else UpdateFetch.Failed("没有找到发布版本。")
                    403 -> UpdateFetch.Failed(limited(raw))
                    else -> UpdateFetch.Failed(httpMessage(response.code, raw))
                }
            }
        } catch (error: IOException) {
            if (call.isCanceled()) UpdateFetch.Failed("已取消") else UpdateFetch.Failed(networkMessage(error))
        } finally {
            if (active === call) active = null
        }
    }

    fun download(token: String, release: RemoteRelease, dest: File, onProgress: (Int) -> Unit): UpdateDownload {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, dest.name + ".part")
        val request = Request.Builder()
            .url(release.assetUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val call = http.newCall(request)
        active = call
        val outcome = try {
            call.execute().use { response ->
                when (response.code) {
                    200 -> writeBody(response, partial, release.size, onProgress)
                    301, 302, 303, 307, 308 -> {
                        val location = response.header("Location")
                        if (location.isNullOrBlank()) return@use UpdateDownload.Failed("下载地址无效")
                        val resolved = response.request.url.resolve(location)?.toString()
                            ?: return@use UpdateDownload.Failed("下载地址无效")
                        response.close()
                        downloadPublic(resolved, partial, release.size, onProgress)
                    }
                    401, 403, 404 -> UpdateDownload.Unauthorized
                    else -> UpdateDownload.Failed("下载失败（${response.code}）")
                }
            }
        } catch (error: IOException) {
            partial.delete()
            if (call.isCanceled()) UpdateDownload.Failed("已取消") else UpdateDownload.Failed(networkMessage(error))
        } finally {
            if (active === call) active = null
        }
        return when (outcome) {
            is UpdateDownload.Ok -> finish(partial, dest)
            else -> {
                partial.delete()
                outcome
            }
        }
    }

    private fun downloadPublic(url: String, dest: File, size: Long, onProgress: (Int) -> Unit): UpdateDownload {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val call = redirecting.newCall(request)
        active = call
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) UpdateDownload.Failed("下载失败（${response.code}）")
                else writeBody(response, dest, size, onProgress)
            }
        } finally {
            if (active === call) active = null
        }
    }

    private fun writeBody(response: Response, dest: File, size: Long, onProgress: (Int) -> Unit): UpdateDownload {
        val body = response.body ?: return UpdateDownload.Failed("下载内容是空的")
        val total = body.contentLength().takeIf { it > 0L } ?: size
        body.byteStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var readSum = 0L
                var last = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    readSum += count
                    if (total > 0L) {
                        val percent = ((readSum * 100) / total).toInt().coerceIn(0, 99)
                        if (percent != last) {
                            last = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        if (dest.length() <= 0L) return UpdateDownload.Failed("下载内容是空的")
        onProgress(100)
        return UpdateDownload.Ok(dest)
    }

    private fun finish(partial: File, dest: File): UpdateDownload {
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
        dest.parentFile?.listFiles()?.forEach { child ->
            if (child != dest) child.delete()
        }
        return UpdateDownload.Ok(dest)
    }
}

internal fun checkForUpdate(client: UpdateClient, localVersion: String, token: String): UpdatePhase {
    return when (val result = client.latest(token)) {
        is UpdateFetch.Ok -> {
            if (isNewerVersion(result.release.version, localVersion)) {
                UpdatePhase.Available(result.release, downloaded = false)
            } else {
                UpdatePhase.UpToDate
            }
        }
        UpdateFetch.Unauthorized -> UpdatePhase.Failed(
            if (token.isBlank()) "仓库是私有的，先保存 GitHub 令牌。" else "令牌无效。",
            askToken = true,
        )
        UpdateFetch.NoApk -> UpdatePhase.Failed("这个版本没有安装包。", askToken = false)
        is UpdateFetch.Failed -> UpdatePhase.Failed(result.message, askToken = false)
    }
}

internal fun markDownloaded(context: Context, phase: UpdatePhase): UpdatePhase {
    if (phase !is UpdatePhase.Available) return phase
    val file = updateApk(context, phase.release.version)
    return phase.copy(downloaded = file.isFile && file.length() > 0L)
}

internal fun updateApk(context: Context, version: String): File {
    val safe = version.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.ifBlank { "update" }
    return File(context.cacheDir, "updates/myboard-$safe.apk")
}

internal fun installedVersionName(context: Context): String {
    val info = packageInfo(context.packageManager, context.packageName, flags = 0L)
    return info?.versionName?.substringBefore('-').orEmpty().ifBlank { "0" }
}

internal fun prepareInstall(context: Context, file: File): UpdatePhase {
    val info = packageArchive(context.packageManager, file.absolutePath) ?: run {
        file.delete()
        return UpdatePhase.Failed("安装包无法读取", askToken = false)
    }
    if (info.packageName != context.packageName) {
        file.delete()
        return UpdatePhase.Failed("安装包不是 myboard", askToken = false)
    }
    if (!signatureOk(context, info)) {
        file.delete()
        return UpdatePhase.Failed("安装包签名和当前应用不一致", askToken = false)
    }
    val remote = info.versionName?.substringBefore('-').orEmpty()
    if (remote.isNotBlank() && !isNewerVersion(remote, installedVersionName(context))) {
        return UpdatePhase.UpToDate
    }
    if (!context.packageManager.canRequestPackageInstalls()) {
        openInstallPermission(context)
        return UpdatePhase.NeedsPermission(file)
    }
    return if (openPackageInstaller(context, file)) {
        UpdatePhase.Ready(file)
    } else {
        UpdatePhase.Failed("没有打开系统安装器", askToken = false)
    }
}

internal fun openInstallPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

internal fun isNewerVersion(remote: String, local: String): Boolean {
    val left = versionParts(remote)
    val right = versionParts(local)
    val count = maxOf(left.size, right.size)
    for (index in 0 until count) {
        val diff = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}

internal fun parseLatestRelease(body: String): RemoteRelease? {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    val version = json.optString("tag_name").ifBlank { json.optString("name") }
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
    if (version.isBlank()) return null
    val assets = json.optJSONArray("assets") ?: return null
    var chosen: JSONObject? = null
    var chosenRank = -1L
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        val name = asset.optString("name")
        val type = asset.optString("content_type")
        val apk = name.endsWith(".apk", ignoreCase = true) || type == "application/vnd.android.package-archive"
        if (!apk || asset.optString("url").isBlank()) continue
        val rank = asset.optLong("size") + if (name.contains(version)) 1L shl 40 else 0L
        if (rank > chosenRank) {
            chosen = asset
            chosenRank = rank
        }
    }
    val asset = chosen ?: return null
    return RemoteRelease(
        version = version,
        notes = json.optString("body").trim(),
        assetUrl = asset.getString("url"),
        size = asset.optLong("size"),
    )
}

private fun versionParts(raw: String): List<Int> {
    return raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}

private fun openPackageInstaller(context: Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        clipData = ClipData.newRawUri("myboard", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun signatureOk(context: Context, incoming: PackageInfo): Boolean {
    val installed = packageInfo(context.packageManager, context.packageName, signingFlags()) ?: return true
    val ours = certificates(installed)
    val theirs = certificates(incoming)
    if (ours.isEmpty() || theirs.isEmpty()) return true
    return ours.any { it in theirs }
}

private fun certificates(info: PackageInfo): Set<String> {
    val raw = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        info.signatures?.map { it.toByteArray() }.orEmpty()
    }
    return raw.map { Base64.encodeToString(it, Base64.NO_WRAP) }.toSet()
}

private fun signingFlags(): Long {
    return if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES.toLong()
    }
}

private fun packageInfo(manager: PackageManager, packageName: String, flags: Long): PackageInfo? {
    return try {
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, flags.toInt())
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

private fun packageArchive(manager: PackageManager, path: String): PackageInfo? {
    val flags = signingFlags()
    return if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageArchiveInfo(path, flags.toInt())
    }
}

private fun limited(raw: String): String {
    return if (raw.contains("rate limit", ignoreCase = true)) "检查过于频繁，稍后再试。" else httpMessage(403, raw)
}

private fun httpMessage(code: Int, raw: String): String {
    val message = runCatching { JSONObject(raw).optString("message") }.getOrDefault("").take(80)
    return if (message.isBlank()) "检查失败（$code）" else "检查失败（$code）：$message"
}

private fun networkMessage(error: IOException): String {
    return if (error is UnknownHostException || error is ConnectException) "网络不可用" else "网络超时"
}
