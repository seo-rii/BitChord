package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.music.bitchord.R
import com.music.bitchord.data.innertube.YouTubeTokenProvider
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SignInStep { ChooseAccount, RequestGrant, AwaitResult, AwaitReturn }

@Composable
fun MicroGLoginScreen(
    onDismiss: () -> Unit,
    onAuthorized: suspend (Account, YouTubeTokenProvider) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val authorize by rememberUpdatedState(onAuthorized)
    var step by rememberSaveable { mutableStateOf(SignInStep.ChooseAccount) }
    var selected by rememberSaveable { mutableStateOf<Account?>(null) }
    val provider = remember(context, selected) { selected?.let { MicroGTokenProvider(context, it) } }
    val authenticator by produceState<MicroGAuthenticator?>(null, context, selected?.type) {
        value = selected?.type?.let { withContext(Dispatchers.IO) { microGAuthenticator(context, it) } }
    }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var verifyingYouTube by remember { mutableStateOf(false) }
    var visitedScreens by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var leftForExternalScreen by rememberSaveable { mutableStateOf(false) }
    // Only the provider retains the short-lived grant, in memory.
    var receivedGrant by remember(provider) { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (step == SignInStep.AwaitResult) {
            // Older releases deliver consent through AccountManager even on RESULT_CANCELED.
            receivedGrant = provider?.acceptConsentResult(result.data) == true
            step = SignInStep.RequestGrant
        }
    }
    // A singleTask login screen can deliver a canceled Activity result before the user
    // even sees it. For these screens, wait for the actual trip away and back.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (step == SignInStep.AwaitReturn) {
                if (event == Lifecycle.Event.ON_PAUSE) leftForExternalScreen = true
                else if (event == Lifecycle.Event.ON_RESUME && leftForExternalScreen) {
                    step = SignInStep.RequestGrant
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            onDismiss()
        } else {
            val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (name.isNullOrBlank() || type.isNullOrBlank()) {
                error = R.string.microg_authorization_failed
            } else {
                selected = Account(name, type)
                receivedGrant = false
                visitedScreens = arrayListOf()
                error = null
                step = SignInStep.RequestGrant
            }
        }
    }

    LaunchedEffect(step, provider) {
        if (step != SignInStep.RequestGrant) return@LaunchedEffect
        val account = selected ?: return@LaunchedEffect
        val tokens = provider ?: return@LaunchedEffect
        try {
            lifecycle.withResumed { }
            when (val next = advanceMicroGGrant(
                hasReturnedGrant = receivedGrant,
                visitedScreens = visitedScreens,
                requestGrant = tokens::prepareAuthorization,
                screenKey = { it.id },
            )) {
                is MicroGGrantAction.OpenScreen -> {
                    lifecycle.withResumed {
                        visitedScreens = ArrayList(next.visitedScreens)
                        if (next.screen.mayReturnEarly) {
                            leftForExternalScreen = false
                            step = SignInStep.AwaitReturn
                            context.startActivity(next.screen.intent)
                        } else {
                            step = SignInStep.AwaitResult
                            consent.launch(next.screen.intent)
                        }
                    }
                    return@LaunchedEffect
                }
                MicroGGrantAction.VerifyAccount -> {
                    verifyingYouTube = true
                    if (!authorize(account, tokens)) error = R.string.microg_youtube_failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: MicroGRequestTimeoutException) {
            error = if (authenticator?.lacksKnownConsentFlow == true) {
                R.string.microg_legacy_timeout
            } else R.string.microg_timeout
        } catch (_: MicroGResolutionUnavailableException) {
            error = R.string.microg_screen_unavailable
        } catch (_: ActivityNotFoundException) {
            error = R.string.microg_screen_unavailable
        } catch (_: SecurityException) {
            error = R.string.microg_authorization_failed
        } catch (_: MicroGConsentRequiredException) {
            error = R.string.microg_consent_required
        } catch (_: OperationCanceledException) {
            error = R.string.microg_consent_required
        } catch (e: ClientRequestException) {
            error = when (e.response.status.value) {
                401, 403 -> R.string.microg_youtube_denied
                else -> R.string.microg_youtube_failed
            }
        } catch (_: Exception) {
            error = if (verifyingYouTube) R.string.microg_youtube_failed else R.string.microg_authorization_failed
        } finally {
            verifyingYouTube = false
        }
        step = SignInStep.ChooseAccount
    }

    val waitingForScreen = step == SignInStep.AwaitResult || step == SignInStep.AwaitReturn
    val message = error ?: when {
        verifyingYouTube -> R.string.microg_checking_youtube
        step == SignInStep.RequestGrant -> R.string.microg_requesting_grant
        waitingForScreen -> R.string.microg_waiting_for_consent
        else -> R.string.microg_account_prompt
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.microg_sign_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(message))
                authenticator?.let { Text(it.displayLabel, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (waitingForScreen) {
                // Also recover if Android returned to BitChord without delivering an Activity result.
                TextButton(onClick = { step = SignInStep.RequestGrant }) {
                    Text(stringResource(R.string.microg_check_grant))
                }
            } else if (step == SignInStep.ChooseAccount) {
                TextButton(onClick = {
                    error = null
                    try {
                        val types = microGAccountTypes(context)
                        if (types.isEmpty()) {
                            error = R.string.microg_unavailable
                        } else {
                            chooser.launch(AccountManager.newChooseAccountIntent(
                                null, null, types.toTypedArray(), null, null, null, null,
                            ))
                        }
                    } catch (_: Exception) {
                        error = R.string.microg_authorization_failed
                    }
                }) { Text(stringResource(R.string.microg_choose_account)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
