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
    private val buildTag = Regex("(?:v[0-9.]+-)?build-([0-9]+)")
    private val legacyTitle = Regex(".*[Bb]uild ([0-9]+).*")

    @Serializable
    private data class Release(val tag_name: String, val name: String? = null)

    fun hasNewerBuild(http: OkHttpClient, upstreamBuild: Int): Boolean {
        val request = Request.Builder().url(latest).header("Accept", "application/vnd.github+json").build()
        val call = http.newCall(request)
        call.timeout().timeout(8, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned ${response.code}")
            isNewer(response.body?.string() ?: throw IOException("Missing release data"), upstreamBuild)
        }
    }

    internal fun isNewer(body: String, upstreamBuild: Int): Boolean {
        val release = json.decodeFromString<Release>(body)
        val match = buildTag.find(release.tag_name) ?: release.name?.let { legacyTitle.find(it) }
        val build = match?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Unrecognized VELVET release")
        return build > upstreamBuild
    }
}
