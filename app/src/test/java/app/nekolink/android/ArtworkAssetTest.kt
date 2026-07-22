package app.nekolink.android

import android.graphics.Bitmap
import app.nekolink.android.net.ArtworkAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ArtworkAssetTest {

    @Test
    fun sha256Hex_isLowercaseHex_ofBody() {
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        // echo -n hello | sha256sum
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ArtworkAsset.sha256Hex(bytes),
        )
    }

    @Test
    fun prepareArtwork_rejectsEmptyAndOversize() {
        assertNull(ArtworkAsset.prepareArtwork(ByteArray(0)))
        assertNull(ArtworkAsset.prepareArtwork(ByteArray(ArtworkAsset.MAX_UPLOAD_BYTES + 1)))
    }

    @Test
    fun prepareArtwork_hashesCompressedBytes_andGuessesContentType() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
        val prepared = ArtworkAsset.prepareArtwork(jpeg)
        assertNotNull(prepared)
        assertEquals(ArtworkAsset.sha256Hex(jpeg), prepared!!.hash)
        assertEquals("image/jpeg", prepared.contentType)
        assertTrue(prepared.bytes.contentEquals(jpeg))

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val p2 = ArtworkAsset.prepareArtwork(png)!!
        assertEquals("image/png", p2.contentType)
    }

    @Test
    fun compressBitmap_producesBytesUnderCap() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        val bytes = ArtworkAsset.compressBitmap(bmp)
        assertNotNull(bytes)
        assertTrue(bytes!!.isNotEmpty())
        assertTrue(bytes.size <= ArtworkAsset.MAX_UPLOAD_BYTES)
        val prepared = ArtworkAsset.prepareArtwork(bytes)
        assertNotNull(prepared)
    }
}
