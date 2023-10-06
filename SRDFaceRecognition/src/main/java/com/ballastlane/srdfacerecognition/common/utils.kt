package com.example.pocfacerecognition.common

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ReadOnlyBufferException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.experimental.inv

fun generateRandomString(): String {
    val random = Random()
    val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val randomString = StringBuilder()
    for (i in 0 until 10) {
        randomString.append(letters[random.nextInt(letters.length)])
    }
    return randomString.toString()
}

fun getCropBitmapByCPU(source: Bitmap?, cropRectF: RectF): Bitmap {
    val resultBitmap = Bitmap.createBitmap(
        cropRectF.width().toInt(),
        cropRectF.height().toInt(),
        Bitmap.Config.ARGB_8888
    )
    val cavas = Canvas(resultBitmap)

    // draw background
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    paint.color = Color.WHITE
    cavas.drawRect(
        RectF(0f, 0f, cropRectF.width(), cropRectF.height()),
        paint
    )
    val matrix = Matrix()
    matrix.postTranslate(-cropRectF.left, -cropRectF.top)
    cavas.drawBitmap(source!!, matrix, paint)
    if (!source.isRecycled) {
        source.recycle()
    }
    return resultBitmap
}

fun rotateBitmap(
    bitmap: Bitmap, rotationDegrees: Int, flipX: Boolean, flipY: Boolean
): Bitmap {
    val matrix = Matrix()

    // Rotate the image back to straight.
    matrix.postRotate(rotationDegrees.toFloat())

    // Mirror the image along the X or Y axis.
    matrix.postScale(if (flipX) -1.0f else 1.0f, if (flipY) -1.0f else 1.0f)
    val rotatedBitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

    // Recycle the old bitmap if it has changed.
    if (rotatedBitmap != bitmap) {
        bitmap.recycle()
    }
    return rotatedBitmap
}

//IMPORTANT. If conversion not done ,the toBitmap conversion does not work on some devices.
fun YUV_420_888toNV21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = width * height / 4
    val nv21 = ByteArray(ySize + uvSize * 2)
    val yBuffer = image.planes[0].buffer // Y
    val uBuffer = image.planes[1].buffer // U
    val vBuffer = image.planes[2].buffer // V
    var rowStride = image.planes[0].rowStride
    assert(image.planes[0].pixelStride == 1)
    var pos = 0
    if (rowStride == width) { // likely
        yBuffer[nv21, 0, ySize]
        pos += ySize
    } else {
        var yBufferPos = -rowStride.toLong() // not an actual position
        while (pos < ySize) {
            yBufferPos += rowStride.toLong()
            yBuffer.position(yBufferPos.toInt())
            yBuffer[nv21, pos, width]
            pos += width
        }
    }
    rowStride = image.planes[2].rowStride
    val pixelStride = image.planes[2].pixelStride
    assert(rowStride == image.planes[1].rowStride)
    assert(pixelStride == image.planes[1].pixelStride)
    if (pixelStride == 2 && rowStride == width && uBuffer[0] == vBuffer[1]) {
        // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
        val savePixel = vBuffer[1]
        try {
            vBuffer.put(1, savePixel.inv())
            if (uBuffer[0] == savePixel.inv()) {
                vBuffer.put(1, savePixel)
                vBuffer.position(0)
                uBuffer.position(0)
                vBuffer[nv21, ySize, 1]
                uBuffer[nv21, ySize + 1, uBuffer.remaining()]
                return nv21 // shortcut
            }
        } catch (ex: ReadOnlyBufferException) {
            // unfortunately, we cannot check if vBuffer and uBuffer overlap
        }

        // unfortunately, the check failed. We must save U and V pixel by pixel
        vBuffer.put(1, savePixel)
    }

    // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
    // but performance gain would be less significant
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val vuPos = col * pixelStride + row * rowStride
            nv21[pos++] = vBuffer[vuPos]
            nv21[pos++] = uBuffer[vuPos]
        }
    }
    return nv21
}

fun toBitmap(image: Image?): Bitmap {
    val nv21: ByteArray? =
        image?.let { YUV_420_888toNV21(it) }
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image!!.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 75, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun getResizedBitmap(bm: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
    val width = bm.width
    val height = bm.height
    val scaleWidth = newWidth.toFloat() / width
    val scaleHeight = newHeight.toFloat() / height
    // CREATE A MATRIX FOR THE MANIPULATION
    val matrix = Matrix()
    // RESIZE THE BIT MAP
    matrix.postScale(scaleWidth, scaleHeight)

    // "RECREATE" THE NEW BITMAP
    val resizedBitmap = Bitmap.createBitmap(
        bm, 0, 0, width, height, matrix, false
    )
    bm.recycle()
    return resizedBitmap
}

private fun exifToDegrees(exifOrientation: Int): Int {
    return when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> {
            90
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            180
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            270
        }

        else -> 0
    }
}

fun decodeBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.capacity()).also { buffer.get(it) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

@Throws(IOException::class)
fun getBitmapFromUri(uri: Uri?, resolver: ContentResolver): Bitmap {
    val parcelFileDescriptor: ParcelFileDescriptor? =
        uri?.let { resolver.openFileDescriptor(it, "r") }
    val fileDescriptor = parcelFileDescriptor?.fileDescriptor
    val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
    parcelFileDescriptor?.close()
    val inputStream = resolver.openInputStream(uri!!)

    val exif = ExifInterface(inputStream!!)
    val rotation =
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val rotationInDegrees: Int = exifToDegrees(rotation)
    val matrix = Matrix()
    if (rotation != 0) {
        matrix.preRotate(rotationInDegrees.toFloat())
    }
    inputStream.close()
    return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
}

fun getTimestamp(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    val date = Date()
    return formatter.format(date)
}