package com.music.bitchord.data.innertube

import com.music.bitchord.data.YtMusicRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicroGRequestTest {
    @Test fun authHeadersContainOnlyTheBearerAndRequestTime() {
        val request = HttpRequestBuilder()
        request.bearerHeaders("test-token")
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertTrue(request.headers["X-Goog-Request-Time"]!!.toLong() > 0)
        assertNull(request.headers["Cookie"])
        assertFailsWith<IllegalArgumentException> { request.bearerHeaders("bad\r\nheader") }
    }

    @Test fun trackingCredentialsOnlyGoToKnownYouTubeStatsEndpoints() {
        assertTrue(isYouTubeStatsUrl(Url("https://s.youtube.com/api/stats/watchtime?cpn=1")))
        listOf(
            "https://s.youtube.com.evil.example/api/stats/playback",
            "https://s.youtube.com@other.example/api/stats/playback",
            "http://s.youtube.com/api/stats/playback",
            "https://s.youtube.com:444/api/stats/playback",
            "https://music.youtube.com/redirect",
            "https://example.googlevideo.com/api/stats/playback",
        ).forEach { assertFalse(isYouTubeStatsUrl(Url(it)), it) }
    }

    @Test fun rejectedTokenIsReplacedOnlyOnceAndOnlyFor401() {
        val server = MockWebServer()
        val client = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            val rejected = mutableListOf<String?>()
            val provider = YouTubeTokenProvider {
                rejected.add(it)
                if (it == null) "old" else "new"
            }
            fun request() = runBlocking {
                requestWithToken(provider) { client.get(server.url("/test").toString()).status.value }
            }

            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(200))
            assertEquals(200, request())
            assertEquals(listOf(null, "old"), rejected)

            rejected.clear()
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
            assertFailsWith<ClientRequestException> { request() }
            assertEquals(listOf(null, "old"), rejected)

            rejected.clear()
            server.enqueue(MockResponse().setResponseCode(403))
            assertFailsWith<ClientRequestException> { request() }
            assertEquals(listOf<String?>(null), rejected)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun rejectedCandidateKeepsTheActiveAccount() = runBlocking {
        Innertube.cookie = "SAPISID=existing"
        try {
            val provider = YouTubeTokenProvider { throw IllegalStateException("Grant unavailable") }
            assertTrue(YtMusicRepository.oauthAccount(provider).isFailure)
            assertEquals("SAPISID=existing", Innertube.cookie)
        } finally {
            Innertube.cookie = null
        }
    }

    @Test fun switchingCredentialsDropsTheOtherMechanism() {
        try {
            Innertube.cookie = "SAPISID=legacy"
            Innertube.useOAuth("microg:account", YouTubeTokenProvider { error("unexpected token request") })
            assertNull(Innertube.cookie)
            assertEquals("microg:account", Innertube.sessionKey)
            assertTrue(Innertube.isSignedIn)
            Innertube.cookie = "SAPISID=next"
            assertEquals("SAPISID=next", Innertube.sessionKey)
        } finally {
            Innertube.cookie = null
        }
        assertFalse(Innertube.isSignedIn)
    }
}
