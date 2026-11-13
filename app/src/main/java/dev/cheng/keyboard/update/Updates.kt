package dev.cheng.keyboard.update

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class RemoteRelease(
    val version: String,
    val notes: String,
    val assetUrl: String,
    val size: Long,
    val versionCode: Int = 0,
    val sha256: String = "",
    val preferDirectGithub: Boolean = false,
)

internal sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val release: RemoteRelease, val downloaded: Boolean) : UpdatePhase
    data class Downloading(val percent: Int, val routeLabel: String = "") : UpdatePhase
    data class NeedsPermission(val file: File, val version: String = "") : UpdatePhase
    data class Ready(val file: File, val message: String = "系统安装界面已唤起；若未弹出，可点击下方按钮重试。") : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

internal sealed interface UpdateFetch {
    data class Ok(val release: RemoteRelease) : UpdateFetch
    data object NoApk : UpdateFetch
    data class Failed(val message: String) : UpdateFetch
}

internal sealed interface UpdateDownload {
    data class Ok(val file: File) : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

private const val REPO_OWNER = "n1ght-ow"
private const val REPO_NAME = "myboard"
private const val GITHUB_REPO_BASE = "https://github.com/$REPO_OWNER/$REPO_NAME"
private const val GITHUB_API_LATEST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
private const val GITHUB_RELEASE_MANIFEST = "$GITHUB_REPO_BASE/releases/latest/download/update.json"
private const val GITHUB_RAW_MANIFEST = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/update.json"
private const val GITHUB_LATEST_REDIRECT = "$GITHUB_REPO_BASE/releases/latest"
private const val JSDELIVR_PACKAGE_API = "https://data.jsdelivr.com/v1/package/gh/$REPO_OWNER/$REPO_NAME"
private const val JSDELIVR_PATH = "/gh/$REPO_OWNER/$REPO_NAME@main/update.json"
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) N1ghtKey-Updater/2.0"
private const val ACTION_INSTALL_STATUS = "dev.cheng.keyboard.action.UPDATE_INSTALL_STATUS"
private const val EXTRA_APK_PATH = "apk_path"

internal val MIRROR_PREFIXES = listOf(
    "https://ghfast.top/",
    "https://ghproxy.net/",
    "https://gh-proxy.com/",
)

private val JSDELIVR_HOSTS = listOf(
    "https://testingcf.jsdelivr.net",
    "https://cdn.jsdelivr.net",
    "https://gcore.jsdelivr.net",
)

private val LOCAL_PROXY_PORTS = listOf(
    7890 to Proxy.Type.HTTP,
    10809 to Proxy.Type.HTTP,
    10808 to Proxy.Type.SOCKS,
    2080 to Proxy.Type.SOCKS,
)

internal object InstallCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var foregroundActivity: WeakReference<Activity>? = null
    @Volatile
    var statusListener: ((UpdatePhase) -> Unit)? = null

    fun attachActivity(activity: Activity) {
        foregroundActivity = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (foregroundActivity?.get() === activity) {
            foregroundActivity = null
        }
    }

    fun launchActivity(context: Context, intent: Intent): Boolean {
        val activity = foregroundActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity != null) {
            val launched = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun launchConfirmation(context: Context, confirmIntent: Intent): Boolean {
        return launchActivity(context, confirmIntent)
    }

    fun notifyPhase(phase: UpdatePhase) {
        mainHandler.post {
            statusListener?.invoke(phase)
        }
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        val apkFile = apkPath.takeIf { it.isNotBlank() }?.let(::File)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = extractConfirmIntent(intent)
                if (confirmIntent != null) {
                    val ok = InstallCoordinator.launchConfirmation(context, confirmIntent)
                    if (!ok && apkFile != null && apkFile.isFile) {
                        openSystemApkInstaller(context, apkFile)
                    }
                } else if (apkFile != null && apkFile.isFile) {
                    openSystemApkInstaller(context, apkFile)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                apkFile?.delete()
                InstallCoordinator.notifyPhase(UpdatePhase.UpToDate)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (apkFile != null && apkFile.isFile) {
                    InstallCoordinator.notifyPhase(
                        UpdatePhase.Ready(apkFile, "已取消安装，可点击下方按钮重新安装。"),
                    )
                }
            }
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                if (apkFile != null && apkFile.isFile) {
                    // Fall back to direct system APK viewer if session install was rejected by OEM ROM
                    val opened = openSystemApkInstaller(context, apkFile)
                    if (opened) {
                        InstallCoordinator.notifyPhase(
                            UpdatePhase.Ready(apkFile, "已切换为系统安装器打开安装包。"),
                        )
                    } else {
                        val hint = if (detail.isNotBlank()) "安装未完成：$detail" else "安装未完成，请重试。"
                        InstallCoordinator.notifyPhase(UpdatePhase.Failed(hint))
                    }
                }
            }
        }
    }

    private fun extractConfirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}

