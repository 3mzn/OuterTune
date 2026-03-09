package com.dd3boh.outertune.sync

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SpotifyTrack(
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum
)

@Serializable
data class SpotifyArtist(
    val name: String
)

@Serializable
data class SpotifyAlbum(
    val name: String,
    val images: List<SpotifyImage>
)

@Serializable
data class SpotifyImage(
    val url: String
)

@Serializable
data class SpotifyLikedSongsResponse(
    val items: List<SpotifyLikedSongItem>,
    val next: String?
)

@Serializable
data class SpotifyLikedSongItem(
    val track: SpotifyTrack
)

@Singleton
class SpotifyRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CLIENT_ID = "a1a7a55ade18420fa7cc22a9b8c60040"
        const val CLIENT_SECRET = "189bd3c607c24c358bec108433ad3751"
        const val REDIRECT_URI = "https://outertune.app/callback"
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    private var accessToken: String? = null

    suspend fun getAccessTokenFromCode(code: String): String? {
        try {
            val response = client.post("https://accounts.spotify.com/api/token") {
                header("Authorization", "Basic " + android.util.Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), android.util.Base64.NO_WRAP))
                setBody(io.ktor.client.request.forms.FormDataContent(io.ktor.http.Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", REDIRECT_URI)
                }))
            }
            if (response.status.value in 200..299) {
                val jsonText = response.bodyAsText()
                accessToken = Regex("\"access_token\":\"(.*?)\"").find(jsonText)?.groupValues?.get(1)
                return accessToken
            } else {
                Log.e("SpotifyRepository", "API token exchange failed: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            Log.e("SpotifyRepository", "Failed to fetch API token", e)
        }
        return null
    }

    suspend fun getLikedSongsApi(token: String, url: String = "https://api.spotify.com/v1/me/tracks?limit=50"): SpotifyLikedSongsResponse? {
        try {
            return client.get(url) {
                header("Authorization", "Bearer $token")
            }.body()
        } catch (e: Exception) {
            Log.e("SpotifyRepository", "Failed to fetch liked songs via API", e)
        }
        return null
    }

    suspend fun getAllLikedSongsApi(token: String, onProgress: (Int) -> Unit = {}): List<SpotifyTrack> {
        val allTracks = mutableListOf<SpotifyTrack>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        
        while (nextUrl != null) {
            val response = getLikedSongsApi(token, nextUrl) ?: break
            allTracks.addAll(response.items.map { it.track })
            onProgress(allTracks.size)
            nextUrl = response.next
        }
        
        return allTracks
    }
}
