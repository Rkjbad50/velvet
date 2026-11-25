package com.velvet.app.recommendation.profile

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.model.TrackFeatureRecord
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * Explicit user library inputs blended into listener profile snapshots cleanly.
 */
data class ExplicitLibraryInputs(
    val likedSongs: List<Song> = emptyList(),
    val playlistSongs: List<Song> = emptyList(),
    val followedArtistNames: List<String> = emptyList(),
    val preferredArtistNames: List<String> = emptyList(),
    val preferredGenres: List<String> = emptyList(),
    val preferredLanguageNames: List<String> = emptyList(),
    val knownSongsByProviderId: Map<String, Song> = emptyMap(),
)

/**
 * Pure Kotlin multi-horizon listener profile engine.
 *
 * Key behavioural guarantees:
 * - Long-term (~90d), medium-term (~14d), and session (~2h) profiles stay independent.
 * - Automatic Repeat-One (`TRACK_REPEATED_AUTOMATICALLY`) never inflates positive taste.
 * - Search-only submissions remain weak, expire quickly, and do not modify long-term taste unless
 *   converted via result click + meaningful listening/completion.
 * - One early skip produces only a mild, fast-decaying item penalty and never bans an artist/genre.
 * - Exposure saturation prevents high-volume listeners from exploding affinity magnitudes.
 */
