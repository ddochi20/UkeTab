package com.uketab.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 서버(server/omr_server.py)와 통신.
 *  - /omr   : 악보 사진 → MusicXML
 *  - /audio : mp3/wav 등 음원 → 멜로디 추출 → MusicXML
 */
class OmrClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    fun recognize(image: File): String = upload("/omr", "image", image, "image/jpeg")

    fun transcribe(audio: File): String = upload("/audio", "audio", audio, "application/octet-stream")

    private fun upload(path: String, field: String, file: File, mime: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(field, file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + path).post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) throw IllegalStateException("서버 오류 ${res.code}: ${text.take(200)}")
            if (!text.contains("<score-partwise") && !text.contains("<score-timewise"))
                throw IllegalStateException("서버가 MusicXML을 돌려주지 않았습니다.")
            return text
        }
    }
}
