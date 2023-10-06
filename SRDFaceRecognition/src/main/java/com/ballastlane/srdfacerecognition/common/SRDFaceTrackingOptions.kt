package com.example.pocfacerecognition.common

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner

data class SRDFaceTrackingOptions(
    val lifecycleOwner: LifecycleOwner,
    val cameraSelector: CameraSelector,
    val previewView: PreviewView?
)