private class AndroidDynamicProxySelector(context: Context?) : ProxySelector() {
    private val appContext = context?.applicationContext

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        val cm = appContext?.getSystemService(ConnectivityManager::class.java)
        val sysProxy = runCatching { cm?.defaultProxy }.getOrNull()
        val host = sysProxy?.host?.trim().orEmpty()
        val port = sysProxy?.port ?: -1
        if (host.isNotEmpty() && port in 1..65535 && !isExcluded(uri.host.orEmpty(), sysProxy?.exclusionList)) {
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)), Proxy.NO_PROXY)
        }
        val jvmDefault = runCatching { getDefault()?.select(uri) }.getOrNull()
        return if (!jvmDefault.isNullOrEmpty()) jvmDefault else listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private fun isExcluded(targetHost: String, exclusions: Array<String>?): Boolean {
        if (targetHost.isEmpty() || exclusions.isNullOrEmpty()) return false
        val lower = targetHost.lowercase()
        return exclusions.any { pattern ->
            val p = pattern.trim().lowercase().removePrefix("*.")
            p.isNotEmpty() && (lower == p || lower.endsWith(".$p"))
        }
    }
}

private data class MetadataProbe(
    val name: String,
    val url: String,
    val client: OkHttpClient,
    val directGithub: Boolean,
    val cachedCdn: Boolean,
    val redirectTagProbe: Boolean = false,
)

private data class ProbeOutcome(
    val release: RemoteRelease?,
    val liveSource: Boolean,
    val error: String? = null,
)

internal class UpdateClient(context: Context? = null) {
    private val defaultHttp = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .proxySelector(AndroidDynamicProxySelector(context))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val directHttp = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val noRedirectDefaultHttp = defaultHttp.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val noRedirectDirectHttp = directHttp.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val activeCalls = CopyOnWriteArrayList<Call>()

    @Volatile
    private var canceled = false

    fun cancel() {
        canceled = true
        val snapshot = activeCalls.toList()
        activeCalls.clear()
        snapshot.forEach { runCatching { it.cancel() } }
    }

    private fun resetStateForNewTask() {
        canceled = false
        defaultHttp.connectionPool.evictAll()
        directHttp.connectionPool.evictAll()
    }

    fun latest(localVersion: String = "0", localVersionCode: Int = 0): UpdateFetch {
        resetStateForNewTask()
        val minuteBucket = System.currentTimeMillis() / 60_000L
        val rawBust = "$GITHUB_RAW_MANIFEST?t=$minuteBucket"
        val releaseBust = "$GITHUB_RELEASE_MANIFEST?t=$minuteBucket"

        val probes = buildList {
            // Primary zero-manifest probes covering both proxy-enabled (GitHub direct + rate-limit fallback) and China direct (domestic API/tag mirrors + CDN)
            add(MetadataProbe("github-api", GITHUB_API_LATEST, defaultHttp, directGithub = true, cachedCdn = false))
            add(MetadataProbe("gh-proxy-api-direct", "https://gh-proxy.com/$GITHUB_API_LATEST", directHttp, directGithub = false, cachedCdn = false))
            add(MetadataProbe("gh-proxy-api-default", "https://gh-proxy.com/$GITHUB_API_LATEST", defaultHttp, directGithub = false, cachedCdn = false))
            add(MetadataProbe("github-latest-tag", GITHUB_LATEST_REDIRECT, noRedirectDefaultHttp, directGithub = true, cachedCdn = false, redirectTagProbe = true))
            add(MetadataProbe("ghfast-latest-tag", "https://ghfast.top/$GITHUB_LATEST_REDIRECT", noRedirectDirectHttp, directGithub = false, cachedCdn = false, redirectTagProbe = true))
            add(MetadataProbe("ghproxy-latest-tag", "https://ghproxy.net/$GITHUB_LATEST_REDIRECT", noRedirectDirectHttp, directGithub = false, cachedCdn = false, redirectTagProbe = true))
            add(MetadataProbe("jsdelivr-pkg", JSDELIVR_PACKAGE_API, directHttp, directGithub = false, cachedCdn = true))
            // Optional update.json manifest probes if attached in future releases
            add(MetadataProbe("github-release-manifest", releaseBust, defaultHttp, directGithub = true, cachedCdn = false))
            add(MetadataProbe("ghfast-release-manifest", "https://ghfast.top/$GITHUB_RELEASE_MANIFEST", directHttp, directGithub = false, cachedCdn = false))
            add(MetadataProbe("github-raw-manifest", rawBust, defaultHttp, directGithub = true, cachedCdn = false))
            for (cdnHost in JSDELIVR_HOSTS) {
                add(MetadataProbe("jsdelivr", "$cdnHost$JSDELIVR_PATH", directHttp, directGithub = false, cachedCdn = true))
            }
        }

        val raceResult = runMetadataRace(probes, localVersion, localVersionCode)
        if (raceResult is UpdateFetch.Ok || canceled) {
            return if (canceled) UpdateFetch.Failed("已取消") else raceResult
        }

        // Fallback: probe active localhost proxy ports (Clash 7890 / v2rayNG 10809 / 10808 / Sing-box 2080)
        // in case the user runs a local proxy without system proxy or excluded the IME from VPN per-app routing.
        val localClients = detectOpenLocalProxies()
        if (localClients.isNotEmpty() && !canceled) {
            val localProbes = ArrayList<MetadataProbe>(localClients.size * 3)
            for ((label, proxyClient) in localClients) {
                val noRedirProxy = proxyClient.newBuilder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                localProbes += MetadataProbe("$label-api", GITHUB_API_LATEST, proxyClient, directGithub = true, cachedCdn = false)
                localProbes += MetadataProbe("$label-latest-tag", GITHUB_LATEST_REDIRECT, noRedirProxy, directGithub = true, cachedCdn = false, redirectTagProbe = true)
                localProbes += MetadataProbe("$label-manifest", releaseBust, proxyClient, directGithub = true, cachedCdn = false)
            }
            val viaLocalProxy = runMetadataRace(localProbes, localVersion, localVersionCode)
            if (viaLocalProxy is UpdateFetch.Ok) return viaLocalProxy
        }

        return if (canceled) UpdateFetch.Failed("已取消") else raceResult
    }

