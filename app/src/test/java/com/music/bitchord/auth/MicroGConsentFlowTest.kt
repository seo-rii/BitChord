package com.music.bitchord.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicroGConsentFlowTest {
    @Test
    fun returnedGrantCanBeVerifiedWithoutAnotherRequest() = runTest {
        val action = advanceMicroGGrant<String>(true, listOf("consent"), { error("Unexpected token request") }, { it })
        assertEquals(MicroGGrantAction.VerifyAccount, action)
    }

    @Test
    fun emptyCanceledActivityResultCanStillHaveAProviderGrant() = runTest {
        var lookups = 0
        val action = advanceMicroGGrant<String>(
            hasReturnedGrant = false,
            visitedScreens = listOf("consent"),
            requestGrant = {
                lookups++
                null
            },
            screenKey = { it },
        )
        assertEquals(1, lookups)
        assertEquals(MicroGGrantAction.VerifyAccount, action)
    }

    @Test
    fun reauthenticationCanBeFollowedByScopeConsent() = runTest {
        val first = advanceMicroGGrant(false, emptyList(), { "reauth" }, { it })
        assertEquals(MicroGGrantAction.OpenScreen("reauth", listOf("reauth")), first)

        val next = advanceMicroGGrant(false, listOf("reauth"), { "consent" }, { it })
        assertEquals(MicroGGrantAction.OpenScreen("consent", listOf("reauth", "consent")), next)
    }

    @Test
    fun platformGrantCanPrecedeBothProviderScreens() = runTest {
        val beforeConsent = listOf("system", "reauth")
        val consent = advanceMicroGGrant(false, beforeConsent, { "consent" }, { it })
        assertEquals(MicroGGrantAction.OpenScreen("consent", beforeConsent + "consent"), consent)

        val completed = advanceMicroGGrant<String>(false, beforeConsent + "consent", { null }, { it })
        assertEquals(MicroGGrantAction.VerifyAccount, completed)
    }

    @Test
    fun returningToAPreviousScreenStopsTheAttemptEvenForANewIntent() = runTest {
        val previous = listOf("reauth", "consent")
        val newRequest = Pair("reauth", "fresh request data")
        val failure = runCatching {
            advanceMicroGGrant(false, previous, { newRequest }, { it.first })
        }.exceptionOrNull()
        assertTrue(failure is MicroGConsentRequiredException)
    }

    @Test
    fun fourthInteractiveScreenStopsTheAttempt() = runTest {
        val failure = runCatching {
            advanceMicroGGrant(false, listOf("system", "reauth", "consent"), { "other" }, { it })
        }.exceptionOrNull()
        assertTrue(failure is MicroGConsentRequiredException)
    }

    @Test
    fun unansweredProviderRequestTimesOutAndCancelsTheLookup() = runTest {
        var cancelled = false
        val failure = runCatching {
            advanceMicroGGrant<String>(
                hasReturnedGrant = false,
                visitedScreens = emptyList(),
                requestGrant = {
                    awaitMicroGResponse {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                },
                screenKey = { it },
            )
        }.exceptionOrNull()

        assertTrue(failure is MicroGRequestTimeoutException)
        assertTrue(cancelled)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun dismissingLoginCancelsThePendingRequest() = runTest {
        val started = CompletableDeferred<Unit>()
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
}
