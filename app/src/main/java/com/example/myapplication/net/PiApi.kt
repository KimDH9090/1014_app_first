package com.example.myapplication.net

import retrofit2.http.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

data class CmdReq(val command: String)
data class ReadyState(
    val state: String,
    val event: String?,
    val eventId: Long,
    val ts: String?,
    val detail: String?
)

interface PiApi {
    @POST("api/command") suspend fun send(@Body body: CmdReq): Map<String, Any?>
    @GET("api/ready_state") suspend fun ready(): ReadyState

    companion object {
        fun create(baseUrl: String): PiApi =
            Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PiApi::class.java)
    }
}
