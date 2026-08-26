package com.uketab.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 악보 사진을 OMR 서버(server/omr_server.py)로 보내 MusicXML 문자열을 받아온다.
 */
class OmrClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun recognize(image: File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", image.name, image.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/omr").post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) throw IllegalStateException("OMR 서버 오류 ${res.code}: ${text.take(200)}")
            if (!text.contains("<score-partwise") && !text.contains("<score-timewise"))
                throw IllegalStateException("서버가 MusicXML을 돌려주지 않았습니다.")
            return text
        }
    }
}