    private fun runMetadataRace(
        probes: List<MetadataProbe>,
        localVersion: String,
        localVersionCode: Int,
    ): UpdateFetch {
        if (probes.isEmpty()) return UpdateFetch.Failed("未配置更新源")
        val poolSize = minOf(6, probes.size)
        val executor = Executors.newFixedThreadPool(poolSize) { runnable ->
            Thread(runnable, "n1ghtkey-update-check").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<ProbeOutcome>(executor)
        return try {
            for (probe in probes) {
                completion.submit { executeMetadataProbe(probe) }
            }
            var best: RemoteRelease? = null
            var sawLiveSource = false
            var lastError: String? = null
            var remaining = probes.size
            var enrichDeadlineMs = Long.MAX_VALUE

            while (remaining > 0 && !canceled) {
                val now = System.currentTimeMillis()
                if (now >= enrichDeadlineMs && best != null) break
                val waitMs = if (enrichDeadlineMs == Long.MAX_VALUE) {
                    12_000L
                } else {
                    (enrichDeadlineMs - now).coerceAtLeast(1L)
                }
                val future = completion.poll(waitMs, TimeUnit.MILLISECONDS) ?: break
                remaining--
                val outcome = runCatching { future.get() }.getOrNull() ?: continue
                if (outcome.error != null) lastError = outcome.error
                val candidate = outcome.release ?: continue
                if (outcome.liveSource) sawLiveSource = true
                best = pickBetterRelease(best, candidate)

                val currentBest = best!!
                val newer = isNewerRelease(currentBest, localVersion, localVersionCode)
                if (newer) {
                    if (currentBest.notes.isNotBlank() && currentBest.sha256.isNotBlank()) {
                        break
                    }
                    if (enrichDeadlineMs == Long.MAX_VALUE) {
                        enrichDeadlineMs = System.currentTimeMillis() + 450L
                    }
                } else if (sawLiveSource) {
                    // Confirmed up-to-date by at least one live uncached endpoint; give 300ms grace for other in-flight live probes
                    if (enrichDeadlineMs == Long.MAX_VALUE) {
                        enrichDeadlineMs = System.currentTimeMillis() + 300L
                    }
                } else {
                    // Only cached CDN responded UpToDate so far; wait up to 2.5s for a live endpoint in case a new release just went out
                    if (enrichDeadlineMs == Long.MAX_VALUE) {
                        enrichDeadlineMs = System.currentTimeMillis() + 2_500L
                    }
                }
            }

            if (best != null) {
                UpdateFetch.Ok(best)
            } else {
                UpdateFetch.Failed(lastError ?: "无法连接更新源，请检查网络后重试。")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            UpdateFetch.Failed("已取消")
        } finally {
            cancelActiveCallsOnly()
            executor.shutdownNow()
        }
    }

    private fun executeMetadataProbe(probe: MetadataProbe): ProbeOutcome {
        if (canceled) return ProbeOutcome(null, liveSource = false, error = "已取消")
        val reqBuilder = Request.Builder()
            .url(probe.url)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
        if (probe.url.endsWith("/releases/latest") && !probe.redirectTagProbe) {
            reqBuilder.header("Accept", "application/vnd.github+json")
            reqBuilder.header("X-GitHub-Api-Version", "2022-11-28")
        } else {
            reqBuilder.header("Accept", "application/json, text/plain, */*")
        }

        val call = probe.client.newCall(reqBuilder.build())
        activeCalls += call
        return try {
            call.execute().use { response ->
                if (probe.redirectTagProbe) {
                    val location = response.header("Location").orEmpty()
                    val finalUrl = location.ifBlank { response.request.url.toString() }
                    val fromTag = parseLatestRedirectTag(finalUrl)?.copy(preferDirectGithub = probe.directGithub)
                    return@use if (fromTag != null) {
                        ProbeOutcome(fromTag, liveSource = true)
                    } else {
                        ProbeOutcome(null, liveSource = false, error = "未解析到最新版本标签")
                    }
                }
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val msg = if (response.code == 403 && raw.contains("rate limit", ignoreCase = true)) {
                        "GitHub 接口限流，正在自动切换备用通道"
                    } else {
                        "请求失败（${response.code}）"
                    }
                    return@use ProbeOutcome(null, liveSource = false, error = msg)
                }
                val raw = response.body?.string().orEmpty()
                val parsed = parseLatestRelease(raw)?.copy(
                    preferDirectGithub = probe.directGithub,
                )
                if (parsed != null) {
                    ProbeOutcome(parsed, liveSource = !probe.cachedCdn)
                } else {
                    ProbeOutcome(null, liveSource = false, error = "更新信息格式无效")
                }
            }
        } catch (error: IOException) {
            ProbeOutcome(null, liveSource = false, error = networkMessage(error))
        } finally {
            activeCalls.remove(call)
        }
    }

    fun download(release: RemoteRelease, dest: File, onProgress: (Int, String) -> Unit): UpdateDownload {
        resetStateForNewTask()
        dest.parentFile?.mkdirs()
        if (dest.isFile && dest.length() > 0L && verifyDownloadedFile(dest, release)) {
            onProgress(100, "本地缓存已就绪")
            return UpdateDownload.Ok(dest)
        }

        val partial = File(dest.parentFile, dest.name + ".part")
        if (partial.exists() && release.size > 0L && partial.length() > release.size) {
            partial.delete()
        }

        val canonicalUrl = normalizeCanonicalGithubApkUrl(release)
        val routes = buildDownloadRoutes(canonicalUrl, release.preferDirectGithub)
        var lastError = "下载失败，请稍后重试"

        for ((routeIndex, route) in routes.withIndex()) {
            if (canceled) {
                return UpdateDownload.Failed("已取消")
            }
            val dlHttp = route.client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .build()
            val allowSlowSwitch = routeIndex < routes.lastIndex
            val attempt = downloadFromSingleRoute(dlHttp, route.url, route.label, partial, release, allowSlowSwitch, onProgress)
            when (attempt) {
                is UpdateDownload.Ok -> {
                    if (verifyDownloadedFile(partial, release)) {
                        return finish(partial, dest, onProgress)
                    }
                    // Discard if header is invalid or size already reached/exceeded expected size; keep truncated prefix for Range resume
                    if (!hasZipApkHeader(partial) || (release.size > 0L && partial.length() >= release.size)) {
                        partial.delete()
                    }
                    lastError = "安装包校验未通过，正在尝试其他线路"
                }
                is UpdateDownload.Failed -> {
                    if (canceled) return UpdateDownload.Failed("已取消")
                    lastError = attempt.message
                }
            }
        }

        return UpdateDownload.Failed(lastError)
    }

    private data class DownloadRoute(
        val label: String,
        val url: String,
        val client: OkHttpClient,
    )

    private fun buildDownloadRoutes(canonicalUrl: String, preferDirectGithub: Boolean): List<DownloadRoute> {
        val isGithubUrl = canonicalUrl.startsWith("https://github.com/", ignoreCase = true)
        if (!isGithubUrl) {
            return listOf(
                DownloadRoute("直连更新源", canonicalUrl, defaultHttp),
                DownloadRoute("备用直连", canonicalUrl, directHttp),
            )
        }

        val directRoutes = listOf(
            DownloadRoute("GitHub 官方通道", canonicalUrl, defaultHttp),
        )
        val mirrorRoutes = buildList {
            for ((idx, prefix) in MIRROR_PREFIXES.withIndex()) {
                val mirrorUrl = prefix + canonicalUrl
                val num = idx + 1
                // Try direct socket first for domestic mirrors (bypasses dead/slow proxy), then default client
                add(DownloadRoute("国内加速镜像 $num", mirrorUrl, directHttp))
                add(DownloadRoute("加速镜像 $num", mirrorUrl, defaultHttp))
            }
        }
        val localProxyRoutes = detectOpenLocalProxies().map { (name, proxyClient) ->
            DownloadRoute("本机代理 ($name)", canonicalUrl, proxyClient)
        }

        val ordered = ArrayList<DownloadRoute>()
        if (preferDirectGithub) {
            ordered += directRoutes
            ordered += localProxyRoutes
            ordered += mirrorRoutes
            ordered += DownloadRoute("GitHub 直连尝试", canonicalUrl, directHttp)
        } else {
            ordered += mirrorRoutes
            ordered += directRoutes
            ordered += localProxyRoutes
            ordered += DownloadRoute("GitHub 直连尝试", canonicalUrl, directHttp)
        }
        return ordered.distinctBy { "${it.url}|${it.client === directHttp}|${it.label}" }
    }

    private fun downloadFromSingleRoute(
        client: OkHttpClient,
        url: String,
        routeLabel: String,
        partial: File,
        release: RemoteRelease,
        allowSlowSwitch: Boolean,
        onProgress: (Int, String) -> Unit,
    ): UpdateDownload {
        val existingBytes = if (partial.isFile) partial.length().coerceAtLeast(0L) else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        val call = client.newCall(reqBuilder.build())
        activeCalls += call
        return try {
            call.execute().use { response ->
                when (response.code) {
                    200 -> streamApkBody(response, partial, resumeOffset = 0L, expectedTotal = release.size, routeLabel = routeLabel, allowSlowSwitch = allowSlowSwitch, onProgress = onProgress)
                    206 -> {
                        val contentRange = response.header("Content-Range").orEmpty().trim()
                        val offset = when {
                            existingBytes <= 0L -> 0L
                            contentRange.isEmpty() || contentRange.startsWith("bytes $existingBytes-", ignoreCase = true) -> existingBytes
                            contentRange.startsWith("bytes 0-", ignoreCase = true) -> 0L
                            else -> {
                                partial.delete()
                                return@use UpdateDownload.Failed("$routeLabel 断点范围不匹配")
                            }
                        }
                        streamApkBody(response, partial, resumeOffset = offset, expectedTotal = release.size, routeLabel = routeLabel, allowSlowSwitch = allowSlowSwitch, onProgress = onProgress)
                    }
                    416 -> {
                        // Range already fully satisfied or stale partial size
                        if (existingBytes > 0L && verifyDownloadedFile(partial, release)) {
                            UpdateDownload.Ok(partial)
                        } else {
                            partial.delete()
                            UpdateDownload.Failed("断点续传范围失效（416）")
                        }
                    }
                    else -> UpdateDownload.Failed("$routeLabel 响应异常（${response.code}）")
                }
            }
        } catch (error: IOException) {
            if (call.isCanceled() || canceled) {
                UpdateDownload.Failed("已取消")
            } else {
                UpdateDownload.Failed("$routeLabel：${networkMessage(error)}")
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun streamApkBody(
        response: Response,
        partial: File,
        resumeOffset: Long,
        expectedTotal: Long,
        routeLabel: String,
        allowSlowSwitch: Boolean,
        onProgress: (Int, String) -> Unit,
    ): UpdateDownload {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if ("text/html" in contentType || "application/json" in contentType) {
            return UpdateDownload.Failed("$routeLabel 返回了非安装包页面")
        }

        val body = response.body ?: return UpdateDownload.Failed("$routeLabel 返回内容为空")
        val contentLength = body.contentLength()
        val total = when {
            contentLength > 0L && resumeOffset > 0L -> resumeOffset + contentLength
            contentLength > 0L -> contentLength
            else -> expectedTotal
        }

        body.byteStream().use { input ->
            if (resumeOffset == 0L) {
                // Verify ZIP / APK magic header (PK\x03\x04) BEFORE overwriting any existing partial file!
                val header = ByteArray(4)
                var headerRead = 0
                while (headerRead < 4) {
                    val r = input.read(header, headerRead, 4 - headerRead)
                    if (r < 0) break
                    headerRead += r
                }
                if (!isZipApkMagic(header, headerRead)) {
                    return UpdateDownload.Failed("$routeLabel 返回的数据不是合法 APK")
                }
                FileOutputStream(partial, false).use { output ->
                    output.write(header, 0, headerRead)
                    pumpStream(input, output::write, startBytes = headerRead.toLong(), total = total, routeLabel = routeLabel, allowSlowSwitch = allowSlowSwitch, onProgress = onProgress)
                }
            } else {
                RandomAccessFile(partial, "rw").use { raf ->
                    raf.seek(resumeOffset)
                    pumpStream(input, { buf, off, len -> raf.write(buf, off, len) }, startBytes = resumeOffset, total = total, routeLabel = routeLabel, allowSlowSwitch = allowSlowSwitch, onProgress = onProgress)
                }
            }
        }

        if (partial.length() <= 4L) {
            partial.delete()
            return UpdateDownload.Failed("下载内容为空")
        }
        if (contentLength > 0L && partial.length() != resumeOffset + contentLength) {
            return UpdateDownload.Failed("$routeLabel 连接中断，正在断点续传")
        }
        return UpdateDownload.Ok(partial)
    }

    private inline fun pumpStream(
        input: java.io.InputStream,
        writeChunk: (ByteArray, Int, Int) -> Unit,
        startBytes: Long,
        total: Long,
        routeLabel: String,
        allowSlowSwitch: Boolean,
        onProgress: (Int, String) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var readSum = startBytes
        val routeStartBytes = startBytes
        val routeStartMs = System.currentTimeMillis()
        var lastPercent = -1
        if (total > 0L) {
            val initialPct = ((readSum * 100L) / total).toInt().coerceIn(0, 99)
            lastPercent = initialPct
            onProgress(initialPct, routeLabel)
        } else {
            onProgress(0, routeLabel)
        }
        while (!canceled) {
            val count = input.read(buffer)
            if (count < 0) break
            writeChunk(buffer, 0, count)
            readSum += count
            if (total > 0L) {
                val percent = ((readSum * 100L) / total).toInt().coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent, routeLabel)
                }
            }
            if (allowSlowSwitch) {
                val elapsedMs = System.currentTimeMillis() - routeStartMs
                val routeBytes = readSum - routeStartBytes
                if (elapsedMs >= 15_000L && routeBytes * 1000L < elapsedMs * 12L * 1024L) {
                    throw IOException("当前线路过慢，正在切换加速通道")
                }
            }
        }
        if (canceled) throw IOException("Canceled")
    }

    private fun finish(partial: File, dest: File, onProgress: (Int, String) -> Unit): UpdateDownload {
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
        dest.parentFile?.listFiles()?.forEach { child ->
            if (child != dest) child.delete()
        }
        onProgress(100, "校验完成")
        return UpdateDownload.Ok(dest)
    }

    private fun detectOpenLocalProxies(): List<Pair<String, OkHttpClient>> {
        val found = ArrayList<Pair<String, OkHttpClient>>(2)
        for ((port, type) in LOCAL_PROXY_PORTS) {
            val open = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 90)
                    true
                }
            }.getOrDefault(false)
            if (open) {
                val proxy = Proxy(type, InetSocketAddress("127.0.0.1", port))
                val client = directHttp.newBuilder().proxy(proxy).build()
                found += "127.0.0.1:$port" to client
            }
        }
        return found
    }

    private fun cancelActiveCallsOnly() {
        val snapshot = activeCalls.toList()
        activeCalls.clear()
        snapshot.forEach { runCatching { it.cancel() } }
    }
}

