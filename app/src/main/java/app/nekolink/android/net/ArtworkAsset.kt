package app.nekolink.android.net

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Result of preparing compressed artwork for conditional upload. */
data class PreparedArtwork(
    val hash: String,
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedArtwork) return false
        return hash == other.hash &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = hash.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

/**
 * Artwork asset helpers (ADR 0024): compress → SHA-256 hex → conditional upload.
 * Wire [MediaSession.artworkHash] only after ensure succeeds; client leaves [artworkUrl] null.
 */
object ArtworkAsset {
    /** Server/client soft cap for raw asset body (~512 KiB). */
    const val MAX_UPLOAD_BYTES: Int = 512 * 1024

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Validate size and produce hash + content-type for upload.
     * Returns null when empty or over [MAX_UPLOAD_BYTES] (skip artwork).
     */
    fun prepareArtwork(bytes: ByteArray): PreparedArtwork? {
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) return null
        return PreparedArtwork(
            hash = sha256Hex(bytes),
            bytes = bytes,
            contentType = guessContentType(bytes),
        )
    }

    /**
     * Compress [bitmap] to PNG or JPEG bytes under [MAX_UPLOAD_BYTES].
     * Prefers JPEG quality 85 for larger bitmaps; PNG for smaller ones.
     * Returns null when compression fails or result still exceeds the cap.
     */
    fun compressBitmap(bitmap: Bitmap): ByteArray? {
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        val preferJpeg = pixels > 256L * 256L

        if (preferJpeg) {
            compress(bitmap, Bitmap.CompressFormat.JPEG, 85)?.let { if (it.size <= MAX_UPLOAD_BYTES) return it }
            compress(bitmap, Bitmap.CompressFormat.PNG, 100)?.let { if (it.size <= MAX_UPLOAD_BYTES) return it }
            // Last resort: lower JPEG quality
            compress(bitmap, Bitmap.CompressFormat.JPEG, 70)?.let { if (it.size <= MAX_UPLOAD_BYTES) return it }
            return null
        }

        compress(bitmap, Bitmap.CompressFormat.PNG, 100)?.let { if (it.size <= MAX_UPLOAD_BYTES) return it }
        compress(bitmap, Bitmap.CompressFormat.JPEG, 85)?.let { if (it.size <= MAX_UPLOAD_BYTES) return it }
        return null
    }

    private fun compress(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray? {
        return try {
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, stream)) return null
            stream.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun guessContentType(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        return "image/jpeg"
    }
}
