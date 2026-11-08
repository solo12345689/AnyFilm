package com.example.data.network

import com.example.data.model.*
import com.example.data.preferences.PreferencesManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface AniFamApi {

    @GET("api/v1/app/home")
    suspend fun getHome(): AniFamHomeResponse

    @GET("api/v1/app/genres")
    suspend fun getGenres(): List<AniFamGenreItem>

    @GET("api/v1/app/browse")
    suspend fun browse(
        @Query("genre") genre: String? = null,
        @Query("type") type: String? = null,
        @Query("sort") sort: String? = "popular",
        @Query("page") page: Int = 1
    ): AniFamBrowseResponse

    @GET("api/v1/app/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1
    ): AniFamBrowseResponse

    @GET("api/v1/app/search/suggest")
    suspend fun searchSuggest(
        @Query("q") query: String
    ): List<AniFamAnimeItem>

    @GET("api/v1/app/anime/{id}")
    suspend fun getAnimeDetail(
        @Path("id") id: Int
    ): AniFamAnimeDetail

    @GET("api/v1/app/anime/{id}/episodes")
    suspend fun getEpisodes(
        @Path("id") id: Int
    ): AniFamEpisodesResponse

    @POST("api/v1/app/episodes/{id}/play")
    suspend fun playEpisode(
        @Path("id") id: Int,
        @Body body: Map<String, String>
    ): AniFamPlayResponse

    @POST("api/v1/app/episodes/{id}/resolve")
    suspend fun resolveSource(
        @Path("id") id: Int,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): AniFamPlaySource

    @POST("api/v1/app/auth/register")
    suspend fun register(
        @Body request: AniFamRegisterRequest
    ): AniFamAuthResponse

    @POST("api/v1/app/auth/login")
    suspend fun login(
        @Body request: AniFamLoginRequest
    ): AniFamAuthResponse

    @GET("api/v1/app/me")
    suspend fun getProfile(): AniFamUser

    @PATCH("api/v1/app/me/profile")
    suspend fun updateProfile(
        @Body request: AniFamProfileUpdateRequest
    ): AniFamUser

    @Multipart
    @POST("api/v1/app/me/avatar/upload")
    suspend fun uploadAvatar(
        @Part avatar: okhttp3.MultipartBody.Part
    ): AniFamUser

    @GET("api/v1/app/me/watchlist")
    suspend fun getWatchlist(
        @Query("page") page: Int = 1
    ): AniFamWatchlistResponse

    @GET("api/v1/app/me/watchlist/status/{id}")
    suspend fun getWatchlistStatus(
        @Path("id") animeId: Int
    ): AniFamWatchlistStatusResponse

    @PUT("api/v1/app/me/watchlist/{id}")
    suspend fun addToWatchlist(
        @Path("id") animeId: Int,
        @Body body: Map<String, String> = mapOf("status" to "watching")
    ): AniFamWatchlistStatusResponse

    @DELETE("api/v1/app/me/watchlist/{id}")
    suspend fun removeFromWatchlist(
        @Path("id") animeId: Int
    ): retrofit2.Response<Unit>
}

object AniFamRetrofitClient {
    private const val BASE_URL = "https://anifam.net/"
    private var instance: AniFamApi? = null
    private var currentToken: String? = null

    fun getApi(prefs: PreferencesManager? = null): AniFamApi {
        val token = prefs?.anifamAuthToken
        if (instance != null && currentToken == token) {
            return instance!!
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("accept", "application/json")
                    .header("x-app-version", "20")
                    .header("x-app-platform", "android")
                    .header("user-agent", "okhttp/4.12.0")

                if (!token.isNullOrEmpty()) {
                    builder.header("authorization", "Bearer $token")
                }

                chain.proceed(builder.build())
            })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

        currentToken = token
        val newInstance = retrofit.create(AniFamApi::class.java)
        instance = newInstance
        return newInstance
    }
}

