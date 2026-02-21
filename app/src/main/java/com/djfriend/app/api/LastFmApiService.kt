package com.djfriend.app.api

import com.djfriend.app.BuildConfig
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Retrofit Interface

interface LastFmApiService {

    @GET("?method=track.getInfo&format=json&autocorrect=1")
    suspend fun getTrackInfo(
        @Query("artist") artist: String,
        @Query("track") track: String,
        @Query("api_key") apiKey: String
    ): TrackInfoResponse

    @GET("?method=track.getSimilar&format=json")
    suspend fun getSimilarTracks(
        @Query("artist") artist: String,
        @Query("track") track: String,
        @Query("limit") limit: Int,
        @Query("api_key") apiKey: String
    ): SimilarTracksResponse

    @GET("?method=artist.getSimilar&format=json")
    suspend fun getSimilarArtists(
        @Query("artist") artist: String,
        @Query("limit") limit: Int,
        @Query("api_key") apiKey: String
    ): SimilarArtistsResponse

    @GET("?method=artist.getTopTracks&format=json")
    suspend fun getArtistTopTracks(
        @Query("artist") artist: String,
        @Query("limit") limit: Int,
        @Query("api_key") apiKey: String
    ): ArtistTopTracksResponse
}

// Retrofit Singleton

object RetrofitClient {
    private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

    val lastFmApi: LastFmApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LastFmApiService::class.java)
    }

    // Convenience so callers don't import BuildConfig themselves
    val apiKey: String get() = BuildConfig.LASTFM_API_KEY
}

// Response Models

data class TrackInfoResponse(val track: TrackDetail?)

data class TrackDetail(
    val name: String,
    val artist: ArtistRef,
    val listeners: String?,
    val duration: String?
)

data class SimilarTracksResponse(
    @SerializedName("similartracks") val similarTracks: SimilarTracksList
)
data class SimilarTracksList(@SerializedName("track") val tracks: List<TrackRef>)

data class SimilarArtistsResponse(
    @SerializedName("similarartists") val similarArtists: SimilarArtistsList
)
data class SimilarArtistsList(@SerializedName("artist") val artists: List<ArtistRef>)

data class ArtistTopTracksResponse(
    @SerializedName("toptracks") val topTracks: TopTracksList
)
data class TopTracksList(@SerializedName("track") val tracks: List<TrackRef>)

data class TrackRef(val name: String, val artist: ArtistRef)

data class ArtistRef(val name: String)