internal fun isZipApkMagic(bytes: ByteArray, length: Int = bytes.size): Boolean {
    return length >= 4 &&
        bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x4B.toByte() &&
        bytes[2] == 0x03.toByte() &&
        bytes[3] == 0x04.toByte()
}

private fun hasZipApkHeader(file: File): Boolean {
    if (!file.isFile || file.length() < 4L) return false
    return runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            val n = input.read(magic)
            isZipApkMagic(magic, n)
        }
    }.getOrDefault(false)
}

internal fun hasZipEndOfCentralDirectory(file: File): Boolean {
    val len = file.length()
    if (!file.isFile || len < 22L) return false
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val window = minOf(len, 65_558L).toInt()
            raf.seek(len - window)
            val tail = ByteArray(window)
            raf.readFully(tail)
            for (i in window - 22 downTo 0) {
                if (
                    tail[i] == 0x50.toByte() &&
                    tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() &&
                    tail[i + 3] == 0x06.toByte()
                ) {
                    return@use true
                }
            }
            false
        }
    }.getOrDefault(false)
}

internal fun verifyDownloadedFile(file: File, release: RemoteRelease): Boolean {
    if (!file.isFile || file.length() <= 1024L) return false
    if (!hasZipApkHeader(file)) return false
    if (!hasZipEndOfCentralDirectory(file)) return false
    if (release.size > 0L && file.length() != release.size) return false
    if (release.sha256.isNotBlank()) {
        val actual = sha256Hex(file)
        if (!actual.equals(release.sha256.trim(), ignoreCase = true)) return false
    }
    return true
}

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun normalizeCanonicalGithubApkUrl(release: RemoteRelease): String {
    var url = release.assetUrl.trim().removePrefix("/")
    for (prefix in MIRROR_PREFIXES) {
        if (url.startsWith(prefix, ignoreCase = true)) {
            url = url.substring(prefix.length).removePrefix("/")
            break
        }
    }
    if (url.startsWith("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/assets/")) {
        val v = release.version.removePrefix("v").removePrefix("V")
        if (v.isNotBlank()) {
            return "$GITHUB_REPO_BASE/releases/download/v$v/myboard-$v.apk"
        }
    }
    return url
}

