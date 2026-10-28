package com.mynativecamera.app.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

/** A finished, visible clip in the gallery. */
data class SavedClip(val uri: Uri, val displayName: String)

/** A MediaStore row that is still being written (IS_PENDING = 1, hidden from other apps). */
class PendingVideo internal constructor(
    val uri: Uri,
    val displayName: String,
    val descriptor: ParcelFileDescriptor,
)

/**
 * Scoped-storage video sink. No java.io.File paths and no storage permission: rows are
 * inserted into MediaStore under DCIM/MyNativeCamera, written through a file descriptor,
 * and published atomically by clearing IS_PENDING when the recorder has finalised the MP4.
 *
 * All methods do binder/disk I/O - call them from a background dispatcher only.
 */
class MediaStoreVideoStore(context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val collection: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Throws(IOException::class)
    fun createPending(displayName: String): PendingVideo {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_MP4)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create $displayName")

        val pfd = try {
            // Mode "w" for newly inserted pending videos in scoped storage, fallback to "rwt" or "rw"
            resolver.openFileDescriptor(uri, "w")
                ?: resolver.openFileDescriptor(uri, "rwt")
                ?: resolver.openFileDescriptor(uri, "rw")
        } catch (_: Exception) {
            try {
                resolver.openFileDescriptor(uri, "rwt")
                    ?: resolver.openFileDescriptor(uri, "rw")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw IOException("Cannot open $displayName for writing", e)
            }
        } ?: run {
            resolver.delete(uri, null, null)
            throw IOException("Cannot open $displayName for writing")
        }
        return PendingVideo(uri, displayName, pfd)
    }

    /** Closes the descriptor and makes the clip visible in Gallery / Photos. */
    fun publish(video: PendingVideo): SavedClip {
        closeQuietly(video.descriptor)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(video.uri, values, null, null)
        return SavedClip(video.uri, video.displayName)
    }

    /** Removes an empty/corrupt row (e.g. recorder failed before the first frame). */
    fun discard(video: PendingVideo) {
        closeQuietly(video.descriptor)
        runCatching { resolver.delete(video.uri, null, null) }
            .onFailure { Log.w(TAG, "Failed to delete ${video.displayName}", it) }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        runCatching { pfd.close() }
    }

    companion object {
        private const val TAG = "MediaStoreVideoStore"
        private const val MIME_MP4 = "video/mp4"
        val RELATIVE_DIR: String = "${Environment.DIRECTORY_DCIM}/MyNativeCamera"
    }
}
