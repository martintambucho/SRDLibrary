package com.example.pocfacerecognition

import com.example.pocfacerecognition.common.SRDFace
import com.example.pocfacerecognition.common.SRDFaceTrackingOptions
import com.example.pocfacerecognition.common.TrackingEvent
import kotlinx.coroutines.flow.MutableStateFlow

interface IFaceTracker {
    @Throws(IllegalStateException::class)
    fun addFace(srdFace: SRDFace, completion: (Exception?, SRDFace?) -> Unit)
    fun getFace(identifier: String): SRDFace?
    fun removeFace(identifier: String)
    fun start(options: SRDFaceTrackingOptions): MutableStateFlow<TrackingEvent>
    fun stop()
}