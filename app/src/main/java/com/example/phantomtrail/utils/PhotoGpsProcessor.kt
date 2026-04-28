package com.example.phantomtrail.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import kotlin.math.abs

/**
 * Handles photo EXIF data processing and GPS tagging
 */
class PhotoGpsProcessor(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "PhotoGpsProcessor"
    }

    data class ProcessResult(
        val successCount: Int,
        val errorCount: Int,
        val savedPhotoUris: List<Uri>
    )

    /**
     * Process multiple photos and add GPS coordinates
     */
    suspend fun processPhotos(
        photoUris: List<Uri>,
        trailPoints: List<GeoPoint>,
        stepTimestamps: List<ZonedDateTime>
    ): ProcessResult {
        if (trailPoints.isEmpty() || stepTimestamps.isEmpty()) {
            throw IllegalStateException("No trail data available")
        }

        var successCount = 0
        var errorCount = 0
        val savedPhotoUris = mutableListOf<Uri>()

        val startTime = stepTimestamps.first()
        val endTime = stepTimestamps.last()

        for (uri in photoUris) {
            try {
                val result = processPhoto(uri, trailPoints, startTime, endTime)
                if (result != null) {
                    savedPhotoUris.add(result)
                    successCount++
                } else {
                    errorCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing photo $uri: ${e.message}", e)
                errorCount++
            }
        }

        return ProcessResult(successCount, errorCount, savedPhotoUris)
    }

    private fun processPhoto(
        uri: Uri,
        trailPoints: List<GeoPoint>,
        startTime: ZonedDateTime,
        endTime: ZonedDateTime
    ): Uri? {
        // Create temp file
        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        // Read EXIF and get timestamp
        val exif = ExifInterface(tempFile.absolutePath)
        val photoTime = getPhotoTimestamp(exif, endTime)

        // Find location on trail
        val location = GeoUtils.findLocationForTime(photoTime, trailPoints, startTime, endTime)

        // Update GPS coordinates
        updateGpsCoordinates(exif, location)
        exif.saveAttributes()

        // Try to overwrite original (Android 11+)
        var overwritten = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overwritten = tryOverwriteOriginal(uri, tempFile)
        }

        // Fallback: Create new copy
        val resultUri = if (!overwritten) {
            savePhotoToSameDirectory(uri, tempFile)
        } else {
            uri
        }

        tempFile.delete()
        return resultUri
    }

    suspend fun processPhotosWithFixedLocation(
        photoUris: List<Uri>,
        location: GeoPoint
    ): ProcessResult {
        var successCount = 0
        var errorCount = 0
        val savedPhotoUris = mutableListOf<Uri>()

        for (uri in photoUris) {
            try {
                val result = processPhotoWithFixedLocation(uri, location)
                if (result != null) {
                    savedPhotoUris.add(result)
                    successCount++
                } else {
                    errorCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing photo $uri: ${e.message}", e)
                errorCount++
            }
        }

        return ProcessResult(successCount, errorCount, savedPhotoUris)
    }

    private fun processPhotoWithFixedLocation(uri: Uri, location: GeoPoint): Uri? {
        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        val exif = ExifInterface(tempFile.absolutePath)
        updateGpsCoordinates(exif, location)
        exif.saveAttributes()

        var overwritten = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overwritten = tryOverwriteOriginal(uri, tempFile)
        }

        val resultUri = if (!overwritten) {
            savePhotoToSameDirectory(uri, tempFile)
        } else {
            uri
        }

        tempFile.delete()
        return resultUri
    }

    private fun getPhotoTimestamp(exif: ExifInterface, fallbackTime: ZonedDateTime): ZonedDateTime {
        val photoTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

        return if (photoTimeStr == null) {
            Log.w(TAG, "No timestamp in photo, using end of trail")
            fallbackTime
        } else {
            parseExifDateTime(photoTimeStr)
        }
    }

    private fun parseExifDateTime(exifDateTime: String): ZonedDateTime {
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val date = sdf.parse(exifDateTime)
        return ZonedDateTime.ofInstant(
            date?.toInstant() ?: java.time.Instant.now(),
            ZoneId.systemDefault()
        )
    }

    private fun updateGpsCoordinates(exif: ExifInterface, location: GeoPoint) {
        val lat = location.latitude
        val lon = location.longitude

        // Set latitude
        val latRef = if (lat >= 0) "N" else "S"
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GeoUtils.convertToExifFormat(abs(lat)))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latRef)

        // Set longitude
        val lonRef = if (lon >= 0) "E" else "W"
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GeoUtils.convertToExifFormat(abs(lon)))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lonRef)

        Log.d(TAG, "Updated GPS: $lat,$lon ($latRef,$lonRef)")
    }

    private fun tryOverwriteOriginal(uri: Uri, tempFile: File): Boolean {
        return try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            Log.d(TAG, "Overwrote original file: $uri")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot overwrite $uri (read-only), creating copy: ${e.message}")
            false
        }
    }

    private fun savePhotoToSameDirectory(originalUri: Uri, modifiedFile: File): Uri? {
        return try {
            val originalFileName = getFileName(originalUri) ?: "photo_${System.currentTimeMillis()}.jpg"
            var relativePath = Environment.DIRECTORY_PICTURES

            // Get original path
            contentResolver.query(originalUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    if (pathIndex >= 0) {
                        cursor.getString(pathIndex)?.let { relativePath = it }
                    }
                }
            }

            val baseName = originalFileName.substringBeforeLast(".")
            val extension = originalFileName.substringAfterLast(".", "jpg")
            val newFileName = "${baseName}_GPS.${extension}"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(newFileName, relativePath, modifiedFile)
            } else {
                saveToFileSystem(originalUri, newFileName, modifiedFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo: ${e.message}")
            null
        }
    }

    private fun saveToMediaStore(fileName: String, relativePath: String, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        val newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        newUri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Saved GPS photo to: $relativePath$fileName")
        }
        return newUri
    }

    private fun saveToFileSystem(originalUri: Uri, fileName: String, file: File): Uri? {
        val filePath = getRealPathFromURI(originalUri) ?: return null
        val originalFile = File(filePath)
        val parentDir = originalFile.parentFile ?: return null

        if (!parentDir.exists()) return null

        val newFile = File(parentDir, fileName)
        file.copyTo(newFile, overwrite = true)

        MediaScannerConnection.scanFile(
            context,
            arrayOf(newFile.absolutePath),
            arrayOf("image/jpeg"),
            null
        )

        return Uri.fromFile(newFile)
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        var realPath: String? = null

        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (columnIndex >= 0) {
                        realPath = cursor.getString(columnIndex)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            realPath = uri.path
        }

        return realPath
    }
}