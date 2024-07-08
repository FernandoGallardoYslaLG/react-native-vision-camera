package com.mrousavy.camera.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import com.mrousavy.camera.core.extensions.takePicture
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.utils.FileUtils
import java.io.File
import java.io.FileOutputStream

fun rotateImageIfNeeded(photoFile: File, orientation: Int): File {
  val bitmap = BitmapFactory.decodeFile(photoFile.path)
  val matrix = Matrix()
  val rotatedBitmap = when (orientation) {
    Surface.ROTATION_90 -> {
      matrix.postRotate(90f)
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    Surface.ROTATION_180 -> {
      matrix.postRotate(180f)
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    Surface.ROTATION_270 -> {
      matrix.postRotate(270f)
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    else -> bitmap
  }

  val rotatedFile = File(photoFile.parent, "rotated_${photoFile.name}")
  FileOutputStream(rotatedFile).use { out ->
    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
  }
  return rotatedFile
}

suspend fun CameraSession.takePhoto(flash: Flash, enableShutterSound: Boolean): Photo {
  val camera = camera ?: throw CameraNotReadyError()
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
  val rotation = photoOutput.targetRotation

  val rotatedPhotoFile = rotateImageIfNeeded(File(photoFile.uri.path), rotation)

  return Photo(rotatedPhotoFile.path, size.width, size.height, Orientation.PORTRAIT, isMirrored)
}

private fun CameraSession.getEnableShutterSoundActual(enable: Boolean): Boolean {
  if (enable && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
    Log.i(CameraSession.TAG, "Ringer mode is silent (${audioManager.ringerMode}), disabling shutter sound...")
    return false
  }

  return enable
}
