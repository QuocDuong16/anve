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
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, "synthetic-private-metadata")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "10/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "106/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
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
            assertTrue(exif.getAttribute(ExifInterface.TAG_USER_COMMENT).isNullOrEmpty())
            assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
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
}
