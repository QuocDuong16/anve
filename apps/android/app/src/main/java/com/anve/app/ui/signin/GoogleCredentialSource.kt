package com.anve.app.ui.signin

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.anve.app.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL

/**
 * Thin wrapper around Credential Manager so ViewModels never touch the
 * Android UI API directly. Obtains a Google ID token addressed to the
 * backend's OAuth client (ADR 0002); verification happens server side.
 *
 * The suspend variants of the CredentialManager API are used throughout;
 * the account chooser runs on the UI layer while the caller stays free to
 * dispatch the call from any coroutine context.
 */
class GoogleCredentialSource(private val activity: Activity) {

    private val serverClientId: String =
        activity.getString(R.string.anve_google_server_client_id)

    val isConfigured: Boolean
        get() = serverClientId.isNotBlank() && serverClientId != "REPLACE_ME"

    /** Returns the ID token, or fails with [GoogleSignInUnavailableError]. */
    suspend fun fetchIdToken(): Result<String> {
        if (!isConfigured) {
            return Result.failure(GoogleSignInUnavailableError)
        }
        return runCatching {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val response = CredentialManager.create(activity)
                .getCredential(activity, request)
            val credential = response.credential
            check(credential is CustomCredential &&
                credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                "unexpected credential type: ${credential.type}"
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
    }

    suspend fun clearCredentialState() {
        runCatching {
            CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}

/** Thrown when this install has no OAuth client configured. */
object GoogleSignInUnavailableError : IllegalStateException(
    "Google sign-in is not configured on this install (anve_google_server_client_id)",
)
