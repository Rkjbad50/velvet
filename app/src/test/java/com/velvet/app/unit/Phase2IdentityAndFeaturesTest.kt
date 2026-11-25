package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.recommendation.features.EnrichmentMetadata
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.MetadataEnrichmentProvider
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.identity.ReleaseAudioClassifier
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.model.TempoBucket
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.model.VersionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2IdentityAndFeaturesTest {

    @Test
    fun strictOriginalAudioClassifier_acceptsVerifiedStudioAudioSources() {
        val topicRelease = Song(
            id = "top1",
            title = "Kesariya",
            artist = "Arijit Singh",
            durationSec = 268,
            isTopicRelease = true,
            rawUploaderName = "Arijit Singh - Topic",
            preferredMusicSource = true,
        )
        val distributorDescRelease = Song(
            id = "dist1",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            durationSec = 262,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
            releaseDescriptionHint = "Provided to YouTube by T-Series",
            preferredMusicSource = true,
        )
        val labelAudio = Song(
            id = "lbl1",
            title = "Agar Tum Saath Ho (Official Audio)",
            artist = "Alka Yagnik, Arijit Singh",
            durationSec = 341,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )
        val ostAudio = Song(
            id = "ost1",
            title = "Kun Faya Kun (Original Motion Picture Soundtrack)",
            artist = "A.R. Rahman",
            durationSec = 472,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )
        val legitimateWordVideoInTitle = Song(
            id = "vg1",
            title = "Video Games",
            artist = "Lana Del Rey",
            durationSec = 282,
            isTopicRelease = true,
            rawUploaderName = "Lana Del Rey - Topic",
            preferredMusicSource = true,
        )

        assertEquals(ReleaseAudioClass.VERIFIED_TOPIC_AUDIO, ReleaseAudioClassifier.classify(topicRelease).audioClass)
        assertTrue(ReleaseAudioClassifier.classify(topicRelease).isVerifiedOriginalAudio)

        assertEquals(ReleaseAudioClass.VERIFIED_TOPIC_AUDIO, ReleaseAudioClassifier.classify(distributorDescRelease).audioClass)
        assertTrue(ReleaseAudioClassifier.classify(distributorDescRelease).isVerifiedOriginalAudio)

        assertEquals(ReleaseAudioClass.VERIFIED_LABEL_STUDIO_AUDIO, ReleaseAudioClassifier.classify(labelAudio).audioClass)
        assertTrue(ReleaseAudioClassifier.classify(labelAudio).isVerifiedOriginalAudio)

        assertEquals(ReleaseAudioClass.VERIFIED_OST_AUDIO, ReleaseAudioClassifier.classify(ostAudio).audioClass)
        assertTrue(ReleaseAudioClassifier.classify(ostAudio).isVerifiedOriginalAudio)

        assertEquals(ReleaseAudioClass.VERIFIED_TOPIC_AUDIO, ReleaseAudioClassifier.classify(legitimateWordVideoInTitle).audioClass)
        assertTrue(ReleaseAudioClassifier.classify(legitimateWordVideoInTitle).isVerifiedOriginalAudio)
    }

    @Test
    fun strictOriginalAudioClassifier_rejectsVideosLyricsVisualizersLiveAndAlternatesEvenFromVerifiedChannels() {
        val officialVideoVerified = Song(
            id = "omv1",
            title = "Kesariya (Official Video) | Brahmastra",
            artist = "Sony Music India",
            durationSec = 268,
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
        )
        val artistOfficialMv = Song(
            id = "omv2",
            title = "Anti-Hero (Official Music Video)",
            artist = "Taylor Swift",
            durationSec = 310,
            isUploaderVerified = true,
            rawUploaderName = "Taylor Swift",
            preferredMusicSource = true,
        )
        val lyricVideo = Song(
            id = "lyr1",
            title = "Tum Hi Ho - Lyrical Video | Aashiqui 2",
            artist = "T-Series",
            durationSec = 264,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )
        val visualizer = Song(
            id = "vis1",
            title = "Blinding Lights (Official Visualizer)",
            artist = "The Weeknd",
            durationSec = 200,
            isUploaderVerified = true,
            rawUploaderName = "The Weeknd",
            preferredMusicSource = true,
        )
        val liveConcert = Song(
            id = "live1",
            title = "Channa Mereya (Live at Wembley Concert)",
            artist = "Arijit Singh",
            durationSec = 290,
            isUploaderVerified = true,
            rawUploaderName = "Arijit Singh",
            preferredMusicSource = true,
        )
        val slowedRemix = Song(
            id = "alt1",
            title = "Kesariya (Slowed + Reverb)",
            artist = "Lofi Hub",
            durationSec = 305,
        )
        val coverUpload = Song(
            id = "cov1",
            title = "Tum Hi Ho - Guitar Cover",
            artist = "Indie Creator",
            durationSec = 230,
        )
        val dialogueClip = Song(
            id = "dlg1",
            title = "Best Movie Scene | Full Dialogue Promo",
            artist = "Studio Clips",
            durationSec = 140,
        )

        val rejectedCases = listOf(
            officialVideoVerified to ReleaseAudioClass.REJECTED_OFFICIAL_VIDEO,
            artistOfficialMv to ReleaseAudioClass.REJECTED_OFFICIAL_VIDEO,
            lyricVideo to ReleaseAudioClass.REJECTED_LYRIC_VIDEO,
            visualizer to ReleaseAudioClass.REJECTED_VISUALIZER,
            liveConcert to ReleaseAudioClass.REJECTED_LIVE,
            slowedRemix to ReleaseAudioClass.REJECTED_ALTERNATE_VERSION,
            coverUpload to ReleaseAudioClass.REJECTED_COVER_OR_UGC,
            dialogueClip to ReleaseAudioClass.REJECTED_NON_MUSIC,
        )

        for ((song, expectedClass) in rejectedCases) {
            val result = ReleaseAudioClassifier.classify(song)
            assertEquals("Expected $expectedClass for ${song.title}", expectedClass, result.audioClass)
            assertFalse("Rejected candidate must never pass verified audio: ${song.title}", result.isVerifiedOriginalAudio)
        }
    }

    @Test
    fun strictOriginalAudioClassifier_failsClosedToUnverifiedWhenAudioProofMissing() {
        val verifiedArtistAmbiguous = Song(
            id = "amb1",
            title = "Kesariya",
            artist = "T-Series",
            durationSec = 268,
            isUploaderVerified = true,
            isTopicRelease = false,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )
        val unverifiedWithAudioTag = Song(
            id = "amb2",
            title = "Kesariya (Official Audio)",
            artist = "Random Reuploader",
            durationSec = 268,
            isUploaderVerified = false,
            isTopicRelease = false,
            rawUploaderName = "Random Reuploader",
        )
        val legacyPreferredNoTopic = Song(
            id = "amb3",
            title = "Tera Ban Jaunga",
            artist = "Akhil Sachdeva",
            durationSec = 236,
            preferredMusicSource = true,
        )

        for (song in listOf(verifiedArtistAmbiguous, unverifiedWithAudioTag, legacyPreferredNoTopic)) {
            val cls = ReleaseAudioClassifier.classify(song)
            assertEquals("Ambiguous candidate must resolve UNVERIFIED: ${song.title}", ReleaseAudioClass.UNVERIFIED, cls.audioClass)
            assertFalse(cls.isVerifiedOriginalAudio)
        }
    }

    @Test
    fun identityResolver_separatesWorkVsRecordingVersionsAndPicksVerifiedOriginalAudio() {
        val topicStudio = Song(
            id = "top_k",
            title = "Kesariya",
            artist = "Arijit Singh",
            durationSec = 268,
            isTopicRelease = true,
            rawUploaderName = "Arijit Singh - Topic",
            preferredMusicSource = true,
        )
        val labelStudio = Song(
            id = "lbl_k",
            title = "Kesariya (Official Audio) | Brahmastra",
            artist = "Arijit Singh",
            durationSec = 269,
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
        )
        val officialVideo = Song(
            id = "vid_k",
            title = "Kesariya (Official Video) | Brahmastra",
            artist = "Sony Music India",
            durationSec = 268,
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
        )
        val liveVersion = Song(
            id = "liv_k",
            title = "Kesariya (Live Performance)",
            artist = "Arijit Singh",
            durationSec = 290,
            isUploaderVerified = true,
            rawUploaderName = "Arijit Singh",
        )
        val remixVersion = Song(
            id = "rmx_k",
            title = "Kesariya (DJ Remix)",
            artist = "Arijit Singh",
            durationSec = 220,
        )
        val acousticVersion = Song(
            id = "aco_k",
            title = "Kesariya (Acoustic)",
            artist = "Arijit Singh",
            durationSec = 240,
        )

        val idTopic = RecordingIdentityResolver.resolve(topicStudio)
        val idLabel = RecordingIdentityResolver.resolve(labelStudio)
        val idLive = RecordingIdentityResolver.resolve(liveVersion)
        val idRemix = RecordingIdentityResolver.resolve(remixVersion)
        val idAcoustic = RecordingIdentityResolver.resolve(acousticVersion)

        // Topic and Label studio releases map to same work and studio recording
        assertEquals(idTopic.workId, idLabel.workId)
        assertEquals(idTopic.canonicalRecordingId, idLabel.canonicalRecordingId)
        assertEquals(VersionKind.ORIGINAL_STUDIO, idTopic.versionKind)

        // Live, remix, and acoustic share workId but have distinct canonicalRecordingId & VersionKind
        assertEquals(idTopic.workId, idLive.workId)
        assertEquals(idTopic.workId, idRemix.workId)
        assertEquals(idTopic.workId, idAcoustic.workId)
        assertNotEquals(idTopic.canonicalRecordingId, idLive.canonicalRecordingId)
        assertNotEquals(idTopic.canonicalRecordingId, idRemix.canonicalRecordingId)
        assertNotEquals(idTopic.canonicalRecordingId, idAcoustic.canonicalRecordingId)
        assertEquals(VersionKind.LIVE, idLive.versionKind)
        assertEquals(VersionKind.REMIX, idRemix.versionKind)
        assertEquals(VersionKind.ACOUSTIC, idAcoustic.versionKind)

        assertTrue(RecordingIdentityResolver.isSameRecording(topicStudio, labelStudio))
        assertFalse(RecordingIdentityResolver.isSameRecording(topicStudio, liveVersion))
        assertFalse(RecordingIdentityResolver.isSameRecording(topicStudio, remixVersion))
        assertTrue(RecordingIdentityResolver.isSameMusicalWork(topicStudio, remixVersion))

        // Best original audio representative picks Topic over Label Audio and ignores Official Video
        val best = RecordingIdentityResolver.selectBestVerifiedOriginalAudio(listOf(officialVideo, labelStudio, topicStudio))
        assertNotNull(best)
        assertEquals(topicStudio.id, best!!.id)

        // When no candidate passes verified audio, representative selection returns null (fail-closed)
        assertNull(RecordingIdentityResolver.selectBestVerifiedOriginalAudio(listOf(officialVideo, liveVersion, remixVersion)))

        // External ISRC/MBID strengthens canonical identity without fabrication
        val enrichedId = RecordingIdentityResolver.resolve(topicStudio, musicBrainzRecordingId = "mbid-123", isrc = "IN-A12-22-00001")
        assertEquals("rec:isrc:IN-A12-22-00001", enrichedId.canonicalRecordingId)
        assertEquals(0.98, enrichedId.confidence, 0.001)
    }

    @Test
    fun ontologyAndFeatureExtractor_modelUncertaintyScriptVsLanguageAndSchemaBounds() = runBlocking {
        assertEquals(GenreOntology.DEVOTIONAL, GenreOntology.canonicalize("Bhakti"))
        assertTrue(GenreOntology.similarity("chalisa", "bhajan") >= 0.75)
        assertEquals(0.0, GenreOntology.similarity("devotional", "bollywood_party"), 0.001)
        assertEquals(0.0, GenreOntology.musicTypeCompatibility(MusicType.CHALISA, MusicType.FILM_SONG), 0.001)

        val extractor = MetadataFeatureExtractor { 1_000_000L }

        // Devanagari liturgical song keeps script separate from language and allows multi-label languages
        val chalisa = Song(
            id = "hc1",
            title = "श्री हनुमान चालीसा",
            artist = "Hariharan Bhakti",
            durationSec = 580,
            isTopicRelease = true,
            rawUploaderName = "Gulshan Kumar Bhakti - Topic",
        )
        val chalisaFeatures = extractor.extract(chalisa)
        assertTrue("Deva" in chalisaFeatures.scriptIds)
        assertTrue(MusicType.CHALISA in chalisaFeatures.musicTypes)
        assertTrue(chalisaFeatures.languageConfidence < 0.75)
        assertEquals(TempoBucket.UNKNOWN, chalisaFeatures.tempoBucket)
        assertEquals(0.0, chalisaFeatures.tempoConfidence, 0.001)
        assertNull(chalisaFeatures.audio)

        // Unknown obscure track keeps missing dimensions explicitly unknown rather than hallucinated
        val obscure = Song(id = "obs1", title = "Untitled Demo 42", artist = "Unknown Artist", durationSec = -1)
        val obscureFeatures = extractor.extract(obscure)
        assertNull(obscureFeatures.eraStartYear)
        assertNull(obscureFeatures.eraBucket)
        assertEquals(0.0, obscureFeatures.eraConfidence, 0.001)
        assertTrue(obscureFeatures.languageIds.isEmpty())
        assertEquals(0.0, obscureFeatures.languageConfidence, 0.001)
        assertEquals(ReleaseAudioClass.UNVERIFIED, obscureFeatures.releaseAudioClass)

        // Remaster year only does not invent original era
        val remasterEnriched = extractor.extract(
            song = obscure,
            enrichment = EnrichmentMetadata(
                canonicalKey = "test",
                originalReleaseYear = 2023,
                isRemasterYearOnly = true,
                confidence = 0.9,
                providerName = "musicbrainz",
            ),
        )
        assertNull(remasterEnriched.eraStartYear)
        assertEquals(0.0, remasterEnriched.eraConfidence, 0.001)
        assertTrue(remasterEnriched.isRemasterYearOnly)

        // Repository enrichment uses verified year & measured BPM safely when provided
        val repo = TrackFeatureRepository(
            extractor = extractor,
            enrichmentProvider = object : MetadataEnrichmentProvider {
                override suspend fun lookup(song: Song): EnrichmentMetadata? =
                    if (song.id == "obs1") {
                        EnrichmentMetadata(
                            canonicalKey = "obs",
                            originalReleaseYear = 1995,
                            explicitLanguageId = "hi",
                            measuredBpm = 128.0,
                            confidence = 0.92,
                            providerName = "musicbrainz",
                        )
                    } else null
            },
        )
        val enrichedMap = repo.enrichCached(listOf(obscure))
        val rec = enrichedMap.getValue("obs1")
        assertEquals(1995, rec.eraStartYear)
        assertEquals("1980_1999", rec.eraBucket)
        assertEquals(TempoBucket.FAST, rec.tempoBucket)
        assertEquals(128.0, rec.audio?.bpm ?: 0.0, 0.01)
        assertEquals(TrackFeatureRecord.CURRENT_FEATURE_SCHEMA_VERSION, rec.featureSchemaVersion)

        // Schema validation clamps invalid values into [0.0, 1.0]
        val clamped = rec.copy(
            genreIds = mapOf("bollywood" to 2.5, "nan" to Double.NaN),
            eraConfidence = 1.9,
            languageConfidence = -0.4,
        ).validated()
        assertEquals(1.0, clamped.genreIds["bollywood"] ?: 0.0, 0.001)
        assertFalse("nan" in clamped.genreIds)
        assertEquals(1.0, clamped.eraConfidence, 0.001)
        assertEquals(0.0, clamped.languageConfidence, 0.001)
    }

    @Test
    fun strictOriginalAudioClassifier_acceptsYouTubeMusicSquareCatalogAudio_andStillRejectsVideosAnd16x9() {
        val ytMusicSong = Song(
            id = "ytm1",
            title = "Apna Bana Le",
            artist = "Arijit Singh",
            durationSec = 261,
            isUploaderVerified = false,
            isTopicRelease = false,
            hasSquareArtwork = true,
            isYouTubeMusicCatalog = true,
        )
        val ytMusicVideo16x9 = Song(
            id = "ytm2",
            title = "Apna Bana Le",
            artist = "Arijit Singh",
            durationSec = 261,
            hasSquareArtwork = false,
            isYouTubeMusicCatalog = true,
        )
        val ytMusicLiveEvenIfSquare = Song(
            id = "ytm3",
            title = "Apna Bana Le (Live Concert)",
            artist = "Arijit Singh",
            durationSec = 290,
            hasSquareArtwork = true,
            isYouTubeMusicCatalog = true,
        )

        val clsValid = ReleaseAudioClassifier.classify(ytMusicSong)
        assertEquals(ReleaseAudioClass.VERIFIED_STUDIO_AUDIO, clsValid.audioClass)
        assertTrue(clsValid.isVerifiedOriginalAudio)

        val cls16x9 = ReleaseAudioClassifier.classify(ytMusicVideo16x9)
        assertEquals(ReleaseAudioClass.UNVERIFIED, cls16x9.audioClass)
        assertFalse(cls16x9.isVerifiedOriginalAudio)

        val clsLive = ReleaseAudioClassifier.classify(ytMusicLiveEvenIfSquare)
        assertEquals(ReleaseAudioClass.REJECTED_LIVE, clsLive.audioClass)
        assertFalse(clsLive.isVerifiedOriginalAudio)
    }
}
