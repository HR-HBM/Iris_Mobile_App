package com.example.iris

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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
    DRDiagnosis(severity = "ICDR level 0", symptoms = "No diabetic retinopathy - No visible abnormalities in the retina."),
    DRDiagnosis(severity = "ICDR level 1", symptoms = "Mild NPDR - Few microaneurysms visible in the retina."),
    DRDiagnosis(severity = "ICDR level 2", symptoms = "Moderate NPDR - Increased microaneurysms, hemorrhages, and exudates; possible venous beading."),
    DRDiagnosis(severity = "ICDR level 3", symptoms = "Severe NPDR - Extensive hemorrhages, microaneurysms, and venous beading in multiple quadrants."),
    DRDiagnosis(severity = "ICDR level 4", symptoms = "PDR - Neovascularization and/or vitreous hemorrhage; severe retinal damage.")
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
    lateinit var MEPrediction: TextView
    lateinit var disclaimerText: TextView
    lateinit var drDiagnosis: TextView
    lateinit var meDiagnosis: TextView
    lateinit var notes: TextView
    lateinit var fileNameText: TextView
    lateinit var loadingIndicator: ProgressBar
    lateinit var mainScrollView: ScrollView
    lateinit var notesCard: CardView

    private lateinit var currentPhotoPath: String
    private var photoURI: Uri? = null
    private var currentBitmap: Bitmap? = null

    private fun isNetworkConnected(): Boolean {
        //1
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        //2
        val activeNetwork = connectivityManager.activeNetwork
        //3
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        //4
        return networkCapabilities != null &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    // Retrofit setup with increased timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1)) // Force HTTP/1.1 to avoid HTTP/2 issues
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://retina-api-production.up.railway.app")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#206493") // Set status bar color
        setContentView(R.layout.activity_main)

        // Initialize UI components
        initializeUIComponents()

        // Set dynamic footer text
        setupFooterText()

        // Open the image picker
        selectBtn.setOnClickListener {
            resetPredictionTexts()
            openImagePicker()
        }

        // Run prediction via API
        predictBtn.setOnClickListener {
            if (currentBitmap == null) {
                Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isNetworkConnected()) {
                Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Start image upload in background
            ImagePredictionTask().execute(currentBitmap)
        }
    }

    private fun initializeUIComponents() {
        selectBtn = findViewById(R.id.selectBtn)
        predictBtn = findViewById(R.id.predictBtn)
        DRPrediction = findViewById(R.id.dRPrediction)
        imageView = findViewById(R.id.imageView)
        placeholderText = findViewById(R.id.placeholderText)
        MEPrediction = findViewById(R.id.mEPrediction)
        disclaimerText = findViewById(R.id.disclaimerText)
        drDiagnosis = findViewById(R.id.drDiagnosis)
        meDiagnosis = findViewById(R.id.meDiagnosis)
        fileNameText = findViewById(R.id.fileNameText)
        loadingIndicator = findViewById((R.id.loadingIndicator))
        notes = findViewById(R.id.notes)
        mainScrollView = findViewById(R.id.mainScrollView)
        notesCard = findViewById(R.id.notesCard)

        Log.d("ButtonSetup", "Predict button initialized: ${predictBtn != null}")
    }

    private fun setupFooterText() {
        val appName = getString(R.string.app_name)
        val footerTextView = findViewById<TextView>(R.id.footerText)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val footerText = getString(R.string.footer_text, appName, currentYear)
        footerTextView.text = footerText
    }

    private fun resetPredictionTexts() {
        DRPrediction.text = "Diabetic Retinopathy: "
        MEPrediction.text = "Diabetic Macula Edema: "
        drDiagnosis.text = "•Diagnosis: "
        meDiagnosis.text = "•Diagnosis: "
        notes.visibility = View.GONE
        notesCard.visibility = View.GONE
        disclaimerText.visibility = View.INVISIBLE
    }

    private fun openImagePicker() {
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

            try {
                // Clean up previous bitmap to free memory
                if (currentBitmap != null && !currentBitmap!!.isRecycled) {
                    currentBitmap!!.recycle()
                    currentBitmap = null
                }

                // Case 1: Image from gallery
                if (data?.data != null) {
                    val imageUri = data.data
                    currentBitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, imageUri)
                    imageView.setImageBitmap(currentBitmap)
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
                        currentBitmap = MediaStore.Images.Media.getBitmap(contentResolver, photoURI)
                        imageView.setImageBitmap(currentBitmap)
                        placeholderText.visibility = View.GONE
                        // Extract file name from currentPhotoPath
                        fileName = File(currentPhotoPath).name
                    } catch (e: Exception) {
                        Log.e("ImageError", "Error loading camera image", e)
                        Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                // Case 3: Fallback for thumbnail from camera (just in case)
                else if (data?.extras != null) {
                    val cameraBitmap = data.extras?.get("data") as? Bitmap
                    cameraBitmap?.let {
                        currentBitmap = it
                        imageView.setImageBitmap(currentBitmap)
                        placeholderText.visibility = View.GONE
                        fileName = "Camera_Thumbnail_${System.currentTimeMillis()}.jpg"
                    }
                }

                // Display the file name
                fileNameText.text = "File Name: $fileName"
                fileNameText.visibility = View.VISIBLE
            } catch (e: OutOfMemoryError) {
                Log.e("MemoryError", "Out of memory while loading image", e)
                Toast.makeText(this, "Image too large to load. Please try a different image.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("ImageError", "Error in image selection", e)
                Toast.makeText(this, "Error selecting image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Background task for image prediction to avoid ANR
    private inner class ImagePredictionTask : AsyncTask<Bitmap, Void, ByteArray>() {
        override fun onPreExecute() {
            loadingIndicator.visibility = View.VISIBLE
        }

        override fun doInBackground(vararg params: Bitmap): ByteArray? {
            try {
                val bitmap = params[0]
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                return stream.toByteArray()
            } catch (e: Exception) {
                Log.e("AsyncError", "Error processing image", e)
                return null
            }
        }

        override fun onPostExecute(byteArray: ByteArray?) {
            if (byteArray == null) {
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Error preparing image data", Toast.LENGTH_LONG).show()
                return
            }

            Log.d("PredictFlow", "Image prepared, sending to API")

            try {
                // Create multipart form data
                val requestFile = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", "image.jpg", requestFile)

                // Call API
                apiService.predict(body).enqueue(object : Callback<PredictionResponse> {
                    override fun onResponse(call: Call<PredictionResponse>, response: Response<PredictionResponse>) {
                        runOnUiThread {
                            try {
                                loadingIndicator.visibility = View.GONE

                                if (response.isSuccessful) {
                                    val prediction = response.body()
                                    if (prediction != null) {
                                        updateUIWithPrediction(prediction)
                                    } else {
                                        Toast.makeText(this@MainActivity, "Empty response from server", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    Toast.makeText(this@MainActivity, "Error: ${response.message() ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                    Log.e("APIError", "Response unsuccessful: ${response.code()} - ${response.message()}")
                                }
                            } catch (e: Exception) {
                                Log.e("ResponseError", "Error processing response", e)
                                Toast.makeText(this@MainActivity, "Error processing response: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    override fun onFailure(call: Call<PredictionResponse>, t: Throwable) {
                        runOnUiThread {
                            loadingIndicator.visibility = View.GONE
                            Log.e("PredictionError", "API call failed", t)
                            Toast.makeText(this@MainActivity, "Network error: ${t.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                Log.e("PredictionError", "Error preparing request", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUIWithPrediction(prediction: PredictionResponse) {
        try {
            // Update UI with predictions
            DRPrediction.text = "Diabetic Retinopathy: ${prediction.retinopathy}"
            MEPrediction.text = "Diabetic Macula Edema: ${prediction.edema}"

            // Update diagnosis details with null safety
            val drDiagnosisDetail = drDiagnosisList.find { it.severity == prediction.retinopathy }?.symptoms ?: "Unknown severity level"
            drDiagnosis.text = "Diagnosis: $drDiagnosisDetail"
            DRPrediction.visibility = View.VISIBLE
            drDiagnosis.visibility = View.VISIBLE


            val meDiagnosisDetail = meDiagnosisList.find { it.status == prediction.edema }?.features ?: "Unknown status"
            meDiagnosis.text = "Diagnosis: $meDiagnosisDetail"
            MEPrediction.visibility = View.VISIBLE
            meDiagnosis.visibility = View.VISIBLE


            if (prediction.retinopathy == "ICDR level 0") {
                notes.visibility = View.GONE
                notesCard.visibility = View.GONE
            } else if (prediction.retinopathy == "ICDR level 1" || prediction.retinopathy == "ICDR level 2" || prediction.retinopathy == "ICDR level 3") {
                notes.text = "• NPDR: Non-Proliferative Diabetic Retinopathy"
                notes.visibility = View.VISIBLE
                notesCard.visibility = View.VISIBLE
            } else if (prediction.retinopathy == "ICDR level 4") {
                notes.text = "• PDR: Proliferative Diabetic Retinopathy"
                notes.visibility = View.VISIBLE
                notesCard.visibility = View.VISIBLE
            }



            disclaimerText.visibility = View.VISIBLE

            mainScrollView.post {
                mainScrollView.smoothScrollTo(0, drDiagnosis.top)
            }


        } catch (e: Exception) {
            Log.e("UpdateUIError", "Error updating UI with prediction", e)
            Toast.makeText(this, "Error displaying results: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up bitmap to prevent memory leaks
        if (currentBitmap != null && !currentBitmap!!.isRecycled) {
            currentBitmap!!.recycle()
            currentBitmap = null
        }
    }
}