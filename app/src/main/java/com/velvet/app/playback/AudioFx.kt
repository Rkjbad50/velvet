package com.velvet.app.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.velvet.app.data.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ln

/**
 * Equalizer, bass boost, 3D (virtualizer) and loudness, attached to the player's audio
 * session. Every effect is optional: some phones don't support all of them.
 */
object AudioFx {

    /** What the equalizer screen needs to draw its sliders. Empty until the player starts. */
    class BandInfo(val centersHz: List<Int>, val minMb: Int, val maxMb: Int)

    private val _bands = MutableStateFlow<BandInfo?>(null)
    val bands: StateFlow<BandInfo?> = _bands

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var current = EqSettings()

    /** Target DSP parameters resolved cleanly for any [EqSettings] state. */
    data class TargetAudioProfile(
        val active: Boolean,
        val bandLevels: List<Int>,
        val bassStrength: Int,
        val virtualizerStrength: Int,
        val loudnessGainMb: Int,
    )

    const val PRESET_ULTRA = "ULT Bass"
    val presets = listOf("Flat", "Bass boost", "Vocal", "Treble", "Rock", "Pop", "Acoustic")

    @Synchronized
    fun attach(sessionId: Int, initialSettings: EqSettings = current) {
        release()
        current = initialSettings
        if (sessionId <= 0) return
        // creating an effect and asking what it supports can both throw on some phones;
        // request priority 1 (falling back to 0) so external session listeners don't steal control
        eq = runCatching { Equalizer(1, sessionId) }.getOrElse {
            runCatching { Equalizer(0, sessionId) }.getOrNull()
        }
        bass = runCatching { BassBoost(1, sessionId) }.getOrElse {
            runCatching { BassBoost(0, sessionId) }.getOrNull()
        }
        virtualizer = runCatching { Virtualizer(1, sessionId) }.getOrElse {
            runCatching { Virtualizer(0, sessionId) }.getOrNull()
        }
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        _bands.value = eq?.let(::extractBandInfo)
        apply(current)
    }

    private fun extractBandInfo(e: Equalizer): BandInfo? =
        runCatching {
            val range = e.bandLevelRange
            BandInfo(
                centersHz = (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 },
                minMb = range[0].toInt(),
                maxMb = range[1].toInt(),
            )
        }.onFailure { error ->
            Log.w(TAG, "Equalizer unusable on this device", error)
            runCatching { e.release() }
            eq = null
        }.getOrNull()

    @Synchronized
    fun release() {
        listOfNotNull(eq, bass, virtualizer, loudness).forEach { runCatching { it.release() } }
        eq = null
        bass = null
        virtualizer = null
        loudness = null
        _bands.value = null
    }

    /**
     * Resolves the single authoritative audio effect target profile for [settings].
     * When [EqSettings.ultra] is active, ULT Bass always takes strict precedence over any manual
     * preset, sliders, or effect values so switching modes never blends two profiles.
     */
    fun resolveTargetProfile(settings: EqSettings, info: BandInfo?): TargetAudioProfile {
        val active = settings.enabled || settings.ultra
        if (settings.ultra) {
            return TargetAudioProfile(
                active = true,
                bandLevels = if (info != null) curve(PRESET_ULTRA, info) else emptyList(),
                bassStrength = ULTRA_BASS,
                virtualizerStrength = ULTRA_VIRTUALIZER,
                loudnessGainMb = ULTRA_LOUDNESS,
            )
        }
        return TargetAudioProfile(
            active = active,
            bandLevels = settings.bands,
            bassStrength = settings.bass,
            virtualizerStrength = settings.virtualizer,
            loudnessGainMb = settings.loudness,
        )
    }

    @Synchronized
    fun apply(settings: EqSettings) {
        current = settings
        val info = _bands.value ?: eq?.let(::extractBandInfo)?.also { _bands.value = it }
        val profile = resolveTargetProfile(settings, info)
        val enableBass = profile.active && profile.bassStrength > 0
        val enableVirt = profile.active && profile.virtualizerStrength > 0
        val enableLoud = profile.active && profile.loudnessGainMb > 0

        var newlyEnabled = false

        // Phase 1: Enable active effects BEFORE setting levels/strengths so OEM HAL/EffectBundle
        // initialization (which resets or ignores pre-enable writes on 1st activation) settles first.
        if (profile.active) {
            runCatching {
                eq?.let { e ->
                    if (!e.enabled) {
                        e.enabled = true
                        newlyEnabled = true
                    }
                }
            }
            if (enableBass) {
                runCatching {
                    bass?.let { b ->
                        if (!b.enabled) {
                            b.enabled = true
                            newlyEnabled = true
                        }
                    }
                }
            }
            if (enableVirt) {
                runCatching {
                    virtualizer?.let { v ->
                        if (!v.enabled) {
                            v.enabled = true
                            newlyEnabled = true
                        }
                    }
                }
            }
            if (enableLoud) {
                runCatching {
                    loudness?.let { l ->
                        if (!l.enabled) {
                            l.enabled = true
                            newlyEnabled = true
                        }
                    }
                }
            }
        }

        // Phase 2: Write all target parameters while active effects are enabled.
        val eqMismatched = writeParameters(profile, info, enableBass, enableVirt, enableLoud)

        // Phase 3: Re-assert parameters if any effect transitioned disabled -> enabled on this call
        // or if the HAL clamped/dropped any band on initial EffectBundle enable.
        if (profile.active && (newlyEnabled || eqMismatched)) {
            writeParameters(profile, info, enableBass, enableVirt, enableLoud)
        }

        // Phase 4: Cleanly disable inactive effects after writing neutral values.
        if (!profile.active) {
            runCatching { eq?.let { e -> if (e.enabled) e.enabled = false } }
        }
        if (!enableBass) {
            runCatching { bass?.let { b -> if (b.enabled) b.enabled = false } }
        }
        if (!enableVirt) {
            runCatching { virtualizer?.let { v -> if (v.enabled) v.enabled = false } }
        }
        if (!enableLoud) {
            runCatching { loudness?.let { l -> if (l.enabled) l.enabled = false } }
        }
    }

