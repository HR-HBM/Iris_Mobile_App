package com.example.iris

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.iris.ml.DRModel3
import com.example.iris.ml.MEModel1
import com.example.iris.R
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


// Data class for DR diagnosis details
data class DRDiagnosis(
    val severity: String,
    val symptoms: String
)

// Data class for ME diagnosis details
data class MEDiagnosis(
    val status: String, // "Positive" or "Negative"
    val features: String
)

// Define DR diagnosis list
val drDiagnosisList = listOf(
    DRDiagnosis(severity = "ICDR level 0", symptoms = "No diabetic retinopathy: No visible abnormalities in the retina."),
    DRDiagnosis(severity = "ICDR level 1", symptoms = "Mild NPDR: Few microaneurysms visible in the retina."),
    DRDiagnosis(severity = "ICDR level 2", symptoms = "Moderate NPDR: Increased microaneurysms, hemorrhages, and exudates; possible venous beading."),
    DRDiagnosis(severity = "ICDR level 3", symptoms = "Severe NPDR: Extensive hemorrhages, microaneurysms, and venous beading in multiple quadrants."),
    DRDiagnosis(severity = "ICDR level 4", symptoms = "PDR: Neovascularization and/or vitreous hemorrhage; severe retinal damage.")
)

// Define ME diagnosis list
val meDiagnosisList = listOf(
    MEDiagnosis(status = "Negative", features = "No hard exudates or swelling in the macula region."),
    MEDiagnosis(status = "Positive", features = "Hard exudates and/or macular edema present near the macula.")
)





class MainActivity : AppCompatActivity() {

    lateinit var selectBtn: Button
    lateinit var predictBtn: Button
    lateinit var DRPrediction: TextView
    lateinit var imageView: ImageView
    lateinit var placeholderText: TextView
    lateinit var bitmap: Bitmap
    lateinit var MEPrediction: TextView
    lateinit var disclaimerText: TextView
    lateinit var drDiagnosis: TextView
    lateinit var meDiagnosis: TextView
    lateinit var fileNameText: TextView


    private lateinit var currentPhotoPath: String
    private var photoURI: Uri? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#40a6a6") // Set status bar color
        setContentView(R.layout.activity_main)

        selectBtn = findViewById(R.id.selectBtn)
        predictBtn = findViewById(R.id.predictBtn)
        DRPrediction  = findViewById(R.id.dRPrediction)
        imageView = findViewById(R.id.imageView)
        placeholderText = findViewById(R.id.placeholderText)
        MEPrediction  = findViewById(R.id.mEPrediction)
        disclaimerText = findViewById(R.id.disclaimerText)
        drDiagnosis = findViewById(R.id.drDiagnosis)
        meDiagnosis = findViewById(R.id.meDiagnosis)
        fileNameText = findViewById(R.id.fileNameText)





        val drLabels = application.assets.open("DRLabels.txt").bufferedReader().readLines()
        val meLabels = application.assets.open("MELabels.txt").bufferedReader().readLines()

        // Set dynamic footer text
        val appName = getString(R.string.app_name)
        val footerTextView = findViewById<TextView>(R.id.footerText)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val footerText = getString(R.string.footer_text, appName, currentYear)
        footerTextView.text = footerText



