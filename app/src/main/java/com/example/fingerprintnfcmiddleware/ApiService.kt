package com.example.fingerprintnfcmiddleware

import retrofit2.Call
import retrofit2.http.POST
import retrofit2.http.Body

data class ApiResponse(
    val success: Boolean,
    val msg: String
)

interface ApiService {
    @POST("read-rfid-card")
    fun sendNfcData(@Body data: NfcData): Call<ApiResponse>

    @POST("read-fingerprint")
    fun sendFpData(@Body data: FpData): Call<ApiResponse>
}

data class NfcData(
    val tagId: String,
    val deviceId: String,
)

data class FpData (
    val template: String,
    val deviceId: String,
)