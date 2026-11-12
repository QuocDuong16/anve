package com.anve.app.data.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarImagesInstrumentedTest {
    @Test fun selectedJpegIsOrientedCroppedAndReencodedWithoutMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "synthetic-avatar-input.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            for (x in 0..799) for (y in 300..599) bitmap.setPixel(x, y, Color.BLUE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            // Use a deterministic EXIF fixture rather than depending on the old
            // platform writer, whose saveAttributes can reject generated JPEGs.
            file.writeBytes(withSyntheticExif(file.readBytes()))
            val original = file.inputStream().use { ExifInterface(it) }
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, original.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals("synthetic-private-metadata", original.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            assertTrue(!original.getAttribute(ExifInterface.TAG_GPS_LATITUDE).isNullOrEmpty())
            val bytes = preparePickedAvatar(context.contentResolver, Uri.fromFile(file))
            assertTrue(bytes.size <= AvatarRepository.MAX_AVATAR_BYTES)
            val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(256, result.width); assertEquals(256, result.height)
                // Top red/bottom blue rotates into left blue/right red before crop.
                assertTrue(Color.blue(result.getPixel(40, 128)) > 200)
                assertTrue(Color.red(result.getPixel(215, 128)) > 200)
            } finally { result.recycle() }
            val exif = ExifInterface(bytes.inputStream())
            assertTrue(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE).isNullOrEmpty())
            assertTrue(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).isNullOrEmpty())
            // An absent orientation may be reported as UNDEFINED by Android.
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
        } finally { bitmap.recycle(); file.delete() }
    }
    @Test fun malformedSelectionFailsWithoutUploadAndOversizedResponseIsNotDecoded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "synthetic-invalid-image")
        try {
            file.writeText("not an image")
            assertTrue(runCatching { preparePickedAvatar(context.contentResolver, Uri.fromFile(file)) }.isFailure)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            bitmap.recycle()
            assertEquals(null, decodeAvatarImage(bytes))
        } finally { file.delete() }
    }
    @Test fun pickerRequestsOnlyOneImageThroughSystemContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ActivityResultContracts.PickVisualMedia().createIntent(context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        assertTrue(intent.action in setOf("android.provider.action.PICK_IMAGES", "android.intent.action.OPEN_DOCUMENT",
            "androidx.activity.result.contract.action.PICK_IMAGES"))
        assertTrue(intent.type == "image/*" || intent.type == null)
        assertTrue(!intent.getBooleanExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, false))
    }
    private fun withSyntheticExif(jpeg: ByteArray): ByteArray {
        val description = "synthetic-private-metadata\u0000".toByteArray(Charsets.US_ASCII)
        val descriptionOffset = 50 // TIFF header + three IFD0 entries + next-IFD pointer
        val gpsOffset = descriptionOffset + description.size
        val rationalsOffset = gpsOffset + 30
        val tiff = ByteBuffer.allocate(rationalsOffset + 24).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        tiff.putShort(3)
        tiff.putShort(0x010e).putShort(2).putInt(description.size).putInt(descriptionOffset)
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0)
        tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(gpsOffset)
        tiff.putInt(0).put(description)
        tiff.putShort(2)
        tiff.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0)
        tiff.putShort(2).putShort(5).putInt(3).putInt(rationalsOffset).putInt(0)
        tiff.putInt(10).putInt(1).putInt(0).putInt(1).putInt(0).putInt(1)
        val payload = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array()
        val length = payload.size + 2
        val marker = byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length ushr 8).toByte(), length.toByte())
        return jpeg.copyOfRange(0, 2) + marker + payload + jpeg.copyOfRange(2, jpeg.size)
    }

}
