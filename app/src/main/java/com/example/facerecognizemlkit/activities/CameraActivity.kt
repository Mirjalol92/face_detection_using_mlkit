package com.example.facerecognizemlkit.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.CameraSelector.LENS_FACING_FRONT
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.facerecognizemlkit.custome_views.camera.GraphicOverlay
import com.example.facerecognizemlkit.databinding.ActivityCameraXBinding
import com.example.facerecognizemlkit.facedetector.FaceContourGraphic
import com.example.facerecognizemlkit.utils.CloneableImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.demo.kotlin.facedetector.FaceGraphic
import com.google.mlkit.vision.face.*
import kotlinx.android.synthetic.main.activity_camera_x.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class CameraActivity: AppCompatActivity(){

    private lateinit var binding: ActivityCameraXBinding

    private lateinit var cameraExecutor: ExecutorService

    private val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
        .build()
    var cameraOpened = false

    private val name get() = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())

    private val contentValues get() = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
        }
    }

    private val outputOptions: ImageCapture.OutputFileOptions by lazy {
        ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()
    }

    private val imageCapture: ImageCapture by lazy {
        ImageCapture.Builder()
            .setTargetRotation(binding.root.display?.rotation ?: Surface.ROTATION_0)
            .build()
    }

    private val imageAnalysis: ImageAnalysis by lazy {
        ImageAnalysis.Builder()
            .setTargetResolution(Size(480, 640)) //640, 480
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
    }

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .build()

    private val faceDetector: FaceDetector = FaceDetection.getClient(options)

    private fun processFaceContourDetectionResult(faces: List<Face>) {
        // Task completed successfully
        binding.myGraphicOverlay.clear()
        if (faces.isEmpty()) {
            Toast.makeText(this,"No face found",Toast.LENGTH_SHORT).show()
            return
        }
        for (i in faces.indices) {
            val face = faces[i]
            val faceGraphic = FaceContourGraphic(binding.myGraphicOverlay)
            faceGraphic.updateFace(face)
            binding.myGraphicOverlay.apply {
                add(faceGraphic)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraXBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.myGraphicOverlay.apply {
//            scaleY = resources.displayMetrics.heightPixels.toFloat()/ 640.toFloat()
//            scaleX = resources.displayMetrics.widthPixels.toFloat()/ 480.toFloat()
        }

        binding.imageCaptureButton.setOnClickListener {
            takePhoto()
        }

        setTargetRotation()

        setCameraInfo()

        if (isAllPermissionGranted()){
            startCamera()
        }else{
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

    }

    private fun setCameraInfo(){
        val dm = DisplayMetrics()
        (this.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.apply {
            this.defaultDisplay.getRealMetrics(dm)
        }
        binding.myGraphicOverlay.setCameraInfo(
            dm.widthPixels,
            dm.heightPixels,
            CameraCharacteristics.LENS_FACING_FRONT
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun takePhoto(){
        if (!cameraOpened) return
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object: ImageCapture.OnImageSavedCallback{
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${outputFileResults.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
                override fun onError(ex: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${ex.message}", ex)
                }
            }
        )
    }

    private fun recordVideo(){}

    var isCompleted = true
    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener( Runnable{
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageAnalysis.setAnalyzer(cameraExecutor){ imageProxy->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees == 0 || rotationDegrees == 180) {
                    binding.myGraphicOverlay.setCameraInfo(imageProxy.width, imageProxy.height, LENS_FACING_FRONT)
                } else {
                    binding.myGraphicOverlay.setCameraInfo(imageProxy.height, imageProxy.width, LENS_FACING_FRONT)
                }
                val mediaImage = imageProxy.image
                if(mediaImage != null) {
                    lifecycleScope.launch(Dispatchers.IO){
                        if(isCompleted){
                            val mImage = CloneableImage(mediaImage).clone()
                            isCompleted = false
                            try {
                                val image = InputImage.fromMediaImage(mImage.image, imageProxy.imageInfo.rotationDegrees)
                                faceDetector.process(image)
                                    .addOnSuccessListener { faces->
                                        processFaceContourDetectionResult(faces)
                                        isCompleted = true
                                    }
                                    .addOnFailureListener {
                                        isCompleted = true
                                        Log.e(TAG,it.stackTraceToString())
                                    }
                                    .addOnCompleteListener {
                                        isCompleted = true
                                        imageProxy.close()
                                    }
                            }catch (ex:Exception){
                                ex.printStackTrace()
                                isCompleted = true
                            }catch (ex: IllegalStateException){
                                ex.printStackTrace()
                                isCompleted = true
                            }
                        }
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageCapture,   imageAnalysis)
                cameraOpened = true
            }catch (ex: Exception){
                Log.e(TAG, "Use case binding failed", ex)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setTargetRotation(){
        object: OrientationEventListener(this){
            override fun onOrientationChanged(orientation: Int) {
                val rotation = when(orientation){
                    in 45.. 134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225.. 314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                imageCapture.targetRotation = rotation
                imageAnalysis.targetRotation = rotation
            }
        }.also {
            it.enable()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (isAllPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun isAllPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) ==  PackageManager.PERMISSION_GRANTED
    }





    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}