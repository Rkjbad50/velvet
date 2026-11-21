package com.velvet.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val UPSTREAM_RELEASES_URL = "https://github.com/Rkjbad50/velvet/releases"

@Serializable
internal data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

@Serializable
internal data class Release(
    val tag_name: String,
    val name: String? = null,
    val html_url: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
)

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val releaseTitle: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

internal object ReleaseCheck {
    private const val latest = "https://api.github.com/repos/Rkjbad50/velvet/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }
    private val semverRegex = Regex("""(?:v|Version\s*)?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
    private val buildTag = Regex("(?:v[0-9.]+-)?build-([0-9]+)")
    private val legacyTitle = Regex(".*[Bb]uild ([0-9]+).*")

    fun checkUpdate(http: OkHttpClient, upstreamBuild: Int, currentVersionName: String = "2.6"): UpdateInfo? {
        val request = Request.Builder().url(latest).header("Accept", "application/vnd.github+json").build()
        val call = http.newCall(request)
        call.timeout().timeout(8, TimeUnit.SECONDS)
        return runCatching {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val release = json.decodeFromString<Release>(body)
                val isNew = isNewer(body, upstreamBuild, currentVersionName)
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                val downloadUrl = apkAsset?.browserDownloadUrl ?: release.html_url ?: UPSTREAM_RELEASES_URL
                UpdateInfo(
                    hasUpdate = isNew,
                    latestVersion = release.tag_name.removePrefix("v").removePrefix("Version "),
                    releaseTitle = release.name ?: release.tag_name,
                    downloadUrl = downloadUrl,
                    releaseUrl = release.html_url ?: UPSTREAM_RELEASES_URL,
                )
            }
        }.getOrNull()
    }

    fun hasNewerBuild(http: OkHttpClient, upstreamBuild: Int, currentVersionName: String = "2.6"): Boolean {
        return checkUpdate(http, upstreamBuild, currentVersionName)?.hasUpdate ?: false
    }

    internal fun isNewer(body: String, upstreamBuild: Int, currentVersionName: String = "2.6"): Boolean {
        val release = json.decodeFromString<Release>(body)
        val tag = release.tag_name
        val name = release.name.orEmpty()

        // 1. Check build tag first if present
        val buildMatch = buildTag.find(tag) ?: legacyTitle.find(name)
        val remoteBuild = buildMatch?.groupValues?.get(1)?.toIntOrNull()
        if (remoteBuild != null && upstreamBuild > 0) {
            return remoteBuild > upstreamBuild
        }

        // 2. Compare semantic versioning
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
}
