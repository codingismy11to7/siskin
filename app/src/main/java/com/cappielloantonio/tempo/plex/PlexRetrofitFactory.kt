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
 * distinction rather than a runtime hope -- and is what lets each one carry its
 * own token, since a shared server rejects the account token.
 */
object PlexRetrofitFactory {

    private const val PLEX_TV_BASE_URL = "https://plex.tv/api/v2/"

    /** Syntactically valid but unreachable; used before a server is discovered. */
    private const val PLACEHOLDER_BASE_URL = "https://localhost/"

    fun plexTv(api: PlexApi): Retrofit = build(PLEX_TV_BASE_URL, api::plexTvHeaders)

    /**
     * Bakes [serverUri] into the returned instance's base URL, and uses
     * [serverToken] for its identity headers.
     *
     * Taken as parameters rather than read from [api] so a caller can build a
     * client for a server that is not the persisted one -- which sign-in needs,
     * because it probes and reads a candidate server's sections *before*
     * committing a PlexSession. The instance is still pinned to whatever it was
     * given: callers that outlive a server change must rebuild, as
     * PlexBrowseRepository.refreshClients does.
     */
    fun server(api: PlexApi, serverUri: String?, serverToken: String?): Retrofit =
        build(normalize(serverUri)) {
            PlexIdentity.headers(
                api.clientIdentifier,
                api.appVersion,
                PlexApi.serverTokenOrAccount(serverToken, api.accountToken),
                api.language
            )
        }

    private val gson = GsonBuilder().setLenient().create()

    /**
     * One connection pool and one dispatcher thread pool for the whole app.
     *
     * Every client below is derived from this with [OkHttpClient.newBuilder],
     * which shares both. Building a whole OkHttpClient per call instead is not
     * merely wasteful allocation: each one owns a private connection pool, so
     * nothing it opens is ever reused and every request pays a fresh TCP and
     * TLS handshake. The callers that matter here are constructed per use --
     * PlexScrobbler reports on every play *and* pause, BaseSessionCallback
     * builds one per heart tap, PlexMixRepository one per mix -- so on a head
     * unit that is several full handshakes per track.
     *
     * Deliberately carries no interceptors: the identity interceptor closes
     * over one PlexApi's header supplier, so it must stay per-client or a
     * server call would start sending plex.tv's token and vice versa.
     */
    private val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(1, TimeUnit.MINUTES)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun build(baseUrl: String, headers: () -> Map<String, String>): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(okHttp(headers))
            .build()

    private fun okHttp(headers: () -> Map<String, String>): OkHttpClient = sharedClient.newBuilder()
        .addInterceptor(identityInterceptor(headers))
        .addInterceptor(logging())
        .build()

    /** Attaches the X-Plex-* headers to every request, token included when present. */
    private fun identityInterceptor(headers: () -> Map<String, String>) = Interceptor { chain ->
        val builder = chain.request().newBuilder()
        headers().forEach { (name, value) -> builder.header(name, value) }
        chain.proceed(builder.build())
    }

    private fun logging() = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        // X-Plex-Token is a full account credential; HEADERS logging would
        // otherwise write it to logcat verbatim on every request.
        redactHeader("X-Plex-Token")
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
