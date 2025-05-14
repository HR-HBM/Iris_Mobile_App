package com.example.iris

import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.Call

data class PredictionResponse(
    val retinopathy: String,
    val edema: String,
    val retinopathy_probs: List<Float>,
    val edema_probs: List<Float>
)

interface ApiService {
    @Multipart
    @POST("/predict")
    fun predict(@Part file: MultipartBody.Part): Call<PredictionResponse>
}