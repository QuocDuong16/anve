package com.anve.app.data.avatar

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The selected URI and original pixels never enter disk-backed app state. */
suspend fun preparePickedAvatar(resolver: ContentResolver, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val raw = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 16 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Selected image is unavailable")
    val decoded = if (Build.VERSION.SDK_INT >= 28) decodeModern(raw) else decodeLegacy(raw)
    val target = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    try {
        val side = minOf(decoded.width, decoded.height)
        val x = (decoded.width - side) / 2
        val y = (decoded.height - side) / 2
        Canvas(target).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, Rect(x, y, x + side, y + side), Rect(0, 0, 256, 256), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        ByteArrayOutputStream().use { output ->
            check(target.compress(Bitmap.CompressFormat.JPEG, 95, output))
            output.toByteArray().also { require(it.size <= AvatarRepository.MAX_AVATAR_BYTES) }
        }
    } finally { decoded.recycle(); target.recycle() }
}

fun decodeAvatarImage(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..256 || bounds.outHeight !in 1..256) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

@RequiresApi(28)
private fun decodeModern(raw: ByteArray): Bitmap = ImageDecoder.decodeBitmap(
    ImageDecoder.createSource(ByteBuffer.wrap(raw)),
) { decoder, info, _ ->
    val width = info.size.width
    val height = info.size.height
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 1024.0 / maxOf(width, height))
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
}

// API 26/27 lack ImageDecoder. Bounds and sampling keep camera images bounded;
// EXIF rotation is applied before cropping and never copied into the upload.
private fun decodeLegacy(raw: ByteArray): Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
    require(options.outWidth > 0 && options.outHeight > 0)
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 1024) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    options.inPreferredConfig = Bitmap.Config.ARGB_8888
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options))
    val orientation = try {
        ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: java.io.IOException) { ExifInterface.ORIENTATION_NORMAL }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    } catch (e: Exception) { bitmap.recycle(); throw e }
}
