package com.codeair.mhe.collab.firebase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [FirestoreGateway] fake for unit tests: path-keyed document store,
 * scriptable failures, and observable document flows. No network, no Firebase.
 */
class InMemoryFirestoreGateway : FirestoreGateway {
    private val docs = mutableMapOf<String, Map<String, Any?>>()
    private val flows = mutableMapOf<String, MutableStateFlow<Map<String, Any?>>>()
    var failWith: Exception? = null

    fun seed(path: String, data: Map<String, Any?>) {
        docs[path] = data
        flows[path]?.value = data
    }

    private fun checkFailure() {
        failWith?.let { throw it }
    }

    override suspend fun getDocument(path: String): Map<String, Any?>? {
        checkFailure()
        return docs[path]
    }

    override suspend fun setDocument(path: String, data: Map<String, Any?>, merge: Boolean) {
        checkFailure()
        val next = if (merge) (docs[path] ?: emptyMap()) + data else data
        docs[path] = next
        flows.getOrPut(path) { MutableStateFlow(next) }.value = next
    }

    override suspend fun updateDocument(path: String, data: Map<String, Any?>) {
        checkFailure()
        val existing = docs[path] ?: throw IllegalStateException("Document not found: $path")
        val next = existing + data
        docs[path] = next
        flows.getOrPut(path) { MutableStateFlow(next) }.value = next
    }

    override suspend fun deleteDocument(path: String) {
        checkFailure()
        docs.remove(path)
        flows[path]?.value = emptyMap()
    }

    override suspend fun queryEqual(
        collectionPath: String,
        field: String,
        value: Any?
    ): List<Pair<String, Map<String, Any?>>> {
        checkFailure()
        val prefix = "$collectionPath/"
        return docs.entries
            .filter { (path, data) ->
                path.startsWith(prefix) &&
                    !path.removePrefix(prefix).contains("/") &&
                    data[field] == value
            }
            .map { (path, data) -> path.removePrefix(prefix) to data }
    }

    override suspend fun listCollection(collectionPath: String): List<Pair<String, Map<String, Any?>>> {
        checkFailure()
        val prefix = "$collectionPath/"
        return docs.entries
            .filter { (path, _) ->
                path.startsWith(prefix) && !path.removePrefix(prefix).contains("/")
            }
            .map { (path, data) -> path.removePrefix(prefix) to data }
    }

    override fun listenDocument(path: String): Flow<Map<String, Any?>?> {
        val flow = flows.getOrPut(path) { MutableStateFlow(docs[path] ?: emptyMap()) }
        return flow.map { if (it.isEmpty() && docs[path] == null) null else it }
    }
}