        // Set up image processor with resizing and normalization
        val drImageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(512, 512, ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 1.0f / 255.0f))  // Normalize image between 0 and 1
            .build()

        val meImageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 1.0f / 255.0f))  // Normalize image between 0 and 1
            .build()

        // Open the image picker
        selectBtn.setOnClickListener {
            // Intent to open gallery (phone storage)
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*" // To select any image
            }

            // Intent to open the camera (to take a picture)
            val takePhotoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            // Create a file to save the full-resolution photo
            var photoFile: File? = null
            try {
                photoFile = createImageFile()
            } catch (ex: IOException) {
                // Error occurred while creating the File
                ex.printStackTrace()
            }

            // Continue only if the File was successfully created
            photoFile?.also {
                photoURI = FileProvider.getUriForFile(
                    this,
                    "com.example.iris.fileprovider",  // Must match provider authority in manifest
                    it
                )
                takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            }

            // Create a chooser intent with both intents (gallery and camera)
            val chooserIntent = Intent.createChooser(pickIntent, "Select or Take a New Picture")

            // Add the camera intent as an additional option in the chooser
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePhotoIntent))

            // Start the activity to allow the user to select an image or take a photo
            startActivityForResult(chooserIntent, 100)
        }

        // Run prediction on selected image
        predictBtn.setOnClickListener {
            try {
                // Load the bitmap into TensorImage
                val tensorImage = TensorImage(DataType.FLOAT32)
                tensorImage.load(bitmap)

                // Process the image (resize and normalize)
                val drProcessedImage = drImageProcessor.process(tensorImage)
                // Prepare input tensor for model
                val drInputFeature =
                    TensorBuffer.createFixedSize(intArrayOf(1, 512, 512, 3), DataType.FLOAT32)
                drInputFeature.loadBuffer(drProcessedImage.buffer)

                // Process the image (resize and normalize)
                val meProcessedImage = meImageProcessor.process(tensorImage)
                // Prepare input tensor for model
                val meInputFeature =
                    TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32)
                meInputFeature.loadBuffer(meProcessedImage.buffer)

                // Load the model and run inference
                val drModel = DRModel3.newInstance(this)
                val outputs1 = drModel.process(drInputFeature)
                // Get the output and find the class with the highest probability
                val drOutputFeature = outputs1.outputFeature0AsTensorBuffer.floatArray
                val maxIdx1 = drOutputFeature.indices.maxByOrNull { drOutputFeature[it] } ?: -1

                val meModel = MEModel1.newInstance(this)
                val outputs2 = meModel.process(meInputFeature)
                val meOutputFeature = outputs2.outputFeature0AsTensorBuffer.floatArray
                val maxIdx2 = meOutputFeature.indices.maxByOrNull { meOutputFeature[it] } ?: -1

                // Set prediction text (class label)
                DRPrediction.text = "Diabetic Retinopathy: ${if (maxIdx1 >= 0) drLabels[maxIdx1] else "Unknown"}"
                MEPrediction.text = "Diabetic Macula Edema: ${if (maxIdx2 >= 0) meLabels[maxIdx2] else "Unknown"}"
                // Update diagnosis details based on predictions
                // For DR, maxIdx1 directly maps to severity (0-4)
                val drDiagnosisDetail = drDiagnosisList.find { it.severity == drLabels[maxIdx1] }?.symptoms ?: "Unknown severity level"
                drDiagnosis.text = "•Diagnosis: $drDiagnosisDetail"

                // For ME, maxIdx2 maps to status (0 for Negative, 1 for Positive based on MELabels.txt)
//                val meStatus = if (maxIdx2 >= 0) meLabels[maxIdx2] else "Unknown"
                val meDiagnosisDetail = meDiagnosisList.find { it.status == meLabels[maxIdx2] }?.features ?: "Unknown status"
                meDiagnosis.text = "•Diagnosis: $meDiagnosisDetail"


                disclaimerText.visibility = View.VISIBLE

                // Close the model to release resources
                drModel.close()
                meModel.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == RESULT_OK) {
            var fileName = "Unknown"
            // Case 1: Image from gallery
            if (data?.data != null) {
                val imageUri = data.data
                bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, imageUri)
                imageView.setImageBitmap(bitmap)
                placeholderText.visibility = View.GONE
                // Extract file name from Uri
                val cursor = contentResolver.query(imageUri!!, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = it.getString(nameIndex)
                        }
                    }
                }
            }
            // Case 2: Full resolution image from camera
            else if (photoURI != null) {
                try {
                    bitmap = MediaStore.Images.Media.getBitmap(contentResolver, photoURI)
                    imageView.setImageBitmap(bitmap)
                    placeholderText.visibility = View.GONE
                    // Extract file name from currentPhotoPath
                    fileName = File(currentPhotoPath).name
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Case 3: Fallback for thumbnail from camera (just in case)
            else if (data?.extras != null) {
                val cameraBitmap = data.extras?.get("data") as? Bitmap
                cameraBitmap?.let {
                    bitmap = it
                    imageView.setImageBitmap(bitmap)
                    placeholderText.visibility = View.GONE
                    fileName = "Camera_Thumbnail_${System.currentTimeMillis()}.jpg"
                }
            }
            // Display the file name
            fileNameText.text = "File Name: $fileName"
            fileNameText.visibility = View.VISIBLE
        }
    }
}

