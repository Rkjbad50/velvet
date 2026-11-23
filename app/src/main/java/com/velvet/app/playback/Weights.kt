package com.velvet.app.playback

/**
 * Hyperparameters and feature weights for Autoplay Engine V2 Scoring & Diversity.
 * Kept in this single constants object for transparent tuning, testability, and future ML model weights.
 */
object Weights {
    // ── Feature Weights ────────────────────────────────────────────────────────

    // W1: Generator Rank & Source Agreement
    // Position in G1 (radio) + Multi-generator agreement bonus (+15 per extra source)
    const val W1_GENERATOR_RANK_SCORE = 1.0
    const val G1_RANK_MAX_BONUS = 20.0
    const val MULTI_SOURCE_AGREEMENT_BONUS = 15.0

    // W2: Same Movie or Album (+35)
    // Indian music listeners strongly expect cohesion with the seed's movie/soundtrack album.
    const val W2_SAME_MOVIE_OR_ALBUM = 35.0

    // W3: Artist Similarity
    // In Music-First Autoplay V3: artist receives NO positive bonus in default similarity scoring.
    // Artist identity is strictly used for spacing and repetition control.
    const val W3_ARTIST_SIMILARITY = 0.0

    // W4: Era Proximity (+25)
    // Decade proximity (same decade = 1.0, adjacent decade = 0.5)
    const val W4_ERA_PROXIMITY = 25.0

    // W5: Language Match (+25)
    // Script & language alignment (Devanagari/Hindi match, prevents abrupt language clash)
    const val W5_LANGUAGE_MATCH = 25.0

    // W6: Personal Taste Score (+35)
    // Normalized artistAffinity and movieAffinity from TasteProfile (range [-1.0, 1.0])
    const val W6_TASTE_SCORE = 35.0

    // W7: Library Boost (+15 max)
    // Modest boost for liked or playlist songs, capped at +15 so autoplay does not just mirror the library.
    const val W7_LIBRARY_BOOST = 15.0

    // W8: Quality Score (+30 max)
    // ATV (official audio) > OMV (official video) > UGC; lyrics and metadata completeness.
    const val W8_QUALITY_ATV = 25.0
    const val W8_QUALITY_OMV = 15.0
    const val W8_QUALITY_METADATA = 5.0

    // ── Penalties ──────────────────────────────────────────────────────────────
    const val PENALTY_RECENTLY_SKIPPED = 40.0       // Skipped in current session
    const val PENALTY_PLAYED_RECENTLY = 25.0         // In session history or persisted 200 history
    const val PENALTY_DAILY_OVERPLAY = 30.0         // Played 3+ times in the last 24h
    const val PENALTY_DURATION_MISMATCH = 20.0       // Duration difference > 90s from seed
    const val PENALTY_VERSION_TAG_MISMATCH = 50.0   // Candidate has remix/slowed tag when seed did not
    const val PENALTY_GENRE_CLASH = 100.0           // e.g. Devotional vs Party secular
    const val PENALTY_LANGUAGE_CLASH = 120.0        // e.g. South Indic in Hindi Devotional session

    // ── Diversity & Exploration ───────────────────────────────────────────────
    const val EPSILON_GREEDY = 0.1                  // 10% chance of exploratory selection from top-K
    const val NOVELTY_SLOT_INTERVAL = 5             // 1 novelty track per 5 tracks (tracks owner has never played)
    const val TOP_K_POOL_SIZE = 8                   // Pool size for epsilon-greedy selection
}
