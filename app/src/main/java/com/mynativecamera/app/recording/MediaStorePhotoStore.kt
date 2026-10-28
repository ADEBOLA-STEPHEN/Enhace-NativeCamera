package com.mynativecamera.app.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A finished, saved photo clip in the gallery. */
data class SavedPhoto(val uri: Uri, val displayName: String)

/**
 * Scoped-storage photo sink.
 * Writes JPEGs, computational HDR Bitmaps, and Adobe DNG RAWs to MediaStore under DCIM/MyNativeCamera.
 */
class MediaStorePhotoStore(context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val collection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Throws(IOException::class)
    fun savePhoto(jpegBytes: ByteArray): SavedPhoto {
        val stamp = LocalDateTime.now().format(STAMP_FORMAT)
        val displayName = "MNC_PHOTO_$stamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_JPEG)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create image $displayName")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(jpegBytes)
                out.flush()
            } ?: throw IOException("Cannot open output stream for $displayName")

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            return SavedPhoto(uri, displayName)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Failed to save photo $displayName", e)
            throw IOException("Error saving photo: ${e.message}", e)
        }
    }

    @Throws(IOException::class)
    fun saveBitmap(bitmap: Bitmap, prefix: String = "MNC_HDR_"): SavedPhoto {
        val stamp = LocalDateTime.now().format(STAMP_FORMAT)
        val displayName = "$prefix$stamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_JPEG)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create image $displayName")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                if (!ok) throw IOException("Bitmap.compress returned false for $displayName")
                out.flush()
            } ?: throw IOException("Cannot open output stream for $displayName")

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            return SavedPhoto(uri, displayName)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Failed to save HDR photo $displayName", e)
            throw IOException("Error saving HDR photo: ${e.message}", e)
        } finally {
            bitmap.recycle()
        }
    }

    @Throws(IOException::class)
    fun saveRawDng(
        rawImage: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
    ): SavedPhoto {
        val stamp = LocalDateTime.now().format(STAMP_FORMAT)
        val displayName = "MNC_RAW_$stamp.dng"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_DNG)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create DNG $displayName")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                val dngCreator = DngCreator(characteristics, captureResult)
                dngCreator.writeImage(out, rawImage)
                out.flush()
                dngCreator.close()
            } ?: throw IOException("Cannot open output stream for $displayName")

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            return SavedPhoto(uri, displayName)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Failed to save DNG $displayName", e)
            throw IOException("Error saving DNG: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "MediaStorePhotoStore"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_DNG = "image/x-adobe-dng"
        val RELATIVE_DIR: String = "${Environment.DIRECTORY_DCIM}/MyNativeCamera"
        private val STAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    }
}
