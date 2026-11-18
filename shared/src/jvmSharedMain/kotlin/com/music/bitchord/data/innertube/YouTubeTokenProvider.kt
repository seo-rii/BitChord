package com.music.bitchord.data.innertube

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url

fun interface YouTubeTokenProvider {
    /** Return the current access token, replacing [rejectedToken] when YouTube returned 401. */
    suspend fun getToken(rejectedToken: String?): String
}

internal suspend fun <T> requestWithToken(
    provider: YouTubeTokenProvider?,
    send: suspend (String?) -> T,
): T {
    if (provider == null) return send(null)
    val token = provider.getToken(null)
    return try {
        send(token)
    } catch (failure: ClientRequestException) {
        if (failure.response.status != HttpStatusCode.Unauthorized) throw failure
        send(provider.getToken(token))
    }
}

internal fun HttpRequestBuilder.bearerHeaders(token: String) {
    require(token.isNotEmpty() && token.all { it.code in 0x21..0x7e }) { "Invalid YouTube access token" }
    header("Authorization", "Bearer $token")
    header("X-Goog-Request-Time", (System.currentTimeMillis() / 1000).toString())
}

private val statsHosts = setOf("s.youtube.com", "www.youtube.com", "music.youtube.com", "youtube.com")
private val statsPaths = setOf("/api/stats/playback", "/api/stats/watchtime", "/api/stats/atr")

internal fun isYouTubeStatsUrl(url: Url): Boolean =
    url.protocol == URLProtocol.HTTPS && url.port == 443 && url.host in statsHosts && url.encodedPath in statsPaths