private fun pickBetterRelease(current: RemoteRelease?, incoming: RemoteRelease): RemoteRelease {
    if (current == null) return incoming
    if (isNewerVersion(incoming.version, current.version)) return incoming
    if (isNewerVersion(current.version, incoming.version)) return current
    if (incoming.versionCode > current.versionCode) return incoming
    if (current.versionCode > incoming.versionCode) return current
    return RemoteRelease(
        version = current.version,
        notes = current.notes.ifBlank { incoming.notes },
        assetUrl = if (current.assetUrl.startsWith("https://github.com/")) current.assetUrl else incoming.assetUrl.ifBlank { current.assetUrl },
        size = if (current.size > 0L) current.size else incoming.size,
        versionCode = maxOf(current.versionCode, incoming.versionCode),
        sha256 = current.sha256.ifBlank { incoming.sha256 },
        preferDirectGithub = current.preferDirectGithub || incoming.preferDirectGithub,
    )
}

internal fun isNewerRelease(release: RemoteRelease, localVersion: String, localVersionCode: Int = 0): Boolean {
    if (isNewerVersion(release.version, localVersion)) return true
    if (isNewerVersion(localVersion, release.version)) return false
    return localVersionCode > 0 && release.versionCode > localVersionCode
}

internal fun checkForUpdate(
    client: UpdateClient,
    localVersion: String,
    localVersionCode: Int = 0,
): UpdatePhase {
    return when (val result = client.latest(localVersion, localVersionCode)) {
        is UpdateFetch.Ok -> {
            if (isNewerRelease(result.release, localVersion, localVersionCode)) {
                UpdatePhase.Available(result.release, downloaded = false)
            } else {
                UpdatePhase.UpToDate
            }
        }
        UpdateFetch.NoApk -> UpdatePhase.Failed("该版本暂未附带安装包。")
        is UpdateFetch.Failed -> UpdatePhase.Failed(result.message)
    }
}

