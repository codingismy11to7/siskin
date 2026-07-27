package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.BuildConfig
import com.google.gson.GsonBuilder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the two Retrofit instances this layer needs.
 *
 * plex.tv is fixed and usable before sign-in; the media server's address is only
 * known after discovery and changes when the user switches servers. Keeping them
 * as separate instances makes "this call works signed out" a compile-time
 * distinction rather than a runtime hope.
 */
object PlexRetrofitFactory {

    private const val PLEX_TV_BASE_URL = "https://plex.tv/api/v2/"

    /** Syntactically valid but unreachable; used before a server is discovered. */
    private const val PLACEHOLDER_BASE_URL = "https://localhost/"

    fun plexTv(api: PlexApi): Retrofit = build(PLEX_TV_BASE_URL, api)

    fun server(api: PlexApi): Retrofit = build(normalize(api.serverUri), api)

    private fun build(baseUrl: String, api: PlexApi): Retrofit {
        val gson = GsonBuilder().setLenient().create()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(okHttp(api))
            .build()
    }

    private fun okHttp(api: PlexApi): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(1, TimeUnit.MINUTES)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(identityInterceptor(api))
        .addInterceptor(logging())
        .build()

    /** Attaches the X-Plex-* headers to every request, token included when present. */
    private fun identityInterceptor(api: PlexApi) = Interceptor { chain ->
        val builder = chain.request().newBuilder()
        api.headers().forEach { (name, value) -> builder.header(name, value) }
        chain.proceed(builder.build())
    }

    private fun logging() = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /**
     * Retrofit rejects a base URL without a parseable host, and requires a
     * trailing slash. Falling back to an unreachable placeholder means calls made
     * before discovery fail through the normal error path instead of throwing at
     * construction.
     */
    private fun normalize(serverUri: String?): String {
        val trimmed = serverUri?.trim().orEmpty()
        val parsed = trimmed.toHttpUrlOrNull()
        if (parsed == null || parsed.host.isEmpty()) return PLACEHOLDER_BASE_URL
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
