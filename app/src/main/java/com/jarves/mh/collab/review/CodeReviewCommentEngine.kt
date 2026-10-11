package com.jarves.mh.collab.review

import com.jarves.mh.collab.model.CodeComment
import com.jarves.mh.collab.model.CommentReply
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-Safe Inline Code Review & Comment Thread Engine (Phase 13).
 * Manages file/line-level discussion threads, replies, and review resolution states.
 */
class CodeReviewCommentEngine {

    private val commentsMap = ConcurrentHashMap<String, CodeComment>()
    private val _commentsFlow = MutableStateFlow<List<CodeComment>>(emptyList())
    val commentsFlow: StateFlow<List<CodeComment>> = _commentsFlow.asStateFlow()

    fun addComment(
        filePath: String,
        lineNumber: Int,
        author: String,
        text: String
    ): CodeComment {
        val comment = CodeComment(
            id = UUID.randomUUID().toString(),
            author = author,
            filePath = filePath,
            lineNumber = lineNumber,
            commentText = text,
            resolved = false,
            timestamp = System.currentTimeMillis()
        )
        commentsMap[comment.id] = comment
        syncFlow()
        return comment
    }

    fun addReply(
        commentId: String,
        author: String,
        text: String
    ): CommentReply? {
        val current = commentsMap[commentId] ?: return null
        val reply = CommentReply(
            author = author,
            replyText = text,
            timestamp = System.currentTimeMillis()
        )
        val updated = current.copy(replies = current.replies + reply)
        commentsMap[commentId] = updated
        syncFlow()
        return reply
    }

    fun resolveComment(commentId: String, resolvedBy: String = "Reviewer"): Boolean {
        val current = commentsMap[commentId] ?: return false
        val updated = current.copy(
            resolved = true,
            resolvedBy = resolvedBy
        )
        commentsMap[commentId] = updated
        syncFlow()
        return true
    }

    fun reopenComment(commentId: String): Boolean {
        val current = commentsMap[commentId] ?: return false
        val updated = current.copy(
            resolved = false,
            resolvedBy = null
        )
        commentsMap[commentId] = updated
        syncFlow()
        return true
    }

    fun deleteComment(commentId: String): Boolean {
        val removed = commentsMap.remove(commentId) != null
        if (removed) {
            syncFlow()
        }
        return removed
    }

    fun getCommentsForFile(filePath: String): List<CodeComment> {
        return commentsMap.values
            .filter { it.filePath.equals(filePath, ignoreCase = true) || it.filePath.endsWith(filePath) }
            .sortedBy { it.lineNumber }
    }

    fun getAllComments(): List<CodeComment> {
        return commentsMap.values.sortedByDescending { it.timestamp }
    }

    fun getUnresolvedComments(): List<CodeComment> {
        return commentsMap.values
            .filter { !it.resolved }
            .sortedByDescending { it.timestamp }
    }

    fun getCommentById(commentId: String): CodeComment? = commentsMap[commentId]

    fun loadComments(comments: List<CodeComment>) {
        commentsMap.clear()
        comments.forEach { commentsMap[it.id] = it }
        syncFlow()
    }

    fun clearComments() {
        commentsMap.clear()
        syncFlow()
    }

    private fun syncFlow() {
        _commentsFlow.value = commentsMap.values.sortedByDescending { it.timestamp }
    }
}
