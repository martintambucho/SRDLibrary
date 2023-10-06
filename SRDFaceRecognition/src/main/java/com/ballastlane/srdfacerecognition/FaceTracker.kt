package com.example.pocfacerecognition

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.media.Image
import android.util.Pair
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.pocfacerecognition.common.SRDFace
import com.example.pocfacerecognition.common.SRDFaceTrackingOptions
import com.example.pocfacerecognition.common.SRDImage
import com.example.pocfacerecognition.common.SRDMovedAwayException
import com.example.pocfacerecognition.common.SRDMultipleFacesException
import com.example.pocfacerecognition.common.SRDPreferences
import com.example.pocfacerecognition.common.TrackingEvent
import com.example.pocfacerecognition.common.TrackingEventType
import com.example.pocfacerecognition.common.getCropBitmapByCPU
import com.example.pocfacerecognition.common.getResizedBitmap
import com.example.pocfacerecognition.common.rotateBitmap
import com.example.pocfacerecognition.common.toBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.sqrt

class FaceTracker(context: Context, apikey: String) : IFaceTracker {

    private var modelFile = "mobile_face_net.tflite"

    private var trackingEventPublisher = MutableStateFlow(TrackingEvent.default)
    private var detector: FaceDetector? = null
    private var faceTrackerRepository = FaceTrackerRepository()

    private var tfLite: Interpreter? = null
    private lateinit var embeedings: Array<FloatArray>
    private var registered: HashMap<String?, SimilarityClassifier.Recognition> = HashMap()
    private lateinit var context: Context
    private var faces: MutableList<SRDFace> = mutableListOf()

    init {
        SRDPreferences.init(context)
        SRDPreferences.write(SRDPreferences.API_KEY, apikey)
        initModel(context, modelFile)
        initDetector()
    }

