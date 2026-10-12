package com.codeair.mhe.collab.firebase

import com.google.firebase.auth.FirebaseAuth
import com.codeair.mhe.core.result.OperationResult

/**
 * Firebase Auth UID identity for collaboration (Milestone 12).
 *
 * Every mutating collaboration action requires a signed-in Firebase user; the
 * document id of membership/presence records IS the Auth UID, so identity is
 * the security anchor for both the engines below and `firestore.rules`.
 *
 * The [uidProvider] seam keeps this unit-testable: production resolves the UID
 * from [FirebaseAuth], tests supply a fixed UID (or null to model sign-out).
 */
class FirebaseCollaborationIdentity(
    private val uidProvider: () -> String? = { runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() }
) {
    /** The current Firebase Auth UID, or null when nobody is signed in. */
    fun currentUid(): String? = uidProvider()

    /** True when a Firebase user is currently signed in. */
    fun isSignedIn(): Boolean = !currentUid().isNullOrBlank()
}

/**
 * Guard helper: returns a NOT_CONFIGURED [OperationResult] when [uid] is blank
 * (nobody signed in), so mutating collaboration actions refuse honestly instead
 * of writing anonymous data. Returns null when the action may proceed.
 */
fun requireSignedIn(uid: String?, action: String): OperationResult? =
    if (uid.isNullOrBlank()) {
        OperationResult.notConfigured(
            "Firebase sign-in is required to $action. No authenticated user was found, " +
                "so nothing was changed. Sign in with Firebase Auth and retry."
        )
    } else null
