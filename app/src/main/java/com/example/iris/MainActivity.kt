package com.example.iris

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
//import com.example.bme_project.R
//import com.example.bme_project.ml.DRModel3
//import com.example.bme_project.ml.MEModel1
//import com.example.iris.ml.DRModel3
//import com.example.iris.ml.MEModel1
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.support.common.ops.NormalizeOp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
//import org.tensorflow.lite.support.common.TfLiteDelegate




class MainActivity : AppCompatActivity() {

    lateinit var selectBtn: Button
    lateinit var predictBtn: Button
    lateinit var DRPrediction: TextView
    lateinit var imageView: ImageView
    lateinit var placeholderText: TextView
    lateinit var bitmap: Bitmap
    lateinit var MEPrediction: TextView
    lateinit var disclaimerText: TextView
    private lateinit var loadingIndicator: ProgressBar

    private var photoUri: Uri? = null // Add as class variable
    private val REQUEST_PERMISSIONS = 200

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
                imageView.setImageBitmap(bitmap)
                placeholderText.visibility = View.GONE
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = photoUri // Capture photoUri in a local val
            if (uri != null) {
                try {
                    bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
                    imageView.setImageBitmap(bitmap)
                    placeholderText.visibility = View.GONE
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Failed to capture image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File = getExternalFilesDir(null)!!
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectBtn = findViewById(R.id.selectBtn)
        predictBtn = findViewById(R.id.predictBtn)
        DRPrediction = findViewById(R.id.dRPrediction)
        imageView = findViewById(R.id.imageView)
        placeholderText = findViewById(R.id.placeholderText)
        MEPrediction = findViewById(R.id.mEPrediction)
        disclaimerText = findViewById(R.id.disclaimerText)

        val DRlabels = application.assets.open("DRLabels.txt").bufferedReader().readLines()
        val MELabels = application.assets.open("MELabels.txt").bufferedReader().readLines()

        loadingIndicator = findViewById(R.id.loadingIndicator)


        // Set up image processor for DR detection with resizing and normalization
        val DRimageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(512, 512, ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 1.0f / 255.0f))  // Normalize image between 0 and 1
            .build()


        // Set up image processor for ME detection with resizing and normalization
        val MEimageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 1.0f / 255.0f))  // Normalize image between 0 and 1
            .build()



        // Open the image picker
        selectBtn.setOnClickListener {
            requestPermissionsAndOpenPicker()
        }

        // Run prediction on selected image
        predictBtn.setOnClickListener {
            if (!::bitmap.isInitialized) {
                Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show loading indicator
            loadingIndicator.visibility = View.VISIBLE
            predictBtn.isEnabled = false  // Disable button during processing

            // Create a copy of bitmap to avoid concurrent modification
            val bitmapToProcess = bitmap.config?.let { it1 -> bitmap.copy(it1, true) }

            // Create and start background thread
            Thread {
                try {
                    // Process first model (DR)
                    val drResults = bitmapToProcess?.let { it1 -> processDRModel(it1) }

                    // Process second model (ME)
                    val meResults = bitmapToProcess?.let { it1 -> processMEModel(it1) }

                    // Update UI on main thread
                    runOnUiThread {
                        // Update UI with results
                        if (drResults != null) {
                            DRPrediction.text = "Prediction: ${drResults.first}"
                        }
                        if (meResults != null) {
                            MEPrediction.text = "Prediction: ${meResults.first}"
                        }
                        disclaimerText.visibility = View.VISIBLE

                        // Hide loading indicator
                        loadingIndicator.visibility = View.GONE
                        predictBtn.isEnabled = true  // Re-enable button
                    }
                } catch (e: Exception) {
                    e.printStackTrace()

                    // Update UI on main thread
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        loadingIndicator.visibility = View.GONE
                        predictBtn.isEnabled = true  // Re-enable button
                    }
                }
            }.start()
        }
    }

    private fun processDRModel(bitmap: Bitmap): Pair<String, Int> {
        try {
            // Create image processor
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(512, 512, ResizeMethod.BILINEAR))
                .add(NormalizeOp(0.0f, 1.0f / 255.0f))
                .build()

            // Load and process image
            val tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            val processedImage = imageProcessor.process(tensorImage)

            // Create input tensor
            val inputBuffer = processedImage.buffer

            // Create interpreter options
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(false) // Disable Neural Network API for better compatibility
//                addDelegate(TfLiteDelegate())

            }

            // Load model file directly
            val modelBuffer = ByteBuffer.allocateDirect(application.assets.open("DRModel3.tflite").readBytes().size)
            modelBuffer.put(application.assets.open("DRModel3.tflite").readBytes())
            modelBuffer.rewind()

            // Create interpreter
            val interpreter = Interpreter(modelBuffer, options)

            // Prepare output buffer (adjust the size according to your model output)
            val outputBuffer = ByteBuffer.allocateDirect(4 * 5) // Assuming 5 classes, float32 (4 bytes each)
            outputBuffer.order(ByteOrder.nativeOrder())

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            // Process output
            outputBuffer.rewind()
            val outputs = FloatArray(5) // Adjust size based on your model output
            for (i in outputs.indices) {
                outputs[i] = outputBuffer.float
            }

            // Find max index
            val maxIdx = outputs.indices.maxByOrNull { outputs[it] } ?: -1

            // Get label
            val labels = application.assets.open("DRLabels.txt").bufferedReader().readLines()
            val result = if (maxIdx >= 0) labels[maxIdx] else "Unknown"

            // Close interpreter
            interpreter.close()

            return Pair(result, maxIdx)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair("Error: ${e.message}", -1)
        }
    }

    private fun processMEModel(bitmap: Bitmap): Pair<String, Int> {
        try {
            // Create image processor
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(224, 224, ResizeMethod.BILINEAR))
                .add(NormalizeOp(0.0f, 1.0f / 255.0f))
                .build()

            // Load and process image
            val tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            val processedImage = imageProcessor.process(tensorImage)

            // Create input tensor
            val inputBuffer = processedImage.buffer

            // Create interpreter options
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(false) // Disable Neural Network API for better compatibility
//                addDelegate(TfLiteDelegate())

            }

            // Load model file directly
            val modelBuffer = ByteBuffer.allocateDirect(application.assets.open("MEModel1.tflite").readBytes().size)
            modelBuffer.put(application.assets.open("MEModel1.tflite").readBytes())
            modelBuffer.rewind()

            // Create interpreter
            val interpreter = Interpreter(modelBuffer, options)

            // Prepare output buffer (adjust the size according to your model output)
            val outputBuffer = ByteBuffer.allocateDirect(4 * 2) // Assuming 5 classes, float32 (4 bytes each)
            outputBuffer.order(ByteOrder.nativeOrder())

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            // Process output
            outputBuffer.rewind()
            val outputs = FloatArray(2) // Adjust size based on your model output
            for (i in outputs.indices) {
                outputs[i] = outputBuffer.float
            }

            // Find max index
            val maxIdx = outputs.indices.maxByOrNull { outputs[it] } ?: -1

            // Get label
            val labels = application.assets.open("MELabels.txt").bufferedReader().readLines()
            val result = if (maxIdx >= 0) labels[maxIdx] else "Unknown"

            // Close interpreter
            interpreter.close()

            return Pair(result, maxIdx)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair("Error: ${e.message}", -1)
        }
    }

    private fun requestPermissionsAndOpenPicker() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        // Only request READ_MEDIA_IMAGES if needed for custom gallery picker (not needed for Photo Picker)
        if (permissions.isEmpty()) {
            openImagePicker()
        } else {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }



    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            openImagePicker()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun openImagePicker() {
        val options = arrayOf("Take Photo", "Select from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Choose Image Source")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Take Photo
                        val photoFile: File? = createImageFile()
                        if (photoFile != null) {
                            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
                            photoUri = uri // Set photoUri for later use
                            takePhoto.launch(uri)
                        } else {
                            Toast.makeText(this, "Failed to create image file", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> { // Select from Gallery
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }




//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//
//        if (requestCode == 100 && resultCode == RESULT_OK) {
//            when {
//                data?.data != null -> {
//                    val imageUri = data.data
//                    bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, imageUri!!)
//                    imageView.setImageBitmap(bitmap)
//                    placeholderText.visibility = View.GONE
//                }
//                photoUri != null -> {
//                    bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, photoUri!!)
//                    imageView.setImageBitmap(bitmap)
//                    placeholderText.visibility = View.GONE
//                }
//            }
//        }
////    companion object {
////        // Define the pic id
////        private const val pic_id = 123
////    }
//    }
}