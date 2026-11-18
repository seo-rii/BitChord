package com.music.bitchord.auth

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroGConsentFlowTest {
    @Test
    fun returnedGrantCanBeVerifiedImmediately() = runBlocking {
        val verified = verifyMicroGConsent(
            acceptResult = { true },
            requestAfterConsent = { error("Unexpected second token request") },
            verifyAccount = { true },
        )

        assertTrue(verified)
    }

    @Test
    fun emptyCanceledResultCanStillLeadToAnApprovedAccount() = runBlocking {
        val calls = mutableListOf<String>()
        val verified = verifyMicroGConsent(
            acceptResult = { false },
            requestAfterConsent = { calls += "silent lookup" },
            verifyAccount = {
                calls += "verify"
                true
            },
        )

        assertTrue(verified)
        assertEquals(listOf("silent lookup", "verify"), calls)
    }

    @Test
    fun secondConsentRequestStopsTheAttempt() = runBlocking {
        val needsConsent = IOException("Consent still required")
        var lookups = 0
        val failure = runCatching {
            verifyMicroGConsent(
                acceptResult = { false },
                requestAfterConsent = {
                    lookups++
                    throw needsConsent
                },
                verifyAccount = { error("Missing grant") },
            )
        }.exceptionOrNull()

        assertSame(needsConsent, failure)
        assertEquals(1, lookups)
    }

    @Test
    fun unansweredProviderRequestTimesOutAndCancelsTheLookup() = runTest {
        var cancelled = false
        val failure = runCatching {
            verifyMicroGConsent(
                acceptResult = { false },
                requestAfterConsent = {
                    awaitMicroGResponse {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                },
                verifyAccount = { error("Timed-out grant") },
            )
        }.exceptionOrNull()

        assertTrue(failure is MicroGRequestTimeoutException)
        assertTrue(cancelled)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun dismissingLoginCancelsThePendingRequest() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancelled = false
        val attempt = launch {
            try {
                awaitMicroGResponse {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        started.await()
        attempt.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun failedYouTubeAccountCheckIsReportedToTheCaller() = runBlocking {
        val verified = verifyMicroGConsent(
            acceptResult = { true },
            requestAfterConsent = { error("Unexpected second token request") },
            verifyAccount = { false },
        )

        assertFalse(verified)
    }
}
