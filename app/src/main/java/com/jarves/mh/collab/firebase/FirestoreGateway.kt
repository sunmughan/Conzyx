package com.jarves.mh.collab.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Seam over the Firestore SDK (Milestone 12).
 *
 * Production uses [FirebaseFirestoreGateway]; unit tests use an in-memory fake,
 * so sync/invite/presence logic is verified without a network or a Firebase project.
 * All methods throw only on genuine transport failures — callers wrap them into
 * [com.jarves.mh.core.result.OperationResult] themselves.
 */
interface FirestoreGateway {
    /** Returns the document map, or null when the document does not exist. */
    suspend fun getDocument(path: String): Map<String, Any?>?

    /** Creates or replaces the document; when [merge] is true, fields are merged. */
    suspend fun setDocument(path: String, data: Map<String, Any?>, merge: Boolean = false)

    /** Updates fields on an existing document; fails when the document is missing. */
    suspend fun updateDocument(path: String, data: Map<String, Any?>)

    /** Deletes the document; succeeds silently when it does not exist. */
    suspend fun deleteDocument(path: String)

    /** Lists (docId, data) pairs in [collectionPath] where [field] equals [value]. */
    suspend fun queryEqual(
        collectionPath: String,
        field: String,
        value: Any?
    ): List<Pair<String, Map<String, Any?>>>

    /** Lists every (docId, data) pair in [collectionPath]. */
    suspend fun listCollection(collectionPath: String): List<Pair<String, Map<String, Any?>>>

    /** Emits the document map on every remote change, or null when deleted. */
    fun listenDocument(path: String): Flow<Map<String, Any?>?>
}

/**
 * Real Firestore implementation of [FirestoreGateway].
 *
 * Open for test fakes (v3 convention). Construct via [create], which fails fast
 * and honestly when Firebase is not initialized in this process.
 */
open class FirebaseFirestoreGateway protected constructor(
    private val db: FirebaseFirestore
) : FirestoreGateway {

    companion object {
        /**
         * Returns a gateway backed by the default [FirebaseFirestore] instance.
         * Throws [IllegalStateException] when FirebaseApp is not initialized —
         * the caller must surface that as NOT_CONFIGURED, never as success.
         */
        fun create(): FirebaseFirestoreGateway {
            val db = runCatching { FirebaseFirestore.getInstance() }.getOrElse {
                throw IllegalStateException(
                    "FirebaseApp is not initialized — cannot create Firestore gateway. " +
                        "Initialize Firebase (google-services.json) before using collaboration."
                )
            }
            return FirebaseFirestoreGateway(db)
        }
    }

    override suspend fun getDocument(path: String): Map<String, Any?>? {
        val snap = db.document(path).get().await()
        return if (snap.exists()) snap.data else null
    }

    override suspend fun setDocument(path: String, data: Map<String, Any?>, merge: Boolean) {
        val ref = db.document(path)
        if (merge) ref.set(data.filterValues { it != null }, com.google.firebase.firestore.SetOptions.merge()).await()
        else ref.set(data).await()
    }

    override suspend fun updateDocument(path: String, data: Map<String, Any?>) {
        db.document(path).update(data).await()
    }

    override suspend fun deleteDocument(path: String) {
        db.document(path).delete().await()
    }

    override suspend fun queryEqual(
        collectionPath: String,
        field: String,
        value: Any?
    ): List<Pair<String, Map<String, Any?>>> {
        val snap = db.collection(collectionPath).whereEqualTo(field, value).get().await()
        return snap.documents.mapNotNull { doc -> doc.data?.let { doc.id to it } }
    }

    override suspend fun listCollection(collectionPath: String): List<Pair<String, Map<String, Any?>>> {
        val snap = db.collection(collectionPath).get().await()
        return snap.documents.mapNotNull { doc -> doc.data?.let { doc.id to it } }
    }

    override fun listenDocument(path: String): Flow<Map<String, Any?>?> = callbackFlow {
        var registration: ListenerRegistration? = null
        try {
            registration = db.document(path).addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(if (snap != null && snap.exists()) snap.data else null)
            }
        } catch (e: Exception) {
            close(e)
        }
        awaitClose { registration?.remove() }
    }
}