internal fun markDownloaded(context: Context, phase: UpdatePhase): UpdatePhase {
    if (phase !is UpdatePhase.Available) return phase
    val file = updateApk(context, phase.release.version)
    val ready = verifyDownloadedFile(file, phase.release)
    return phase.copy(downloaded = ready)
}

internal fun updateApk(context: Context, version: String): File {
    val safe = version.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.ifBlank { "update" }
    return File(context.cacheDir, "updates/myboard-$safe.apk")
}

internal fun findDownloadedNewerApk(context: Context): Pair<File, String>? {
    val dir = File(context.cacheDir, "updates")
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) && f.length() > 1024L }
        ?: return null
    val localName = installedVersionName(context)
    val localCode = installedVersionCode(context)
    for (file in files.sortedByDescending { it.lastModified() }) {
        val info = packageArchiveBasic(context.packageManager, file.absolutePath) ?: continue
        if (info.packageName != context.packageName) continue
        val ver = info.versionName?.substringBefore('-').orEmpty()
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        val newer = (ver.isNotBlank() && isNewerVersion(ver, localName)) || (localCode > 0 && code > localCode)
        if (newer && signatureOk(context, file.absolutePath)) {
            return file to ver.ifBlank { code.toString() }
        }
    }
    return null
}

internal fun installedVersionName(context: Context): String {
    val info = packageInfo(context.packageManager, context.packageName, flags = 0L)
    return info?.versionName?.substringBefore('-').orEmpty().ifBlank { "0" }
}