    private fun writeParameters(
        profile: TargetAudioProfile,
        info: BandInfo?,
        enableBass: Boolean,
        enableVirt: Boolean,
        enableLoud: Boolean,
    ): Boolean {
        var bandMismatch = false
        val minMb = info?.minMb ?: -1500
        val maxMb = info?.maxMb ?: 1500
        runCatching {
            eq?.let { e ->
                if (profile.active) {
                    if (!e.enabled) e.enabled = true
                    for (i in 0 until e.numberOfBands) {
                        val level = profile.bandLevels.getOrElse(i) { 0 }.coerceIn(minMb, maxMb).toShort()
                        e.setBandLevel(i.toShort(), level)
                        if (e.getBandLevel(i.toShort()) != level) {
                            bandMismatch = true
                        }
                    }
                } else {
                    for (i in 0 until e.numberOfBands) {
                        e.setBandLevel(i.toShort(), 0.toShort())
                    }
                }
            }
        }
        runCatching {
            bass?.let { b ->
                val target = if (enableBass) profile.bassStrength.coerceIn(0, 1000).toShort() else 0.toShort()
                if (enableBass && !b.enabled) b.enabled = true
                if (b.strengthSupported) {
                    b.setStrength(target)
                    if (enableBass && b.roundedStrength != target) {
                        b.setStrength(target)
                    }
                }
            }
        }
        runCatching {
            virtualizer?.let { v ->
                val target = if (enableVirt) profile.virtualizerStrength.coerceIn(0, 1000).toShort() else 0.toShort()
                if (enableVirt && !v.enabled) v.enabled = true
                if (v.strengthSupported) {
                    v.setStrength(target)
                    if (enableVirt && v.roundedStrength != target) {
                        v.setStrength(target)
                    }
                }
            }
        }
        runCatching {
            loudness?.let { l ->
                val target = if (enableLoud) profile.loudnessGainMb.coerceIn(0, 2000) else 0
                if (enableLoud && !l.enabled) l.enabled = true
                l.setTargetGain(target)
            }
        }
        return bandMismatch
    }

    /**
     * Band levels (millibels) for a named preset, shaped by each band's frequency so it works
     * whatever bands the phone's equalizer has.
     */
    fun curve(preset: String, info: BandInfo): List<Int> = info.centersHz.map { hz ->
        val db = when (preset) {
            // deep bass, clear vocals and crisp highs, with the low mids pulled back so it stays clean
            PRESET_ULTRA -> shape(hz, bass = 9.0, lowMid = -1.5, mid = 1.0, presence = 5.0, treble = 7.0)
            "Bass boost" -> shape(hz, bass = 8.0, lowMid = 3.0, mid = 0.0, presence = 0.0, treble = 0.0)
            "Vocal" -> shape(hz, bass = -2.0, lowMid = 0.0, mid = 4.0, presence = 5.0, treble = 2.0)
            "Treble" -> shape(hz, bass = 0.0, lowMid = 0.0, mid = 0.0, presence = 4.0, treble = 8.0)
            "Rock" -> shape(hz, bass = 5.0, lowMid = -1.0, mid = -2.0, presence = 3.0, treble = 5.0)
            "Pop" -> shape(hz, bass = 2.0, lowMid = 3.0, mid = 4.0, presence = 2.0, treble = 1.0)
            "Acoustic" -> shape(hz, bass = 3.0, lowMid = 1.0, mid = 2.0, presence = 3.0, treble = 3.0)
            else -> 0.0
        }
        (db * 100).toInt().coerceIn(info.minMb, info.maxMb)
    }

    // picks the gain for a band from where its centre frequency sits on a log scale
    private fun shape(hz: Int, bass: Double, lowMid: Double, mid: Double, presence: Double, treble: Double): Double {
        val x = ln(hz.coerceAtLeast(20).toDouble())
        return when {
            x < ln(150.0) -> bass
            x < ln(500.0) -> lowMid
            x < ln(2000.0) -> mid
            x < ln(6000.0) -> presence
            else -> treble
        }
    }

    private const val TAG = "AudioFx"
    private const val ULTRA_BASS = 850
    private const val ULTRA_VIRTUALIZER = 700
    private const val ULTRA_LOUDNESS = 450
}