class TasteProfileBuilder(
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
) {
    private class HorizonAccumulator {
        val genres = mutableMapOf<String, Double>()
        val subgenres = mutableMapOf<String, Double>()
        val musicTypes = mutableMapOf<MusicType, Double>()
        val moods = mutableMapOf<String, Double>()
        val languages = mutableMapOf<String, Double>()
        val eras = mutableMapOf<String, Double>()
        val artists = mutableMapOf<String, Double>()
        val albums = mutableMapOf<String, Double>()
        val recordings = mutableMapOf<String, Double>()
        val works = mutableMapOf<String, Double>()

        fun add(
            weight: Double,
            recId: String?,
            workId: String?,
            artistKeys: Set<String>,
            albumKey: String?,
            features: TrackFeatureRecord?,
        ) {
            if (weight == 0.0 || weight.isNaN() || weight.isInfinite()) return
            if (!recId.isNullOrBlank()) {
                recordings[recId] = (recordings[recId] ?: 0.0) + weight
            }
            if (!workId.isNullOrBlank()) {
                works[workId] = (works[workId] ?: 0.0) + weight
            }
            for (art in artistKeys) {
                if (art.isNotBlank()) {
                    artists[art] = (artists[art] ?: 0.0) + weight
                }
            }
            if (!albumKey.isNullOrBlank()) {
                val low = albumKey.lowercase(Locale.ROOT).trim()
                val col = SongIdentity.collapseHindiLatinVariants(low)
                albums[low] = (albums[low] ?: 0.0) + weight
                if (col != low) albums[col] = (albums[col] ?: 0.0) + weight
            }
            if (features != null) {
                features.genreIds.forEach { (g, conf) ->
                    genres[g] = (genres[g] ?: 0.0) + weight * conf
                }
                features.subgenreIds.forEach { (sg, conf) ->
                    subgenres[sg] = (subgenres[sg] ?: 0.0) + weight * conf
                }
                features.musicTypes.forEach { (mt, conf) ->
                    if (mt != MusicType.UNKNOWN) {
                        musicTypes[mt] = (musicTypes[mt] ?: 0.0) + weight * conf
                    }
                }
                features.moodIds.forEach { (m, conf) ->
                    moods[m] = (moods[m] ?: 0.0) + weight * conf
                }
                features.languageIds.forEach { (lang, conf) ->
                    languages[lang] = (languages[lang] ?: 0.0) + weight * conf
                }
                features.eraBucket?.takeIf { features.eraConfidence > 0.0 }?.let { bucket ->
                    eras[bucket] = (eras[bucket] ?: 0.0) + weight * features.eraConfidence
                }
            }
        }

        fun addExplicitAffinity(
            weight: Double,
            genreIds: Collection<String> = emptyList(),
            subgenreIds: Collection<String> = emptyList(),
            musicTypes: Collection<MusicType> = emptyList(),
            artistKeys: Collection<String> = emptyList(),
            languageIds: Collection<String> = emptyList(),
            moodIds: Collection<String> = emptyList(),
        ) {
            if (weight <= 0.0 || weight.isNaN() || weight.isInfinite()) return
            genreIds.forEach { g ->
                if (g.isNotBlank()) genres[g] = (genres[g] ?: 0.0) + weight
            }
            subgenreIds.forEach { sg ->
                if (sg.isNotBlank()) subgenres[sg] = (subgenres[sg] ?: 0.0) + weight
            }
            musicTypes.forEach { mt ->
                if (mt != MusicType.UNKNOWN) {
                    this.musicTypes[mt] = (this.musicTypes[mt] ?: 0.0) + weight
                }
            }
            artistKeys.forEach { art ->
                if (art.isNotBlank()) artists[art] = (artists[art] ?: 0.0) + weight
            }
            languageIds.forEach { lang ->
                if (lang.isNotBlank()) languages[lang] = (languages[lang] ?: 0.0) + weight
            }
            moodIds.forEach { m ->
                if (m.isNotBlank()) moods[m] = (moods[m] ?: 0.0) + weight
            }
        }

        fun toVector(): FeaturePreferenceVector = FeaturePreferenceVector(
            genreWeights = saturateAndNormalize(genres),
            subgenreWeights = saturateAndNormalize(subgenres),
            musicTypeWeights = saturateAndNormalize(musicTypes),
            moodWeights = saturateAndNormalize(moods),
            languageWeights = saturateAndNormalize(languages),
            eraWeights = saturateAndNormalize(eras),
            artistWeights = saturateAndNormalize(artists),
            albumWeights = saturateAndNormalize(albums),
            recordingWeights = saturateAndNormalize(recordings),
            workWeights = saturateAndNormalize(works),
        )
    }

    fun build(
        events: List<RecommendationEvent>,
        libraryInputs: ExplicitLibraryInputs = ExplicitLibraryInputs(),
        activeSessionId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        watermark: String = "manual",
    ): UserTasteSnapshot {
        val longAcc = HorizonAccumulator()
        val medAcc = HorizonAccumulator()
        val sessAcc = HorizonAccumulator()

        val likedRecs = linkedSetOf<String>()
        val likedProviders = linkedSetOf<String>()
        val playlistRecs = linkedSetOf<String>()
        val playlistProviders = linkedSetOf<String>()
        val dislikedRecs = linkedSetOf<String>()
        val dislikedProviders = linkedSetOf<String>()
        val hiddenRecs = linkedSetOf<String>()
        val hiddenProviders = linkedSetOf<String>()
        val followedArtists = (libraryInputs.followedArtistNames + libraryInputs.preferredArtistNames)
            .map { normalizePrimaryArtist(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val preferredLanguages = normalizeLanguagePreferences(libraryInputs.preferredLanguageNames)

        // Fold 3-step onboarding taste choices directly so cold-start home & queues personalize immediately
        val hasOnboardingTaste = libraryInputs.preferredArtistNames.isNotEmpty() || libraryInputs.preferredGenres.isNotEmpty()
        for (artistName in libraryInputs.preferredArtistNames) {
            val keys = artistKeysFor(artistName, null)
            if (keys.isNotEmpty()) {
                longAcc.addExplicitAffinity(weight = 1.4, artistKeys = keys)
                medAcc.addExplicitAffinity(weight = 0.7, artistKeys = keys)
            }
        }
        for (genreRaw in libraryInputs.preferredGenres) {
            val sig = resolveSurveyGenreSignals(genreRaw)
            if (sig.genres.isNotEmpty() || sig.subgenres.isNotEmpty() || sig.musicTypes.isNotEmpty() || sig.moods.isNotEmpty()) {
                longAcc.addExplicitAffinity(
                    weight = 1.5,
                    genreIds = sig.genres,
                    subgenreIds = sig.subgenres,
                    musicTypes = sig.musicTypes,
                    moodIds = sig.moods,
                )
                medAcc.addExplicitAffinity(
                    weight = 0.8,
                    genreIds = sig.genres,
                    subgenreIds = sig.subgenres,
                    musicTypes = sig.musicTypes,
                    moodIds = sig.moods,
                )
            }
        }
        if (hasOnboardingTaste && preferredLanguages.isNotEmpty()) {
            longAcc.addExplicitAffinity(weight = 1.0, languageIds = preferredLanguages)
            medAcc.addExplicitAffinity(weight = 0.5, languageIds = preferredLanguages)
        }

        // Search intent tracking
        val queryTextsByQueryId = mutableMapOf<String, String>()
        val clickedQueries = mutableSetOf<String>()
        val convertedQueries = mutableSetOf<String>()
        val searchCatRaw = mutableMapOf<String, Double>()
        val convertedRecRaw = mutableMapOf<String, Double>()
        val convertedWorkRaw = mutableMapOf<String, Double>()
        val convertedArtistRaw = mutableMapOf<String, Double>()

        // Negative & fatigue tracking
        val earlySkipTimestampsByRec = mutableMapOf<String, MutableList<Long>>()
        val earlySkipProviderIds = mutableMapOf<String, MutableList<Long>>()
        val artistEarlySkips14d = mutableMapOf<String, MutableSet<String>>()
        val singleSkipRaw = mutableMapOf<String, Double>()
        val sessionRemovedRecs = linkedSetOf<String>()
        val sessionRemovedProviders = linkedSetOf<String>()
        val dailyPlayTimesByRec = mutableMapOf<String, MutableList<Long>>()

        // Novelty & play-session deduplication
        val meaningfulSeenPerPlayKey = mutableSetOf<String>()
        val completedSeenPerPlayKey = mutableSetOf<String>()
        val seenEverRecordings = mutableSetOf<String>()
        var totalCompletedForNovelty = 0
        var newCompletedForNovelty = 0
        var latestSearchEventAt = 0L

        val songIndex = buildSongIndex(libraryInputs)

        // Fold durable explicit Library likes & playlists first as anchored explicit baseline
        for (likedSong in libraryInputs.likedSongs) {
            val id = RecordingIdentityResolver.resolve(likedSong)
            likedRecs.add(id.canonicalRecordingId)
            likedRecs.add(SongIdentity.canonicalGroupId(likedSong))
            likedProviders.add(likedSong.id)
            val feats = featureRepository.getCachedOrExtract(likedSong)
            val artistKeys = artistKeysFor(likedSong.artist, feats)
            longAcc.add(1.6, id.canonicalRecordingId, id.workId, artistKeys, likedSong.album, feats)
            medAcc.add(0.8, id.canonicalRecordingId, id.workId, artistKeys, likedSong.album, feats)
        }
        for (plSong in libraryInputs.playlistSongs) {
            val id = RecordingIdentityResolver.resolve(plSong)
            playlistRecs.add(id.canonicalRecordingId)
            playlistRecs.add(SongIdentity.canonicalGroupId(plSong))
            playlistProviders.add(plSong.id)
            val feats = featureRepository.getCachedOrExtract(plSong)
            val artistKeys = artistKeysFor(plSong.artist, feats)
            longAcc.add(1.1, id.canonicalRecordingId, id.workId, artistKeys, plSong.album, feats)
            medAcc.add(0.5, id.canonicalRecordingId, id.workId, artistKeys, plSong.album, feats)
        }

        val sorted = events.sortedBy { it.occurredAtMillis }
        val effectiveSessionId = activeSessionId
            ?: sorted.lastOrNull { it.recommendationSessionId != null }?.recommendationSessionId

        for (event in sorted) {
            val ageMs = (nowMillis - event.occurredAtMillis).coerceAtLeast(0L)
            val longDecay = exp(-DECAY_LONG_TERM * ageMs)
            val medDecay = exp(-DECAY_MEDIUM_TERM * ageMs)
            val sessDecay = exp(-DECAY_SESSION * ageMs)
            val searchDecay = exp(-DECAY_SEARCH * ageMs)
            val inCurrentSession = effectiveSessionId != null && event.recommendationSessionId == effectiveSessionId

            val knownSong = event.providerItemId?.let { songIndex[it] }
            val features = knownSong?.let { featureRepository.getCachedOrExtract(it) }
            val resolvedIdentity = knownSong?.let { RecordingIdentityResolver.resolve(it) }
            val recKeys = buildSet {
                event.canonicalRecordingId?.takeIf { it.isNotBlank() }?.let { add(it) }
                resolvedIdentity?.canonicalRecordingId?.let { add(it) }
            }
            val primaryRecKey = resolvedIdentity?.canonicalRecordingId
                ?: event.canonicalRecordingId
                ?: event.providerItemId
            val workId = resolvedIdentity?.workId
            val artistKeys = buildSet {
                event.artistIds.forEach { raw ->
                    val norm = normalizePrimaryArtist(raw)
                    if (norm.isNotBlank()) {
                        add(norm)
                        add(SongIdentity.collapseHindiLatinVariants(norm))
                    }
                }
                if (features != null) {
                    features.artistDisplayNames.forEach { addAll(artistKeysFor(it, features)) }
                }
            }
            val albumKey = event.albumOrWorkId ?: features?.albumOrReleaseId

            when (event.eventType) {
                RecommendationEventType.TRACK_REPEATED_AUTOMATICALLY -> {
                    // Explicit non-negotiable rule: automatic Repeat One must NOT inflate preference or novelty.
                }

                RecommendationEventType.SEARCH_SUBMITTED -> {
                    val q = event.normalizedQuery?.trim()?.lowercase(Locale.ROOT)
                    if (!q.isNullOrBlank()) {
                        latestSearchEventAt = maxOf(latestSearchEventAt, event.occurredAtMillis)
                        event.queryId?.let { queryTextsByQueryId[it] = q }
                        val cat = inferQueryCategory(q)
                        if (cat != null && ageMs <= SEARCH_WINDOW_MS) {
                            // Unconverted query stays strictly inside searchIntent (weak prior)
                            searchCatRaw[cat] = (searchCatRaw[cat] ?: 0.0) + 0.08 * searchDecay
                        }
                    }
                }

                RecommendationEventType.SEARCH_RESULT_CLICKED -> {
                    event.queryId?.let { qId ->
                        clickedQueries.add(qId)
                        event.normalizedQuery?.let { queryTextsByQueryId.putIfAbsent(qId, it) }
                    }
                }

                RecommendationEventType.QUEUE_PLAY_NEXT -> {
                    // Strong immediate session intent; mild medium-term hint; avoids rewriting long-term taste
                    val rec = primaryRecKey
                    sessAcc.add(2.5 * sessDecay, rec, workId, artistKeys, albumKey, features)
                    medAcc.add(0.45 * medDecay, rec, workId, artistKeys, albumKey, features)
                    longAcc.add(0.15 * longDecay, rec, workId, artistKeys, albumKey, features)
                }

                RecommendationEventType.TRACK_PLAY_STARTED -> {
                    if (!event.queryId.isNullOrBlank()) {
                        clickedQueries.add(event.queryId)
                    }
                }

                RecommendationEventType.TRACK_MEANINGFUL_LISTEN -> {
                    val playKey = "${event.recommendationSessionId ?: "na"}:${event.providerItemId ?: primaryRecKey}:${event.occurredAtMillis / 60_000L}"
                    if (meaningfulSeenPerPlayKey.add(playKey)) {
                        val w = 0.85
                        longAcc.add(w * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                        medAcc.add(w * medDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                        if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                            sessAcc.add(w * sessDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                        }
                        if (ageMs <= ONE_DAY_MS && primaryRecKey != null) {
                            dailyPlayTimesByRec.getOrPut(primaryRecKey) { mutableListOf() }.add(event.occurredAtMillis)
                        }
                        if (!event.queryId.isNullOrBlank()) {
                            convertedQueries.add(event.queryId)
                            latestSearchEventAt = maxOf(latestSearchEventAt, event.occurredAtMillis)
                            if (primaryRecKey != null) {
                                convertedRecRaw[primaryRecKey] = (convertedRecRaw[primaryRecKey] ?: 0.0) + 1.5 * searchDecay
                            }
                            if (workId != null) {
                                convertedWorkRaw[workId] = (convertedWorkRaw[workId] ?: 0.0) + 1.2 * searchDecay
                            }
                            artistKeys.forEach { a ->
                                convertedArtistRaw[a] = (convertedArtistRaw[a] ?: 0.0) + 1.0 * searchDecay
                            }
                            queryTextsByQueryId[event.queryId]?.let { rawQ ->
                                inferQueryCategory(rawQ)?.let { cat ->
                                    searchCatRaw[cat] = (searchCatRaw[cat] ?: 0.0) + 0.9 * searchDecay
                                }
                            }
                        }
                    }
                }

                RecommendationEventType.TRACK_COMPLETED,
                RecommendationEventType.AUTOPLAY_TRACK_COMPLETED -> {
                    val playKey = "${event.recommendationSessionId ?: "na"}:${event.providerItemId ?: primaryRecKey}:${event.occurredAtMillis / 60_000L}"
                    if (completedSeenPerPlayKey.add(playKey)) {
                        val base = if ("migrated_legacy_listen_event" in event.sessionContextTags) 0.9 else 1.2
                        longAcc.add(base * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                        medAcc.add(base * medDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                        if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                            sessAcc.add(base * sessDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                        }
                        if (ageMs <= ONE_DAY_MS && primaryRecKey != null) {
                            dailyPlayTimesByRec.getOrPut(primaryRecKey) { mutableListOf() }.add(event.occurredAtMillis)
                        }
                        if (primaryRecKey != null) {
                            totalCompletedForNovelty++
                            if (seenEverRecordings.add(primaryRecKey)) {
                                newCompletedForNovelty++
                            }
                        }
                    }
                }

                RecommendationEventType.TRACK_REPLAYED_MANUALLY -> {
                    val unverifiedLegacy = "legacy_replay_unverified_repeat" in event.sessionContextTags
                    val base = if (unverifiedLegacy) 0.35 else 2.0
                    longAcc.add(base * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                    medAcc.add(base * medDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                    if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                        sessAcc.add(base * sessDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                    }
                }

                RecommendationEventType.TRACK_LIKED -> {
                    likedRecs.addAll(recKeys)
                    event.providerItemId?.let { likedProviders.add(it) }
                    longAcc.add(3.0 * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                    medAcc.add(2.5 * medDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                }

                RecommendationEventType.TRACK_UNLIKED -> {
                    likedRecs.removeAll(recKeys)
                    event.providerItemId?.let { likedProviders.remove(it) }
                    longAcc.add(-1.2 * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                }

                RecommendationEventType.TRACK_ADDED_TO_PLAYLIST -> {
                    playlistRecs.addAll(recKeys)
                    event.providerItemId?.let { playlistProviders.add(it) }
                    longAcc.add(2.2 * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                    medAcc.add(1.8 * medDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                }

                RecommendationEventType.TRACK_REMOVED_FROM_PLAYLIST -> {
                    playlistRecs.removeAll(recKeys)
                    event.providerItemId?.let { playlistProviders.remove(it) }
                    longAcc.add(-0.6 * longDecay, primaryRecKey, workId, artistKeys, albumKey, features)
                }

                RecommendationEventType.TRACK_SKIPPED_EARLY,
                RecommendationEventType.AUTOPLAY_TRACK_SKIPPED -> {
                    // Only treat skip as early skip penalty if skipped early (<30s) or unknown playedMs on AUTOPLAY_TRACK_SKIPPED with <30s
                    val isEarly = event.eventType == RecommendationEventType.TRACK_SKIPPED_EARLY ||
                        (event.playedMs ?: 0L) < 30_000L
                    if (isEarly) {
                        if (primaryRecKey != null) {
                            seenEverRecordings.add(primaryRecKey)
                            if (ageMs <= FOURTEEN_DAYS_MS) {
                                earlySkipTimestampsByRec.getOrPut(primaryRecKey) { mutableListOf() }.add(event.occurredAtMillis)
                            }
                            recKeys.forEach { rk ->
                                if (ageMs <= FOURTEEN_DAYS_MS && rk != primaryRecKey) {
                                    earlySkipTimestampsByRec.getOrPut(rk) { mutableListOf() }.add(event.occurredAtMillis)
                                }
                            }
                            if (ageMs <= SEARCH_WINDOW_MS) {
                                singleSkipRaw[primaryRecKey] = (singleSkipRaw[primaryRecKey] ?: 0.0) + 0.25 * searchDecay
                            }
                        }
                        event.providerItemId?.let { pid ->
                            if (ageMs <= FOURTEEN_DAYS_MS) {
                                earlySkipProviderIds.getOrPut(pid) { mutableListOf() }.add(event.occurredAtMillis)
                            }
                        }
                        if (ageMs <= FOURTEEN_DAYS_MS && primaryRecKey != null) {
                            artistKeys.forEach { ak ->
                                artistEarlySkips14d.getOrPut(ak) { mutableSetOf() }.add("$primaryRecKey@${event.occurredAtMillis / 60_000L}")
                            }
                        }
                        // Mild short-lived session negative only (NEVER nukes whole genre or artist on 1 skip)
                        if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                            sessAcc.add(-0.25 * sessDecay, primaryRecKey, null, emptySet(), null, null)
                        }
                    } else {
                        // Late skip (>= 30s): negligible negative on item only
                        if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                            sessAcc.add(-0.08 * sessDecay, primaryRecKey, null, emptySet(), null, null)
                        }
                    }
                }

                RecommendationEventType.TRACK_SKIPPED_LATE -> {
                    if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                        sessAcc.add(-0.08 * sessDecay, primaryRecKey, null, emptySet(), null, null)
                    }
                }

                RecommendationEventType.QUEUE_REMOVED -> {
                    if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                        sessionRemovedRecs.addAll(recKeys)
                        event.providerItemId?.let { sessionRemovedProviders.add(it) }
                    }
                }

                RecommendationEventType.RECOMMENDATION_NOT_INTERESTED -> {
                    dislikedRecs.addAll(recKeys)
                    event.providerItemId?.let { dislikedProviders.add(it) }
                    longAcc.add(-2.5 * longDecay, primaryRecKey, workId, emptySet(), null, null)
                    medAcc.add(-2.5 * medDecay, primaryRecKey, workId, emptySet(), null, null)
                }

                RecommendationEventType.RECOMMENDATION_HIDDEN -> {
                    hiddenRecs.addAll(recKeys)
                    event.providerItemId?.let { hiddenProviders.add(it) }
                    if (inCurrentSession || ageMs <= SESSION_WINDOW_MS) {
                        sessionRemovedRecs.addAll(recKeys)
                        event.providerItemId?.let { sessionRemovedProviders.add(it) }
                    }
                }

                else -> Unit
            }
        }

        // Compute multi-skip fatigue: >= 2 early skips within 14d (valid 30d cooldown)
        val fatiguedRecs = earlySkipTimestampsByRec.filter { (_, tsList) ->
            hasIndependentSkips(tsList) && (nowMillis - tsList.maxOrNull()!! <= THIRTY_DAYS_MS)
        }.keys.toSet()

        val fatiguedProviders = earlySkipProviderIds.filter { (_, tsList) ->
            hasIndependentSkips(tsList) && (nowMillis - tsList.maxOrNull()!! <= THIRTY_DAYS_MS)
        }.keys.toSet()

        // Bounded artist skip fatigue only after >= 3 separate early skip exposures in 14d (capped -0.35)
        val repeatedArtistPenalty = artistEarlySkips14d.mapNotNull { (artist, distinctSkips) ->
            if (distinctSkips.size >= 3) {
                val p = -(0.12 * distinctSkips.size).coerceAtMost(0.35)
                artist to p
            } else null
        }.toMap()

        val dailyOverplayed = dailyPlayTimesByRec.filter { (_, ts) -> ts.size >= 3 }.keys.toSet()
        val novelty = if (totalCompletedForNovelty > 0) {
            (newCompletedForNovelty.toDouble() / totalCompletedForNovelty.toDouble()).coerceIn(0.0, 1.0)
        } else 0.5

        val unconvertedCount = (queryTextsByQueryId.keys - convertedQueries).size
        val convertedCount = convertedQueries.size

        return UserTasteSnapshot(
            longTerm = longAcc.toVector(),
            mediumTerm = medAcc.toVector(),
            session = sessAcc.toVector(),
            explicit = ExplicitPreferenceSet(
                likedRecordingIds = likedRecs,
                likedProviderIds = likedProviders,
                savedPlaylistRecordingIds = playlistRecs,
                savedPlaylistProviderIds = playlistProviders,
                dislikedRecordingIds = dislikedRecs,
                dislikedProviderIds = dislikedProviders,
                hiddenRecordingIds = hiddenRecs,
                hiddenProviderIds = hiddenProviders,
                followedArtistIds = followedArtists,
                preferredLanguageIds = preferredLanguages,
            ),
            searchIntent = SearchIntentSnapshot(
                queryCategoryWeights = saturateAndNormalize(searchCatRaw),
                convertedRecordingBoosts = saturateAndNormalize(convertedRecRaw),
                convertedWorkBoosts = saturateAndNormalize(convertedWorkRaw),
                convertedArtistWeights = saturateAndNormalize(convertedArtistRaw),
                unconvertedQueriesCount = unconvertedCount,
                convertedQueriesCount = convertedCount,
                lastUpdatedMillis = latestSearchEventAt,
            ),
            fatigue = FatigueSnapshot(
                fatiguedRecordingIds = fatiguedRecs,
                fatiguedProviderIds = fatiguedProviders,
                sessionRemovedRecordingIds = sessionRemovedRecs,
                sessionRemovedProviderIds = sessionRemovedProviders,
                dailyOverplayedRecordingIds = dailyPlayTimesByRec.filter { (_, ts) -> ts.size >= 3 }.keys.toSet(),
                singleSkipPenalties = singleSkipRaw.mapValues { (_, v) -> v.coerceIn(0.0, 0.5) },
                repeatedArtistPenalty = repeatedArtistPenalty,
            ),
            noveltyPreference = novelty,
            generatedAtMillis = nowMillis,
            sourceEventWatermark = watermark,
            processedEventCount = sorted.size,
        )
    }

    private fun hasIndependentSkips(timestamps: List<Long>): Boolean {
        if (timestamps.size < 2) return false
        val sorted = timestamps.sorted()
        var independentCount = 1
        var lastTs = sorted.first()
        for (i in 1 until sorted.size) {
            if (sorted[i] - lastTs >= 10_000L) {
                independentCount++
                lastTs = sorted[i]
            }
        }
        return independentCount >= 2
    }

    private fun buildSongIndex(libraryInputs: ExplicitLibraryInputs): Map<String, Song> {
        val map = LinkedHashMap<String, Song>()
        libraryInputs.knownSongsByProviderId.forEach { (id, song) -> map[id] = song }
        libraryInputs.playlistSongs.forEach { map[it.id] = it }
        libraryInputs.likedSongs.forEach { map[it.id] = it }
        return map
    }

    private fun artistKeysFor(rawArtist: String, features: TrackFeatureRecord?): Set<String> = buildSet {
        val primary = normalizePrimaryArtist(rawArtist)
        if (primary.isNotBlank()) {
            add(primary)
            add(SongIdentity.collapseHindiLatinVariants(primary))
        }
        features?.primaryArtistIds?.forEach { a ->
            if (a.isNotBlank()) {
                add(a)
                add(SongIdentity.collapseHindiLatinVariants(a))
            }
        }
    }

    private fun inferQueryCategory(query: String): String? = when {
        listOf("bhajan", "chalisa", "aarti", "mantra", "bhakti", "devotional").any { it in query } -> GenreOntology.DEVOTIONAL
        listOf("sufi", "ghazal", "qawwali").any { it in query } -> GenreOntology.SUFI_GHAZAL
        listOf("punjabi", "bhangra").any { it in query } -> GenreOntology.PUNJABI
        listOf("tamil", "telugu", "malayalam", "kannada").any { it in query } -> GenreOntology.SOUTH_INDIAN
        listOf("lofi", "lo-fi", "chill").any { it in query } -> GenreOntology.LOFI_CHILL
        listOf("90s", "retro", "old", "kishore", "rafi", "lata", "kumar sanu").any { it in query } -> "bollywood_retro"
        listOf("sad", "breakup", "romantic", "arijit", "bollywood", "hindi").any { it in query } -> GenreOntology.BOLLYWOOD
        listOf("indie", "prateek", "anuv").any { it in query } -> GenreOntology.INDIAN_INDIE
        else -> null
    }

    private data class SurveyGenreSignals(
        val genres: List<String> = emptyList(),
        val subgenres: List<String> = emptyList(),
        val musicTypes: List<MusicType> = emptyList(),
        val moods: List<String> = emptyList(),
    )

    private fun resolveSurveyGenreSignals(raw: String): SurveyGenreSignals {
        val norm = raw.trim().lowercase(Locale.ROOT)
        if (norm.isBlank()) return SurveyGenreSignals()
        val directGenre = GenreOntology.canonicalize(norm)
        return when {
            norm in setOf("bhakti", "devotional", "bhajan", "aarti", "mantra", GenreOntology.DEVOTIONAL) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.DEVOTIONAL),
                    subgenres = listOf("bhajan", "aarti", "mantra"),
                    musicTypes = listOf(MusicType.BHAJAN, MusicType.DEVOTIONAL, MusicType.AARTI, MusicType.CHALISA, MusicType.MANTRA),
                    moods = listOf("devotional", "peaceful"),
                )
            norm in setOf("sufi", "ghazal", "sufi & ghazal", "qawwali", GenreOntology.SUFI_GHAZAL) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.SUFI_GHAZAL),
                    subgenres = listOf("sufi", "ghazal", "qawwali"),
                    musicTypes = listOf(MusicType.SUFI, MusicType.GHAZAL, MusicType.QAWALI),
                    moods = listOf("soulful", "peaceful"),
                )
            norm in setOf("bollywood", "hindi film", "hindi hits", GenreOntology.BOLLYWOOD) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.BOLLYWOOD),
                    subgenres = listOf("bollywood_romantic", "bollywood_party"),
                    musicTypes = listOf(MusicType.FILM_SONG, MusicType.SONG),
                )
            norm in setOf("90s", "retro", "90s & retro", "bollywood_retro", "old is gold") ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.BOLLYWOOD),
                    subgenres = listOf("bollywood_retro", "bollywood_90s"),
                    musicTypes = listOf(MusicType.FILM_SONG),
                )
            norm in setOf("romantic", "romance", "love", "bollywood_romantic") ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.BOLLYWOOD, GenreOntology.WESTERN_POP),
                    subgenres = listOf("bollywood_romantic"),
                    moods = listOf("romantic"),
                )
            norm in setOf("sad", "heartbreak", "bollywood_sad") ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.BOLLYWOOD, GenreOntology.INDIAN_INDIE),
                    subgenres = listOf("bollywood_sad"),
                    moods = listOf("sad", "melancholic"),
                )
            norm in setOf("punjabi", "bhangra", "punjabi beats", GenreOntology.PUNJABI) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.PUNJABI),
                    subgenres = listOf("punjabi_pop", "punjabi_bhangra"),
                    moods = listOf("energetic", "party"),
                )
            norm in setOf("south indian", "tamil & telugu", "tollywood", "kollywood", GenreOntology.SOUTH_INDIAN) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.SOUTH_INDIAN),
                    subgenres = listOf("tamil_film", "telugu_film", "malayalam_film", "kannada_film"),
                    musicTypes = listOf(MusicType.FILM_SONG),
                )
            norm in setOf("indian indie", "indie", "acoustic", GenreOntology.INDIAN_INDIE) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.INDIAN_INDIE),
                    subgenres = listOf("indie_pop", "indie_acoustic"),
                    musicTypes = listOf(MusicType.INDEPENDENT),
                )
            norm in setOf("classical", "indian classical", "instrumental", GenreOntology.INDIAN_CLASSICAL) ->
                SurveyGenreSignals(
                    genres = listOf(GenreOntology.INDIAN_CLASSICAL),
                    subgenres = listOf("hindustani_classical", "carnatic", "raga_instrumental"),
                    musicTypes = listOf(MusicType.CLASSICAL, MusicType.INSTRUMENTAL),
                )
            norm in setOf("pop", "western pop", "international pop", GenreOntology.WESTERN_POP) ->
                SurveyGenreSignals(genres = listOf(GenreOntology.WESTERN_POP), subgenres = listOf("synth_pop", "dance_pop"))
            norm in setOf("hip hop", "hip-hop", "rap", "desi hip hop", GenreOntology.HIP_HOP) ->
                SurveyGenreSignals(genres = listOf(GenreOntology.HIP_HOP), subgenres = listOf("desi_hip_hop"))
            norm in setOf("rock", "alt rock", GenreOntology.ROCK) ->
                SurveyGenreSignals(genres = listOf(GenreOntology.ROCK), subgenres = listOf("alt_rock", "classic_rock"))
            norm in setOf("edm", "electronic", "dance", "party", GenreOntology.EDM) ->
                SurveyGenreSignals(genres = listOf(GenreOntology.EDM), moods = listOf("energetic", "party"))
            norm in setOf("lofi", "lo-fi", "lofi & chill", "chill", GenreOntology.LOFI_CHILL) ->
                SurveyGenreSignals(genres = listOf(GenreOntology.LOFI_CHILL), subgenres = listOf("lofi_beats"), moods = listOf("chill", "peaceful"))
            directGenre != null -> {
                val parent = GenreOntology.parentOf(directGenre)
                SurveyGenreSignals(
                    genres = listOf(parent).distinct(),
                    subgenres = if (parent != directGenre) listOf(directGenre) else emptyList(),
                )
            }
            else -> SurveyGenreSignals(genres = listOf(norm))
        }
    }

    private fun normalizeLanguagePreferences(langs: List<String>): Set<String> = langs.mapNotNull { lang ->
        when (lang.trim().lowercase(Locale.ROOT)) {
            "hindi" -> "hi"
            "english" -> "en"
            "punjabi" -> "pa"
            "tamil" -> "ta"
            "telugu" -> "te"
            "malayalam" -> "ml"
            "kannada" -> "kn"
            "bengali", "bangla" -> "bn"
            "marathi" -> "mr"
            "gujarati" -> "gu"
            "bhojpuri" -> "bho"
            "haryanvi" -> "bgc"
            "sanskrit" -> "sa"
            "urdu" -> "ur"
            "" -> null
            else -> lang.trim().lowercase(Locale.ROOT)
        }
    }.toSet()

    companion object {
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000L
        const val FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000L
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000L
        const val SESSION_WINDOW_MS = 4L * 60 * 60 * 1000L
        const val SEARCH_WINDOW_MS = 48L * 60 * 60 * 1000L

        private val DECAY_LONG_TERM = kotlin.math.ln(2.0) / (90.0 * ONE_DAY_MS)
        private val DECAY_MEDIUM_TERM = kotlin.math.ln(2.0) / (14.0 * ONE_DAY_MS)
        private val DECAY_SESSION = kotlin.math.ln(2.0) / (2.0 * 60.0 * 60.0 * 1000.0)
        private val DECAY_SEARCH = kotlin.math.ln(2.0) / (24.0 * 60.0 * 60.0 * 1000.0)

        /**
         * Combines saturation (prevents raw exposure count dominance) with L-infinity scaling into [-1.0, 1.0].
         */
        private fun <K> saturateAndNormalize(raw: Map<K, Double>): Map<K, Double> {
            if (raw.isEmpty()) return emptyMap()
            val saturated = raw.mapValues { (_, v) -> v / (1.0 + abs(v) * 0.25) }
            val maxAbs = saturated.values.maxOfOrNull { abs(it) }?.takeIf { it > 0.0 } ?: return emptyMap()
            return saturated.mapValues { (_, v) -> (v / maxAbs).coerceIn(-1.0, 1.0) }
                .filterValues { abs(it) > 1e-4 }
        }
    }
}