internal fun installedVersionCode(context: Context): Int {
    val info = packageInfo(context.packageManager, context.packageName, flags = 0L) ?: return 0
    return if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        info.versionCode
    }
}

internal fun prepareInstall(context: Context, file: File): UpdatePhase {
    if (!file.isFile || file.length() <= 1024L) {
        return UpdatePhase.Failed("安装包不存在或已损坏，请重新检查更新。")
    }
    val info = packageArchiveBasic(context.packageManager, file.absolutePath) ?: run {
        file.delete()
        return UpdatePhase.Failed("安装包无法解析，已清理缓存，请重新下载。")
    }
    if (info.packageName != context.packageName) {
        file.delete()
        return UpdatePhase.Failed("安装包不是 N1ghtKey")
    }
    if (!signatureOk(context, file.absolutePath)) {
        file.delete()
        return UpdatePhase.Failed("安装包签名与当前应用不一致")
    }
    val remoteName = info.versionName?.substringBefore('-').orEmpty()
    val remoteCode = if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        info.versionCode
    }
    val localName = installedVersionName(context)
    val localCode = installedVersionCode(context)
    val isNewer = (remoteName.isNotBlank() && isNewerVersion(remoteName, localName)) ||
        (localCode > 0 && remoteCode > localCode)
    if (!isNewer) {
        file.delete()
        return UpdatePhase.UpToDate
    }
    if (!context.packageManager.canRequestPackageInstalls()) {
        openInstallPermission(context)
        return UpdatePhase.NeedsPermission(file, remoteName)
    }
    val launched = if (Build.VERSION.SDK_INT >= 31 && isSelfInstalled(context)) {
        installViaPackageSession(context, file) || openSystemApkInstaller(context, file)
    } else {
        openSystemApkInstaller(context, file) || installViaPackageSession(context, file)
    }
    return if (launched) {
        UpdatePhase.Ready(file)
    } else {
        UpdatePhase.Failed("未能唤起系统安装器，请检查系统安装权限。")
    }
}

private fun isSelfInstalled(context: Context): Boolean {
    val installerPkg = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
    return installerPkg == context.packageName
}

internal fun openInstallPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )
    if (context is Activity) {
        runCatching { context.startActivity(intent) }
    } else {
        InstallCoordinator.launchActivity(context, intent)
    }
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

internal fun parseLatestRedirectTag(url: String): RemoteRelease? {
    val marker = "/releases/tag/"
    val idx = url.indexOf(marker, ignoreCase = true)
    if (idx < 0) return null
    val rawTag = url.substring(idx + marker.length)
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore('/')
        .trim()
    val cleanVersion = rawTag
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
    if (cleanVersion.isBlank() || !cleanVersion[0].isDigit()) return null
    val tag = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$cleanVersion"
    return RemoteRelease(
        version = cleanVersion,
        notes = "",
        assetUrl = "$GITHUB_REPO_BASE/releases/download/$tag/myboard-$cleanVersion.apk",
        size = 0L,
    )
}

