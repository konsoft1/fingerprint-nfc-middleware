package com.example.fingerprintnfcmiddleware

import retrofit2.Call
import retrofit2.http.POST
import retrofit2.http.Body

interface ApiService {
    @POST("nfc/data")
    fun sendNfcData(@Body data: NfcData): Call<Void>

    @POST("fp/data")
    fun sendFpData(@Body data: FpData): Call<Void>
}

data class NfcData(
    val tagId: String
)

data class FpData (
    val template: String
)