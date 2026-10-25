package com.anve.app.data.api

import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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
