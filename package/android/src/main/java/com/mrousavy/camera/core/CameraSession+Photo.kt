package com.mrousavy.camera.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.mrousavy.camera.core.extensions.takePicture
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.utils.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.mrousavy.camera.core.extensions.id

@RequiresApi(Build.VERSION_CODES.Q)

fun getDepthVariance(cameraId: String, cameraManager: CameraManager): Double? {
  try {
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

    return if (capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
      0.00
    } else {
      null
    }
  } catch (e: Exception) {
    println("DepthCheckError-getDepthVariance-CameraSessionPhoto: ${e.message}")
    return null
  }
}

private fun savePhotoToDownloads(context: Context, photoFile: File): Uri {
  val contentResolver = context.contentResolver
  val contentValues = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, photoFile.name)
    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
  }

  val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
    ?: throw IOException("Failed to create new MediaStore record.")

  try {
    contentResolver.openOutputStream(uri).use { outputStream ->
      val inputStream = photoFile.inputStream()
      inputStream.copyTo(outputStream!!)
      inputStream.close()
    }
  } catch (e: IOException) {
    contentResolver.delete(uri, null, null)
    throw e
  }

  return uri
}

fun rotateImageIfNeeded(photoFile: File): File {
  val bitmap = BitmapFactory.decodeFile(photoFile.path)
  var exif: ExifInterface? = null
  try {
    exif = ExifInterface(photoFile)
  } catch (e: IOException) {
    e.printStackTrace()
  }

  val orientation = exif?.getAttributeInt(
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.ORIENTATION_NORMAL
  )

  val matrix = Matrix()
  val degrees = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> 0f
  }

  matrix.postRotate(degrees)
  val rotatedBitmap = Bitmap.createBitmap(
    bitmap,
    0,
    0,
    bitmap.width,
    bitmap.height,
    matrix,
    true
  )

  val rotatedFile = File(photoFile.parent, "rotated_${photoFile.name}")
  FileOutputStream(rotatedFile).use { out ->
    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
  }
  return rotatedFile
}

@RequiresApi(Build.VERSION_CODES.Q)
suspend fun CameraSession.takePhoto(flash: Flash, enableShutterSound: Boolean): Photo {
  val camera = camera ?: throw CameraNotReadyError()
  val cameraId = camera.cameraInfo.id ?: "0"
  val configuration = configuration ?: throw CameraNotReadyError()
  val photoConfig = configuration.photo as? CameraConfiguration.Output.Enabled<CameraConfiguration.Photo> ?: throw PhotoNotEnabledError()
  val photoOutput = photoOutput ?: throw PhotoNotEnabledError()

  if (flash != Flash.OFF && !camera.cameraInfo.hasFlashUnit()) {
    throw FlashUnavailableError()
  }

  photoOutput.flashMode = flash.toFlashMode()
  val enableShutterSoundActual = getEnableShutterSoundActual(enableShutterSound)

  val isMirrored = photoConfig.config.isMirrored
  val photoFile = photoOutput.takePicture(
    context,
    isMirrored,
    enableShutterSoundActual,
    metadataProvider,
    callback,
    CameraQueues.cameraExecutor
  )

  val size = FileUtils.getImageSize(photoFile.uri.path)

  val rotatedPhotoFile = rotateImageIfNeeded(File(photoFile.uri.path))

  val savedPhotoUri = savePhotoToDownloads(context, rotatedPhotoFile)

  val depthVariance = getDepthVariance(cameraId,camera2)

  return Photo(rotatedPhotoFile.path, size.width, size.height, Orientation.PORTRAIT, isMirrored, depthVariance)
}

private fun CameraSession.getEnableShutterSoundActual(enable: Boolean): Boolean {
  if (enable && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
    Log.i(CameraSession.TAG, "Ringer mode is silent (${audioManager.ringerMode}), disabling shutter sound...")
    return false
  }

  return enable
}
