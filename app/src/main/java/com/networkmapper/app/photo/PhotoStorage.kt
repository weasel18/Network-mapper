package com.networkmapper.app.photo

import android.content.Context
import android.location.Location
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.networkmapper.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

object PhotoStorage {

    fun photosDir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    fun newPhotoFile(context: Context): File {
        val name = "IMG_${Fmt.fileStamp()}_${Random.nextInt(1000, 9999)}.jpg"
        return File(photosDir(context), name)
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Writes the GPS fix into the JPEG's EXIF so the location travels with the image. */
    suspend fun tagLocation(file: File, location: Location) = withContext(Dispatchers.IO) {
        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setGpsInfo(location)
            exif.saveAttributes()
        } catch (e: Exception) {
            // Not a JPEG or not writable: the location is still stored in the database.
        }
    }

    /** Reads a location already embedded in the image (e.g. from the camera app), if any. */
    suspend fun readLocation(file: File): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val ll = ExifInterface(file.absolutePath).latLong ?: return@withContext null
            if (ll[0] == 0.0 && ll[1] == 0.0) null else ll[0] to ll[1]
        } catch (e: Exception) {
            null
        }
    }

    /** Copies an image picked from the gallery into private storage. */
    suspend fun importFromUri(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        val dest = newPhotoFile(context)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            dest
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }
}