    private fun initModel(ctx: Context, modelFile: String) {
        this.context = ctx
        try {
            tfLite = Interpreter(loadModelFile(context, modelFile))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    internal fun loadModelFile(ctx: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = ctx.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun initDetector() {
        val highAccuracyOpts: FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(highAccuracyOpts)
    }

    override fun addFace(srdFace: SRDFace, completion: ((Exception?, SRDFace?) -> Unit)) {
        detector?.process(InputImage.fromBitmap(srdFace.samples[0], 0))
            ?.addOnSuccessListener { faces ->
                when (faces.size) {
                    0 -> {
                        completion.invoke(SRDMovedAwayException, null)
                    }

                    1 -> {
                        val face: Face? = faces[0]
                        var frameBmp: SRDImage? = null

                        try {
                            frameBmp = srdFace.samples[0]
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }

                        val frameBmp1: SRDImage? =
                            frameBmp?.let {
                                rotateBitmap(it, 0, flipX = false, flipY = false)
                            }

                        val boundingBox = RectF(face?.boundingBox)

                        val croppedFace: SRDImage = getCropBitmapByCPU(frameBmp1, boundingBox)

                        val scaledResult = getResizedBitmap(croppedFace, 112, 112)

                        try {
                            val resultFace = SRDFace(
                                identifier = srdFace.identifier,
                                samples = listOf(scaledResult)
                            )
                            registerNewFace(resultFace)
                            completion.invoke(null, resultFace)
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            completion.invoke(e, null)
                            e.printStackTrace()
                        } catch (e: IllegalStateException) {
                            completion.invoke(e, null)
                            println("POC - IllegalStateException")
                        }
                    }

                    else -> {
                        completion.invoke(SRDMultipleFacesException, null)
                    }
                }
            }
    }

    private fun registerNewFace(face: SRDFace) {
//        removeAllFaces()
        val faceWithSameIdentifier = faces.find { face.identifier == it.identifier }
        if (faceWithSameIdentifier != null) {
            removeFace(faceWithSameIdentifier.identifier)
        }
        faces.add(face)
        recognizeImage(face.samples[0]) {}
        val result = SimilarityClassifier.Recognition("0", "", -1f)
        result.extra = embeedings
        registered[face.identifier] = result
    }

    override fun getFace(identifier: String): SRDFace? {
        return faces.find { identifier == it.identifier }
    }

    override fun removeFace(identifier: String) {
        faces.removeIf { it.identifier == identifier }
        registered.remove(identifier)
    }

    private fun recognizeFace(
        image: InputImage,
        mediaImage: Image,
        imageProxy: ImageProxy,
        trackingEventCallback: ((TrackingEvent) -> Unit)?,
    ) {
        detector?.process(image)
            ?.addOnSuccessListener { faces ->
                when (faces?.size) {
                    0 -> {
                        trackingEventCallback?.invoke(TrackingEvent(event = TrackingEventType.MOVED_AWAY))
                    }

                    1 -> {
                        val face: Face? = faces[0]
                        val frameBmp = toBitmap(mediaImage)
                        val rot: Int = imageProxy.imageInfo.rotationDegrees

                        //Adjust orientation of Face
                        val frameBmp1: SRDImage =
                            rotateBitmap(
                                frameBmp,
                                rot,
                                flipX = false,
                                flipY = false
                            )

                        //Get bounding box of face
                        val boundingBox = RectF(face?.boundingBox)

                        //Crop out bounding box from whole Bitmap(image)
                        val croppedFace: SRDImage =
                            getCropBitmapByCPU(
                                frameBmp1,
                                boundingBox
                            )

                        //Scale the acquired Face to 112*112 which is required input for model
                        val scaled = getResizedBitmap(croppedFace, 112, 112)
                        recognizeImage(scaled) {
                            trackingEventCallback?.invoke(it)
                        }
                    }

                    else -> {
                        trackingEventCallback?.invoke(TrackingEvent(event = TrackingEventType.MULTIPLE_SUBJECTS))
                    }
                }
            }
            ?.addOnFailureListener {
                // Task failed with an exception
            }
            ?.addOnCompleteListener {
                imageProxy.close() //v.important to acquire next frame for analysis
            }
    }

    private fun findNearest(emb: FloatArray): List<Pair<String, Float>> {
        val neighbourList = mutableListOf<Pair<String, Float>>()
        var ret: Pair<String, Float>? = null //to get closest match
        var prevRet: Pair<String, Float>? = null //to get second closest match
        for (entry in registered.entries) {
            val name = entry.key
            val knownEmb = (entry.value.extra as Array<FloatArray>)[0]

            var distance = 0f
            for (i in emb.indices) {
                val diff = emb[i] - knownEmb[i]
                distance += diff * diff
            }
            distance = sqrt(distance.toDouble()).toFloat()

            if (ret == null || distance < ret.second) {
                prevRet = ret
                ret = Pair(name, distance)
            }
        }
        if (prevRet == null) prevRet = ret
        neighbourList.add(ret!!)
        neighbourList.add(prevRet!!)

        return neighbourList
    }

    private fun recognizeImage(bitmap: SRDImage, callback: (TrackingEvent) -> Unit) {

        //Create ByteBuffer to store normalized image
        val imgData = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        imgData.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)

        //get pixel values from Bitmap to normalize
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        imgData.rewind()
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val pixelValue = intValues[i * inputSize + j]
                imgData.putFloat(((pixelValue shr 16 and 0xFF) - imageMean) / imageSTD)
                imgData.putFloat(((pixelValue shr 8 and 0xFF) - imageMean) / imageSTD)
                imgData.putFloat(((pixelValue and 0xFF) - imageMean) / imageSTD)
            }
        }
        val inputArray = arrayOf(imgData)
        val outputMap = HashMap<Int, Any>()
        embeedings =
            Array(1) { FloatArray(outputSize) }//output of model will be stored in this variable
        outputMap[0] = embeedings
        tfLite?.runForMultipleInputsOutputs(inputArray, outputMap)

        val distanceLocal: Float

        //Compare new face with saved Faces.
        if (registered.size > 0) {
            val nearest = findNearest(embeedings[0]) //Find 2 closest matching face
            distanceLocal = nearest[0].second

            println("SRD  distance: $distanceLocal ")
            if (distanceLocal < distance) {
                callback.invoke(TrackingEvent(event = TrackingEventType.FACE_FOUND))
            } else {
                callback.invoke(TrackingEvent(event = TrackingEventType.UNKNOWN_SUBJECT))
            }
        } else {
            callback.invoke(TrackingEvent(event = TrackingEventType.MOVED_AWAY))
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun start(
        options: SRDFaceTrackingOptions
    ): MutableStateFlow<TrackingEvent> {
        val executor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

        val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) //Latest frame is shown
            .build()

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            var preview: Preview? = null
            if (options.previewView != null) {
                preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(options.previewView.surfaceProvider)
                }
            }

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                val image: InputImage?
                @SuppressLint("UnsafeExperimentalUsageError") val mediaImage:
                        Image = imageProxy.image!!
                image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )

                recognizeFace(image, mediaImage, imageProxy) {
                    trackingEventPublisher.value = it
                    CoroutineScope(Dispatchers.IO).launch {
                        faceTrackerRepository.sendTrackingEvent(it)
                    }
                }
            }

            cameraProvider.unbindAll()

            if (preview == null) {
                cameraProvider.bindToLifecycle(
                    options.lifecycleOwner,
                    options.cameraSelector,
                    imageAnalysis
                )
            } else {
                cameraProvider.bindToLifecycle(
                    options.lifecycleOwner,
                    options.cameraSelector,
                    preview,
                    imageAnalysis
                )
            }
        }, executor)

        return trackingEventPublisher
    }

    override fun stop() {
        faces.clear()
    }

    companion object {
        const val imageMean = 128.0f
        const val imageSTD = 128.0f
        const val outputSize = 192 //Output size of model
        const val inputSize = 112 //Input size for model
        const val distance = 1.1f
    }
}