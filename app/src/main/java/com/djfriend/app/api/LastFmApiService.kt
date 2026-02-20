package com.djfriend.app.api

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ──────────────────────────────────────────────────────────────────────────────
// Retrofit Interface
// ──────────────────────────────────────────────────────────────────────────────

interface LastFmApiService {

    @GET("?method=track.getInfo&format=json")
    suspend fun getTrackInfo(
        @Query("artist") artist: String,
        @Query("track") track: String,
        @Query("api_key") apiKey: String = BuildConfig.LASTFM_API_KEY,
        @Query("autocorrect") autocorrect: Int = 1
    ): TrackInfoResponse

    @GET("?method=track.getSimilar&format=json")
    suspend fun getSimilarTracks(
        @Query("artist") artist: String,
        @Query("track") track: String,
        @Query("limit") limit: Int = 5,
        @Query("api_key") apiKey: String = BuildConfig.LASTFM_API_KEY
    ): SimilarTracksResponse

    @GET("?method=artist.getSimilar&format=json")
    suspend fun getSimilarArtists(
        @Query("artist") artist: String,
        @Query("limit") limit: Int = 5,
        @Query("api_key") apiKey: String = BuildConfig.LASTFM_API_KEY
    ): SimilarArtistsResponse

    @GET("?method=artist.getTopTracks&format=json")
    suspend fun getArtistTopTracks(
        @Query("artist") artist: String,
        @Query("limit") limit: Int = 5,
        @Query("api_key") apiKey: String = BuildConfig.LASTFM_API_KEY
    ): ArtistTopTracksResponse
}

// ──────────────────────────────────────────────────────────────────────────────
// Retrofit Client Singleton
// ──────────────────────────────────────────────────────────────────────────────

object RetrofitClient {
    private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

    val lastFmApi: LastFmApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LastFmApiService::class.java)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Response Models
// ──────────────────────────────────────────────────────────────────────────────

// --- track.getInfo ---
data class TrackInfoResponse(val track: TrackDetail?)
data class TrackDetail(
    val name: String,
    val artist: ArtistRef,
    val listeners: String?,
    val duration: String?
)

// --- track.getSimilar ---
data class SimilarTracksResponse(@SerializedName("similartracks") val similarTracks: SimilarTracksList)
data class SimilarTracksList(@SerializedName("track") val tracks: List<TrackRef>)
data class TrackRef(
    val name: String,
    val artist: ArtistRef
)

// --- artist.getSimilar ---
data class SimilarArtistsResponse(@SerializedName("similarartists") val similarArtists: SimilarArtistsList)
data class SimilarArtistsList(@SerializedName("artist") val artists: List<ArtistRef>)
data class ArtistRef(val name: String)

// --- artist.getTopTracks ---
data class ArtistTopTracksResponse(@SerializedName("toptracks") val topTracks: TopTracksList)
data class TopTracksList(@SerializedName("track") val tracks: List<TrackRef>)
