package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OperationCanceledException
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.music.bitchord.R
import com.music.bitchord.data.innertube.YouTubeTokenProvider
import kotlinx.coroutines.CancellationException

private enum class SignInStep { ChooseAccount, RequestGrant, AwaitConsent, ConfirmGrant }

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
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var verifyingYouTube by remember { mutableStateOf(false) }
    // The token stays inside the provider and is never saved in Activity state.
    var receivedGrant by remember(provider) { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (step == SignInStep.AwaitConsent) {
            // Older microG releases may return RESULT_CANCELED even after granting access.
            receivedGrant = provider?.acceptConsentResult(result.data) == true
            step = SignInStep.ConfirmGrant
        }
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
                error = null
                step = SignInStep.RequestGrant
            }
        }
    }

    LaunchedEffect(step, provider) {
        if (step != SignInStep.RequestGrant && step != SignInStep.ConfirmGrant) return@LaunchedEffect
        val account = selected ?: return@LaunchedEffect
        val tokens = provider ?: return@LaunchedEffect
        try {
            if (step == SignInStep.RequestGrant) {
                val intent = tokens.prepareAuthorization()
                if (intent != null) {
                    // The account chooser can deliver its result before our Activity is resumed.
                    lifecycle.withResumed {
                        step = SignInStep.AwaitConsent
                        consent.launch(intent)
                    }
                    return@LaunchedEffect
                }
                receivedGrant = true
            }
            val accepted = verifyMicroGConsent(
                acceptResult = { receivedGrant },
                requestAfterConsent = tokens::finishAuthorization,
                verifyAccount = {
                    verifyingYouTube = true
                    authorize(account, tokens)
                },
            )
            if (!accepted) error = R.string.microg_youtube_failed
        } catch (e: CancellationException) {
            throw e
        } catch (_: MicroGRequestTimeoutException) {
            error = R.string.microg_timeout
        } catch (_: MicroGConsentRequiredException) {
            error = R.string.microg_consent_required
        } catch (_: OperationCanceledException) {
            error = R.string.microg_consent_required
        } catch (_: Exception) {
            error = R.string.microg_authorization_failed
        } finally {
            verifyingYouTube = false
        }
        step = SignInStep.ChooseAccount
    }

    val message = error ?: when {
        verifyingYouTube -> R.string.microg_checking_youtube
        step == SignInStep.RequestGrant || step == SignInStep.ConfirmGrant -> R.string.microg_requesting_grant
        step == SignInStep.AwaitConsent -> R.string.microg_waiting_for_consent
        else -> R.string.microg_account_prompt
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.microg_sign_in)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            if (step == SignInStep.AwaitConsent) {
                // Also recover if Android returned to BitChord without delivering an Activity result.
                TextButton(onClick = { step = SignInStep.ConfirmGrant }) {
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
