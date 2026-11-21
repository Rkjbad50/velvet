package com.velvet.app.diagnostics

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.velvet.app.VelvetApp
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.YtDownloader
import com.velvet.app.playback.PlaybackService
import com.velvet.app.playback.songUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.net.InetAddress
import java.util.Locale

/**
 * Executes the 6 sequential diagnostic checks specified in W3-A:
 * 1) Internet check (generate_204)
 * 2) DNS lookup for youtube.com and music.youtube.com
 * 3) Extractor initialization
 * 4) Resolve a fixed known videoId
 * 5) HEAD/range on the chosen stream URL
 * 6) ExoPlayer prepares and buffers 3 s
 * Ends with a plain-language VERDICT.
 */
object DiagnosticRunner {

    const val FIXED_VIDEO_ID = "dQw4w9WgXcQ" // Rick Astley - Never Gonna Give You Up

    data class State(
        val isRunning: Boolean = false,
        val currentStep: DiagnosticStep? = null,
        val results: List<CheckResult> = emptyList(),
        val verdict: DiagnosticsVerdict? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun resetExtractor(context: Context) {
        val locale = Locale.getDefault()
        NewPipe.init(
            YtDownloader(VelvetApp.http),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
    }

    suspend fun runDiagnostics(context: Context): DiagnosticReport = withContext(Dispatchers.IO) {
        _state.value = State(isRunning = true, currentStep = DiagnosticStep.INTERNET, results = emptyList(), verdict = null)
        DiagnosticsLogger.i("Diagnostics", "Starting diagnostics run...")

        val checkResults = mutableListOf<CheckResult>()
        var resolvedStream: StreamResolver.Resolved? = null

        // 1. Internet (generate_204)
        val internetResult = checkInternet()
        checkResults.add(internetResult)
        _state.value = _state.value.copy(currentStep = DiagnosticStep.DNS, results = checkResults.toList())

        // 2. DNS
        val dnsResult = if (internetResult.passed) checkDns() else CheckResult(
            DiagnosticStep.DNS, passed = false, latencyMs = 0, detail = "Skipped due to no internet",
            exception = internetResult.exception
        )
        checkResults.add(dnsResult)
        _state.value = _state.value.copy(currentStep = DiagnosticStep.EXTRACTOR_INIT, results = checkResults.toList())

        // 3. Extractor init
        val extractorResult = if (dnsResult.passed) checkExtractorInit(context) else CheckResult(
            DiagnosticStep.EXTRACTOR_INIT, passed = false, latencyMs = 0, detail = "Skipped due to DNS/network failure",
            exception = dnsResult.exception
        )
        checkResults.add(extractorResult)
        _state.value = _state.value.copy(currentStep = DiagnosticStep.RESOLVE_STREAM, results = checkResults.toList())

        // 4. Resolve fixed videoId
        val resolveResult = if (extractorResult.passed) {
            val res = checkResolveStream(FIXED_VIDEO_ID)
            resolvedStream = res.first
            res.second
        } else {
            CheckResult(
                DiagnosticStep.RESOLVE_STREAM, passed = false, latencyMs = 0, detail = "Skipped due to extractor failure",
                exception = extractorResult.exception
            )
        }
        checkResults.add(resolveResult)
        _state.value = _state.value.copy(currentStep = DiagnosticStep.STREAM_HEAD_RANGE, results = checkResults.toList())

        // 5. HEAD/range on chosen stream URL
        val headResult = if (resolveResult.passed && resolvedStream != null) {
            checkStreamHeadRange(resolvedStream!!)
        } else {
            CheckResult(
                DiagnosticStep.STREAM_HEAD_RANGE, passed = false, latencyMs = 0, detail = "Skipped: no stream URL resolved",
                exception = resolveResult.exception
            )
        }
        checkResults.add(headResult)
        _state.value = _state.value.copy(currentStep = DiagnosticStep.EXOPLAYER_BUFFER, results = checkResults.toList())

        // 6. ExoPlayer prepare and buffer 3s
        val bufferResult = if (headResult.passed && resolvedStream != null) {
            checkExoPlayerBuffer(context, FIXED_VIDEO_ID, resolvedStream!!)
        } else {
            CheckResult(
                DiagnosticStep.EXOPLAYER_BUFFER, passed = false, latencyMs = 0, detail = "Skipped: stream URL test failed",
                exception = headResult.exception
            )
        }
        checkResults.add(bufferResult)

        // Compute verdict
        val verdict = DiagnosticsVerdictMapper.computeVerdict(checkResults)
        val report = DiagnosticReport(
            results = checkResults,
            verdict = verdict,
            resolvedUrl = resolvedStream?.url,
        )

        _state.value = State(
            isRunning = false,
            currentStep = null,
            results = checkResults,
            verdict = verdict,
        )

        DiagnosticsLogger.i("Diagnostics", "Diagnostics complete: VERDICT = ${verdict.plainText}")
        report
    }

    private fun checkInternet(): CheckResult {
        val start = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", "VELVET-Diagnostics")
                .build()
            val response = VelvetApp.http.newCall(request).execute()
            val latency = System.currentTimeMillis() - start
            val code = response.code
            response.close()
            if (code in 200..299) {
                CheckResult(DiagnosticStep.INTERNET, passed = true, latencyMs = latency, detail = "HTTP $code OK")
            } else {
                CheckResult(DiagnosticStep.INTERNET, passed = false, latencyMs = latency, detail = "HTTP $code", httpStatus = code)
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            CheckResult(DiagnosticStep.INTERNET, passed = false, latencyMs = latency, detail = e.message ?: "Connection failed", exception = e)
        }
    }

    private fun checkDns(): CheckResult {
        val start = System.currentTimeMillis()
        return try {
            val ytAddrs = InetAddress.getAllByName("youtube.com")
            val musicAddrs = InetAddress.getAllByName("music.youtube.com")
            val latency = System.currentTimeMillis() - start
            if (ytAddrs.isNotEmpty() && musicAddrs.isNotEmpty()) {
                CheckResult(
                    DiagnosticStep.DNS,
                    passed = true,
                    latencyMs = latency,
                    detail = "Resolved: ${ytAddrs.first().hostAddress}, ${musicAddrs.first().hostAddress}"
                )
            } else {
                CheckResult(DiagnosticStep.DNS, passed = false, latencyMs = latency, detail = "Empty DNS resolution")
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            CheckResult(DiagnosticStep.DNS, passed = false, latencyMs = latency, detail = e.message ?: "DNS lookup failed", exception = e)
        }
    }

    private fun checkExtractorInit(context: Context): CheckResult {
        val start = System.currentTimeMillis()
        return try {
            if (NewPipe.getDownloader() == null) {
                resetExtractor(context)
            }
            val downloader = NewPipe.getDownloader()
            val latency = System.currentTimeMillis() - start
            if (downloader != null) {
                CheckResult(DiagnosticStep.EXTRACTOR_INIT, passed = true, latencyMs = latency, detail = "NewPipeExtractor initialized")
            } else {
                CheckResult(DiagnosticStep.EXTRACTOR_INIT, passed = false, latencyMs = latency, detail = "Downloader was null")
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            CheckResult(DiagnosticStep.EXTRACTOR_INIT, passed = false, latencyMs = latency, detail = e.message ?: "Extractor init failed", exception = e)
        }
    }

    private fun checkResolveStream(videoId: String): Pair<StreamResolver.Resolved?, CheckResult> {
        val start = System.currentTimeMillis()
        return try {
            // Bypass cache for diagnosis
            StreamResolver.invalidate(videoId)
            val resolved = StreamResolver.resolve(videoId)
            val latency = System.currentTimeMillis() - start
            Pair(
                resolved,
                CheckResult(
                    DiagnosticStep.RESOLVE_STREAM,
                    passed = true,
                    latencyMs = latency,
                    detail = "Quality: ${resolved.quality}, itag/rendition: ${resolved.rendition} (${if (resolved.fast) "Fast" else "Full"})"
                )
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            Pair(
                null,
                CheckResult(
                    DiagnosticStep.RESOLVE_STREAM,
                    passed = false,
                    latencyMs = latency,
                    detail = e.message ?: "Failed to resolve stream",
                    exception = e
                )
            )
        }
    }

    private fun checkStreamHeadRange(resolved: StreamResolver.Resolved): CheckResult {
        val start = System.currentTimeMillis()
        return try {
            val headersBuilder = Headers.Builder()
            resolved.headers.forEach { (k, v) -> headersBuilder.add(k, v) }
            val request = Request.Builder()
                .url(resolved.url)
                .headers(headersBuilder.build())
                .header("Range", "bytes=0-1024")
                .get()
                .build()
            val response = VelvetApp.http.newCall(request).execute()
            val latency = System.currentTimeMillis() - start
            val code = response.code
            val contentLength = response.body?.contentLength() ?: 0L
            response.close()

            if (code == 200 || code == 206) {
                CheckResult(
                    DiagnosticStep.STREAM_HEAD_RANGE,
                    passed = true,
                    latencyMs = latency,
                    detail = "HTTP $code (read $contentLength bytes)",
                    httpStatus = code
                )
            } else {
                CheckResult(
                    DiagnosticStep.STREAM_HEAD_RANGE,
                    passed = false,
                    latencyMs = latency,
                    detail = "HTTP $code response on stream URL",
                    httpStatus = code
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            CheckResult(
                DiagnosticStep.STREAM_HEAD_RANGE,
                passed = false,
                latencyMs = latency,
                detail = e.message ?: "Stream HTTP request failed",
                exception = e
            )
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun checkExoPlayerBuffer(
        context: Context,
        videoId: String,
        resolved: StreamResolver.Resolved
    ): CheckResult = withContext(Dispatchers.Main) {
        val start = System.currentTimeMillis()
        val deferred = CompletableDeferred<CheckResult>()

        var player: ExoPlayer? = null
        try {
            val cached = CacheDataSource.Factory()
                .setCache(PlaybackService.cache(context))
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(VelvetApp.http))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val routed = DefaultDataSource.Factory(context, cached)
            val resolving = ResolvingDataSource.Factory(routed) { spec ->
                spec.buildUpon()
                    .setUri(resolved.url)
                    .setHttpRequestHeaders(spec.httpRequestHeaders + resolved.headers)
                    .build()
            }

            player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(songUri(videoId))
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!deferred.isCompleted) {
                        val latency = System.currentTimeMillis() - start
                        deferred.complete(
                            CheckResult(
                                DiagnosticStep.EXOPLAYER_BUFFER,
                                passed = false,
                                latencyMs = latency,
                                detail = "Player error: ${error.errorCodeName}",
                                exception = error
                            )
                        )
                    }
                }
            })

            player.setMediaItem(mediaItem)
            player.prepare()

            // Poll for 3s buffered or ready state within 8 seconds
            withContext(Dispatchers.Default) {
                val deadline = System.currentTimeMillis() + 8000L
                while (System.currentTimeMillis() < deadline && !deferred.isCompleted) {
                    val buffered = withContext(Dispatchers.Main) { player?.bufferedPosition ?: 0L }
                    val state = withContext(Dispatchers.Main) { player?.playbackState }
                    if (buffered >= 3000L || state == Player.STATE_READY) {
                        val latency = System.currentTimeMillis() - start
                        deferred.complete(
                            CheckResult(
                                DiagnosticStep.EXOPLAYER_BUFFER,
                                passed = true,
                                latencyMs = latency,
                                detail = "Buffered ${buffered}ms (target >= 3000ms)"
                            )
                        )
                        break
                    }
                    delay(100)
                }
                if (!deferred.isCompleted) {
                    val latency = System.currentTimeMillis() - start
                    deferred.complete(
                        CheckResult(
                            DiagnosticStep.EXOPLAYER_BUFFER,
                            passed = false,
                            latencyMs = latency,
                            detail = "Timeout: buffered less than 3 s in 8 s window"
                        )
                    )
                }
            }

            deferred.await()
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            CheckResult(
                DiagnosticStep.EXOPLAYER_BUFFER,
                passed = false,
                latencyMs = latency,
                detail = e.message ?: "ExoPlayer setup failed",
                exception = e
            )
        } finally {
            try {
                player?.stop()
                player?.release()
            } catch (_: Exception) {}
        }
    }
}
