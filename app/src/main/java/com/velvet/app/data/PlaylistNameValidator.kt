package com.velvet.app.data

/**
 * Pure-Kotlin validator for playlist renaming and management.
 * Rules:
 * - Prefilled and trims whitespace
 * - Rejects empty name
 * - Max 60 chars
 * - Rejects duplicate names (case-insensitive) across existing playlists, excluding current playlist
 */
object PlaylistNameValidator {
    const val MAX_LENGTH = 60

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Error(val message: String) : ValidationResult
    }

    fun validate(
        newName: String,
        currentPlaylistId: String?,
        existingPlaylists: List<Playlist>,
    ): ValidationResult {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Error("Playlist name cannot be empty")
        }
        if (trimmed.length > MAX_LENGTH) {
            return ValidationResult.Error("Playlist name cannot exceed $MAX_LENGTH characters")
        }
        val isDuplicate = existingPlaylists.any {
            it.id != currentPlaylistId && it.name.trim().equals(trimmed, ignoreCase = true)
        }
        if (isDuplicate) {
            return ValidationResult.Error("A playlist with this name already exists")
        }
        return ValidationResult.Valid
    }
}
