package com.velvet.app.data

import com.velvet.app.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val UPSTREAM_RELEASES_URL = "https://github.com/Rkjbad50/velvet/releases"
internal const val NEWPIPE_RELEASES_URL = "https://github.com/TeamNewPipe/NewPipeExtractor/releases"

@Serializable
internal data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
)

@Serializable
internal data class GitHubRelease(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val html_url: String? = null,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

// Legacy alias for compatibility with existing tests and calls
internal typealias Release = GitHubRelease

enum class UpdateTrigger {
    NONE,
    APP,
    EXTRACTOR,
    BOTH,
}

data class UpdateInfo(
    val hasUpdate: Boolean,
    val trigger: UpdateTrigger = UpdateTrigger.NONE,
    val latestVersion: String,
    val bundledVersion: String = BuildConfig.VERSION_NAME,
    val latestExtractorVersion: String = BuildConfig.NEWPIPE_VERSION,
    val bundledExtractorVersion: String = BuildConfig.NEWPIPE_VERSION,
    val releaseTitle: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = UPSTREAM_RELEASES_URL,
    val expectedSha256: String? = null,
    val releaseUrl: String = UPSTREAM_RELEASES_URL,
    val isPrerelease: Boolean = false,
    val isRateLimited: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
)

internal object ReleaseCheck {
    private const val APP_RELEASES_API = "https://api.github.com/repos/Rkjbad50/velvet/releases"
    private const val NEWPIPE_RELEASES_API = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("""(?:SHA-?256|sha256)[:\s*`]+([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
    private val standaloneSha256Regex = Regex("""\b([a-fA-F0-9]{64})\b""")

    // 1-hour cache
    private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour
    private var cachedUpdateInfo: UpdateInfo? = null
    private var lastCheckTimestamp: Long = 0L
    private var lastIncludedPrereleases: Boolean = false

    /**
     * Checks for updates against both VELVET GitHub repository and upstream TeamNewPipe/NewPipeExtractor.
     * Caches successful responses for 1 hour; handles rate limits (HTTP 403/429) and offline states gracefully.
     */
    fun checkUpdate(
        http: OkHttpClient,
        upstreamBuild: Int = 0,
        currentVersionName: String = BuildConfig.VERSION_NAME,
        includePrereleases: Boolean = false,
        forceRefresh: Boolean = false,
        clock: () -> Long = { System.currentTimeMillis() },
    ): UpdateInfo {
        val now = clock()
        val cached = cachedUpdateInfo
        if (!forceRefresh && cached != null && (now - lastCheckTimestamp < CACHE_DURATION_MS) && lastIncludedPrereleases == includePrereleases) {
            return cached
        }

        var rateLimited = false
        var offline = false
        var errorMsg: String? = null

        // 1. Fetch App Releases
        var appReleases: List<GitHubRelease> = emptyList()
        try {
            val req = Request.Builder()
                .url(APP_RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val call = http.newCall(req)
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    rateLimited = true
                    errorMsg = "GitHub API rate limit reached. Cached information shown."
                } else if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    appReleases = json.decodeFromString<List<GitHubRelease>>(body)
                } else {
                    errorMsg = "HTTP error ${response.code} from GitHub Releases"
                }
            }
        } catch (e: IOException) {
            offline = true
            errorMsg = "Offline: Check your internet connection"
        } catch (e: Exception) {
            errorMsg = e.message ?: "Failed to check for updates"
        }

        // 2. Fetch NewPipeExtractor Latest Release
        var latestExtractorTag: String? = null
        if (!rateLimited && !offline) {
            try {
                val req = Request.Builder()
                    .url(NEWPIPE_RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val call = http.newCall(req)
                call.timeout().timeout(10, TimeUnit.SECONDS)
                call.execute().use { response ->
                    if (response.code == 403 || response.code == 429) {
                        rateLimited = true
                        errorMsg = "GitHub API rate limit reached."
                    } else if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val rel = json.decodeFromString<GitHubRelease>(body)
                        latestExtractorTag = rel.tag_name
                    }
                }
            } catch (e: IOException) {
                offline = true
                errorMsg = "Offline: Check your internet connection"
            } catch (_: Exception) {
            }
        }

        // If rate-limited or offline and we have a cached result, return the cached result annotated
        if ((rateLimited || offline) && cached != null) {
            return cached.copy(
                isRateLimited = rateLimited,
                isOffline = offline,
                errorMessage = errorMsg,
            )
        }

        // Filter app releases by pre-release setting
        val eligibleReleases = if (includePrereleases) {
            appReleases
        } else {
            appReleases.filter { !it.prerelease && !it.tag_name.contains("-alpha", true) && !it.tag_name.contains("-beta", true) && !it.tag_name.contains("-rc", true) }
        }

        val targetRelease = eligibleReleases.firstOrNull() ?: appReleases.firstOrNull()
        val latestAppTag = targetRelease?.tag_name.orEmpty()
        val bundledAppVer = currentVersionName
        val bundledExtractorVer = BuildConfig.NEWPIPE_VERSION

        val appHasUpdate = if (latestAppTag.isNotEmpty()) {
            SemVer.isNewer(latestAppTag, bundledAppVer)
        } else {
            false
        }

        val extractorHasUpdate = if (latestExtractorTag != null) {
            SemVer.isNewer(latestExtractorTag!!, bundledExtractorVer)
        } else {
            false
        }

        val trigger = when {
            appHasUpdate && extractorHasUpdate -> UpdateTrigger.BOTH
            appHasUpdate -> UpdateTrigger.APP
            extractorHasUpdate -> UpdateTrigger.EXTRACTOR
            else -> UpdateTrigger.NONE
        }

        val apkAsset = targetRelease?.assets?.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val downloadUrl = apkAsset?.browserDownloadUrl ?: targetRelease?.html_url ?: UPSTREAM_RELEASES_URL
        val releaseNotes = targetRelease?.body.orEmpty()
        val sha256 = extractSha256(releaseNotes)

        val result = UpdateInfo(
            hasUpdate = appHasUpdate || extractorHasUpdate,
            trigger = trigger,
            latestVersion = if (latestAppTag.isNotEmpty()) latestAppTag.removePrefix("v").removePrefix("Version ") else bundledAppVer,
            bundledVersion = bundledAppVer,
            latestExtractorVersion = latestExtractorTag ?: bundledExtractorVer,
            bundledExtractorVersion = bundledExtractorVer,
            releaseTitle = targetRelease?.name ?: latestAppTag,
            releaseNotes = releaseNotes,
            downloadUrl = downloadUrl,
            expectedSha256 = sha256,
            releaseUrl = targetRelease?.html_url ?: UPSTREAM_RELEASES_URL,
            isPrerelease = targetRelease?.prerelease ?: false,
            isRateLimited = rateLimited,
            isOffline = offline,
            errorMessage = errorMsg,
        )

        if (!rateLimited && !offline) {
            cachedUpdateInfo = result
            lastCheckTimestamp = now
            lastIncludedPrereleases = includePrereleases
        }

        return result
    }

    /** Extracts a 64-character hex SHA-256 string from release notes. */
    fun extractSha256(notes: String): String? {
        val match = sha256Regex.find(notes)
        if (match != null) return match.groupValues[1]
        // Fallback to standalone hex string if annotated
        return standaloneSha256Regex.find(notes)?.groupValues?.get(1)
    }

    fun hasNewerBuild(http: OkHttpClient, upstreamBuild: Int, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        return checkUpdate(http, upstreamBuild, currentVersionName).hasUpdate
    }

    /**
     * Legacy evaluation function preserved for existing unit tests.
     */
    internal fun isNewer(body: String, upstreamBuild: Int, currentVersionName: String = "2.6"): Boolean {
        val release = json.decodeFromString<GitHubRelease>(body)
        val tag = release.tag_name
        val name = release.name.orEmpty()

        val buildRegex = Regex("(?:v[0-9.]+-)?build-([0-9]+)")
        val legacyTitleRegex = Regex(".*[Bb]uild ([0-9]+).*")

        val buildMatch = buildRegex.find(tag) ?: legacyTitleRegex.find(name)
        val remoteBuild = buildMatch?.groupValues?.get(1)?.toIntOrNull()
        if (remoteBuild != null && upstreamBuild > 0) {
            return remoteBuild > upstreamBuild
        }

        val semverRegex = Regex("""(?:v|Version\s*)?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
        val remoteMatch = semverRegex.find(tag) ?: semverRegex.find(name)
        val currentMatch = semverRegex.find(currentVersionName)
        if (remoteMatch != null && currentMatch != null) {
            val rMaj = remoteMatch.groupValues[1].toIntOrNull() ?: 0
            val rMin = remoteMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val rPatch = remoteMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            val cMaj = currentMatch.groupValues[1].toIntOrNull() ?: 0
            val cMin = currentMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val cPatch = currentMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            if (rMaj != cMaj) return rMaj > cMaj
            if (rMin != cMin) return rMin > cMin
            return rPatch > cPatch
        }

        throw IOException("Unrecognized VELVET release")
    }

    fun clearCache() {
        cachedUpdateInfo = null
        lastCheckTimestamp = 0L
    }
}
