package com.velvet.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Pure functions and image processing engine for playlist cover art.
 */
object PlaylistCoverManager {

    const val MAX_IMAGE_DIMENSION = 1024
    const val JPEG_QUALITY = 88

    data class CropRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Pure function: calculates the largest square crop centered in the given dimensions.
     */
    fun calculateSquareCrop(width: Int, height: Int): CropRect {
        if (width <= 0 || height <= 0) return CropRect(0, 0, 0, 0, 0, 0)
        return if (width > height) {
            val offset = (width - height) / 2
            CropRect(left = offset, top = 0, right = offset + height, bottom = height, width = height, height = height)
        } else if (height > width) {
            val offset = (height - width) / 2
            CropRect(left = 0, top = offset, right = width, bottom = offset + width, width = width, height = width)
        } else {
            CropRect(left = 0, top = 0, right = width, bottom = height, width = width, height = height)
        }
    }

    /**
     * Pure function: calculates standard power-of-2 inSampleSize for decoding.
     */
    fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int = MAX_IMAGE_DIMENSION, reqHeight: Int = MAX_IMAGE_DIMENSION): Int {
        var inSampleSize = 1
        if (srcWidth > reqWidth || srcHeight > reqHeight) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Pure function: maps ExifInterface orientation tags to rotation degrees.
     */
    fun exifOrientationToDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    /**
     * Pure function: calculates target dimensions constrained to maxDim while preserving square aspect ratio.
     */
    fun calculateTargetSize(cropWidth: Int, cropHeight: Int, maxDim: Int = MAX_IMAGE_DIMENSION): Pair<Int, Int> {
        if (cropWidth <= 0 || cropHeight <= 0) return Pair(0, 0)
        val dim = minOf(cropWidth, cropHeight, maxDim)
        return Pair(dim, dim)
    }

    /**
     * Processes an image from [inputBytes]:
     * - Decodes with inSampleSize
     * - Rotates based on EXIF tag
     * - Center-crops to square
     * - Downscales to max 1024x1024
     * - Saves as JPEG quality 88 into covers/<playlistId>_<timestamp>.jpg
     *
     * Returns the file name (e.g. "<playlistId>_<timestamp>.jpg") or null on error/corruption.
     */
    fun processAndSaveCover(context: Context, playlistId: String, inputBytes: ByteArray): String? {
        if (inputBytes.isEmpty()) return null
        return try {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val fileName = "${playlistId}_${System.currentTimeMillis()}.jpg"
            val targetFile = File(coversDir, fileName)

            // Read EXIF orientation
            val exif = ExifInterface(ByteArrayInputStream(inputBytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationDegrees = exifOrientationToDegrees(orientation)

            // Decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
            options.inJustDecodeBounds = false

            var decoded = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, options) ?: return null

            // Apply rotation if needed
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated != decoded) {
                    decoded.recycle()
                    decoded = rotated
                }
            }

            // Crop to center square
            val crop = calculateSquareCrop(decoded.width, decoded.height)
            var square = if (crop.left > 0 || crop.top > 0 || crop.width < decoded.width || crop.height < decoded.height) {
                val cropped = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
                if (cropped != decoded) {
                    decoded.recycle()
                }
                cropped
            } else {
                decoded
            }

            // Downscale to <= 1024 if needed
            val targetSize = calculateTargetSize(square.width, square.height)
            if (square.width > targetSize.first || square.height > targetSize.second) {
                val scaled = Bitmap.createScaledBitmap(square, targetSize.first, targetSize.second, true)
                if (scaled != square) {
                    square.recycle()
                }
                square = scaled
            }

            // Compress to JPEG 88
            FileOutputStream(targetFile).use { out ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.fd.sync()
            }
            square.recycle()

            fileName
        } catch (oom: OutOfMemoryError) {
            System.gc()
            null
        } catch (e: Exception) {
            null
        }
    }

    fun deleteCoverFile(context: Context, fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return try {
            val file = File(File(context.filesDir, "covers"), fileName)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }
}
