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
import java.util.Date
import java.util.Locale


class MainActivity : AppCompatActivity() {

    lateinit var selectBtn: Button
    lateinit var predictBtn: Button
    lateinit var DRPrediction: TextView
    lateinit var imageView: ImageView
    lateinit var placeholderText: TextView
    lateinit var bitmap: Bitmap
    lateinit var MEPrediction: TextView
    lateinit var disclaimerText: TextView

    private lateinit var currentPhotoPath: String
    private var photoURI: Uri? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectBtn = findViewById(R.id.selectBtn)
        predictBtn = findViewById(R.id.predictBtn)
        DRPrediction  = findViewById(R.id.dRPrediction)
        imageView = findViewById(R.id.imageView)
        placeholderText = findViewById(R.id.placeholderText)
        MEPrediction  = findViewById(R.id.mEPrediction)
        disclaimerText = findViewById(R.id.disclaimerText)


        val drLabels = application.assets.open("DRLabels.txt").bufferedReader().readLines()
        val meLabels = application.assets.open("MELabels.txt").bufferedReader().readLines()


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
                DRPrediction.text = "Prediction: ${if (maxIdx1 >= 0) drLabels[maxIdx1] else "Unknown"}"
                MEPrediction.text = "Prediction: ${if (maxIdx2 >= 0) meLabels[maxIdx2] else "Unknown"}"

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
            // Case 1: Image from gallery
            if (data?.data != null) {
                val imageUri = data.data
                bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, imageUri)
                imageView.setImageBitmap(bitmap)
                placeholderText.visibility = View.GONE
            }
            // Case 2: Full resolution image from camera
            else if (photoURI != null) {
                try {
                    bitmap = MediaStore.Images.Media.getBitmap(contentResolver, photoURI)
                    imageView.setImageBitmap(bitmap)
                    placeholderText.visibility = View.GONE
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
                }
            }
        }
    }
}

