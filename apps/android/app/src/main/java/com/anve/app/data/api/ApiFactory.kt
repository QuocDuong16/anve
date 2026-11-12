package com.anve.app.data.api

import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the OkHttp client and Retrofit instance. Callers pass the
 * interceptor chain they need: the session-aware client gets
 * SessionInterceptor (bearer header + one refresh-retry on 401, ADR 0002),
 * the token-free client gets a pass-through.
 */
object ApiFactory {

    fun okHttp(interceptor: Interceptor): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(interceptor)
        .build()

    /** Reuse the same session refresher, but admit private writes only once.
     * One-shot bodies also stop HTTP 503/Retry-After follow-ups, which are
     * separate from connection recovery in OkHttp. A 401 refresh remains
     * explicit in SessionInterceptor, after the server refused admission. */
    fun privateMutations(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.method in setOf("GET", "HEAD")) return@addInterceptor chain.proceed(request)
            val body = request.body ?: byteArrayOf().toRequestBody()
            val admitted = object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }
            chain.proceed(request.newBuilder().method(request.method, admitted).build())
        }.build()

    fun retrofit(baseUrl: String, client: OkHttpClient, moshi: Moshi = apiMoshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    fun api(retrofit: Retrofit): AnveApi = retrofit.create(AnveApi::class.java)

    /** An interceptor that changes nothing — for token-free clients. */
    fun passThrough(): Interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
}
