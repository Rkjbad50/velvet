package com.tunehub.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val UPSTREAM_RELEASES_URL = "https://github.com/Rkjbad50/velvet/releases"

internal object ReleaseCheck {
    private const val latest = "https://api.github.com/repos/Rkjbad50/velvet/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }
    private val semverRegex = Regex("""(?:v|Version\s*)?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
    private val buildTag = Regex("(?:v[0-9.]+-)?build-([0-9]+)")
    private val legacyTitle = Regex(".*[Bb]uild ([0-9]+).*")

    @Serializable
    private data class Release(val tag_name: String, val name: String? = null)

    fun hasNewerBuild(http: OkHttpClient, upstreamBuild: Int, currentVersionName: String = "2.0"): Boolean {
        val request = Request.Builder().url(latest).header("Accept", "application/vnd.github+json").build()
        val call = http.newCall(request)
        call.timeout().timeout(8, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned ${response.code}")
            isNewer(response.body?.string() ?: throw IOException("Missing release data"), upstreamBuild, currentVersionName)
        }
    }

    internal fun isNewer(body: String, upstreamBuild: Int, currentVersionName: String = "2.0"): Boolean {
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