internal fun parseLatestRelease(body: String): RemoteRelease? {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }

    // Format 1: Lightweight update.json manifest
    val directApkUrl = json.optString("apkUrl").ifBlank { json.optString("downloadUrl") }.trim()
    val directVersion = json.optString("version").trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
    if (directVersion.isNotBlank() && directApkUrl.isNotBlank()) {
        return RemoteRelease(
            version = directVersion,
            notes = json.optString("notes").ifBlank { json.optString("body") }.trim(),
            assetUrl = directApkUrl,
            size = json.optLong("size", 0L),
            versionCode = json.optInt("versionCode", 0),
            sha256 = json.optString("sha256").trim().removePrefix("sha256:"),
        )
    }

    // Format 2: GitHub Releases API JSON
    val version = json.optString("tag_name").ifBlank { json.optString("name") }
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
    if (version.isNotBlank()) {
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            var chosen: JSONObject? = null
            var chosenRank = -1L
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val type = asset.optString("content_type")
                val apk = name.endsWith(".apk", ignoreCase = true) || type == "application/vnd.android.package-archive"
                val downloadUrl = asset.optString("browser_download_url").ifBlank { asset.optString("url") }
                if (!apk || downloadUrl.isBlank()) continue
                val rank = asset.optLong("size") + if (name.contains(version)) 1L shl 40 else 0L
                if (rank > chosenRank) {
                    chosen = asset
                    chosenRank = rank
                }
            }
            val asset = chosen ?: return null
            val finalUrl = asset.optString("browser_download_url").ifBlank { asset.optString("url") }
            val digest = asset.optString("digest").trim()
            val sha256 = if (digest.startsWith("sha256:", ignoreCase = true)) digest.substring(7).trim() else ""
            return RemoteRelease(
                version = version,
                notes = json.optString("body").trim(),
                assetUrl = finalUrl,
                size = asset.optLong("size"),
                sha256 = sha256,
            )
        }
    }

    // Format 3: jsDelivr GitHub package versions JSON
    val versions = json.optJSONArray("versions") ?: return null
    var bestVer = ""
    for (index in 0 until versions.length()) {
        val rawVer = versions.optString(index).trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (rawVer.isBlank() || !rawVer[0].isDigit()) continue
        if (bestVer.isEmpty() || isNewerVersion(rawVer, bestVer)) {
            bestVer = rawVer
        }
    }
    if (bestVer.isBlank()) return null
    return RemoteRelease(
        version = bestVer,
        notes = "",
        assetUrl = "$GITHUB_REPO_BASE/releases/download/v$bestVer/myboard-$bestVer.apk",
        size = 0L,
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

private fun installViaPackageSession(context: Context, file: File): Boolean {
    val installer = context.packageManager.packageInstaller
    var sessionId = -1
    return try {
        // Clean up any stale abandoned sessions owned by us
        runCatching {
            for (info in installer.mySessions) {
                if (!info.isActive) installer.abandonSession(info.sessionId)
            }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (file.length() > 0L) setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("n1ghtkey-update.apk", 0, file.length()).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(context.applicationContext, InstallStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                putExtra(EXTRA_APK_PATH, file.absolutePath)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                sessionId,
                callbackIntent,
                flags,
            )
            session.commit(pendingIntent.intentSender)
        }
        true
    } catch (_: Exception) {
        if (sessionId >= 0) {
            runCatching { installer.abandonSession(sessionId) }
        }
        false
    }
}

internal fun openSystemApkInstaller(context: Context, file: File): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        clipData = ClipData.newRawUri("N1ghtKey", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    // Explicitly grant read URI permission to all matching system package installer activities
    runCatching {
        val resolves = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (resolve in resolves) {
            val pkg = resolve.activityInfo?.packageName ?: continue
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    return try {
        if (context is Activity) {
            context.startActivity(intent)
            true
        } else {
            InstallCoordinator.launchActivity(context, intent)
        }
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}

private fun signatureOk(context: Context, archivePath: String): Boolean {
    val installed = packageInfo(context.packageManager, context.packageName, signingFlags()) ?: return true
    val ours = certificates(installed)
    if (ours.isEmpty()) return true
    val theirs = archiveCertificates(context.packageManager, archivePath)
    if (theirs.isEmpty()) return true
    return ours.any { it in theirs }
}

private fun archiveCertificates(manager: PackageManager, path: String): Set<String> {
    if (Build.VERSION.SDK_INT >= 28) {
        val modern = runCatching {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            val info = if (Build.VERSION.SDK_INT >= 33) {
                manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageArchiveInfo(path, flags.toInt())
            }
            info?.let(::certificates).orEmpty()
        }.getOrDefault(emptySet())
        if (modern.isNotEmpty()) return modern
    }
    return runCatching {
        @Suppress("DEPRECATION")
        val legacyFlags = PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(legacyFlags.toLong()))
        } else {
            manager.getPackageArchiveInfo(path, legacyFlags)
        }
        @Suppress("DEPRECATION")
        info?.signatures?.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }?.toSet().orEmpty()
    }.getOrDefault(emptySet())
}

private fun certificates(info: PackageInfo): Set<String> {
    val raw = if (Build.VERSION.SDK_INT >= 28) {
        val signers = info.signingInfo?.apkContentsSigners
            ?: info.signingInfo?.signingCertificateHistory
        signers?.map { it.toByteArray() }.orEmpty()
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

private fun packageArchiveBasic(manager: PackageManager, path: String): PackageInfo? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(path, 0)
        }
    }.getOrNull()
}

private fun networkMessage(error: IOException): String {
    val msg = error.message.orEmpty()
    if (msg.contains("过慢") || msg.contains("切换")) return msg
    return when {
        error is java.net.UnknownHostException -> "域名解析失败"
        error is java.net.ConnectException -> "连接被拒绝或网络不可达"
        error is javax.net.ssl.SSLException -> "安全连接握手受阻"
        error is java.net.SocketTimeoutException -> "连接超时"
        msg.contains("Canceled", ignoreCase = true) -> "已取消"
        else -> "网络请求中断"
    }
}
