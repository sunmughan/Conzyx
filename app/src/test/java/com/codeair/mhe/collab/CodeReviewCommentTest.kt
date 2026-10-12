package com.codeair.mhe.collab

import com.codeair.mhe.collab.review.CodeReviewCommentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodeReviewCommentTest {

    private lateinit var engine: CodeReviewCommentEngine

    @Before
    fun setUp() {
        engine = CodeReviewCommentEngine()
    }

    @Test
    fun testAddComment() {
        val comment = engine.addComment(
            filePath = "app/src/main/MainActivity.kt",
            lineNumber = 25,
            author = "Senior Reviewer",
            text = "Consider extracting this logic to a ViewModel"
        )

        assertNotNull(comment.id)
        assertEquals("app/src/main/MainActivity.kt", comment.filePath)
        assertEquals(25, comment.lineNumber)
        assertEquals("Senior Reviewer", comment.author)
        assertEquals("Consider extracting this logic to a ViewModel", comment.commentText)
        assertFalse(comment.resolved)
        assertNull(comment.resolvedBy)
        assertTrue(comment.replies.isEmpty())

        assertEquals(1, engine.getAllComments().size)
    }

    @Test
    fun testAddReply() {
        val comment = engine.addComment("Utils.kt", 10, "Reviewer", "Is this thread-safe?")
        val reply = engine.addReply(comment.id, "Developer", "Yes, it uses ConcurrentHashMap.")

        assertNotNull(reply)
        assertEquals("Developer", reply!!.author)
        assertEquals("Yes, it uses ConcurrentHashMap.", reply.replyText)

        val retrieved = engine.getCommentById(comment.id)
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.replies.size)
        assertEquals(reply.id, retrieved.replies.first().id)
    }

    @Test
    fun testResolveAndReopenComment() {
        val comment = engine.addComment("Service.kt", 50, "Reviewer", "Security flaw")
        assertFalse(comment.resolved)

        val resolved = engine.resolveComment(comment.id, "Alice Lead")
        assertTrue(resolved)

        val retrieved = engine.getCommentById(comment.id)
        assertNotNull(retrieved)
        assertTrue(retrieved!!.resolved)
        assertEquals("Alice Lead", retrieved.resolvedBy)

        val reopened = engine.reopenComment(comment.id)
        assertTrue(reopened)

        val reopenedComment = engine.getCommentById(comment.id)
        assertNotNull(reopenedComment)
        assertFalse(reopenedComment!!.resolved)
        assertNull(reopenedComment.resolvedBy)
    }

    @Test
    fun testGetCommentsForFile() {
        engine.addComment("fileA.kt", 20, "User1", "Comment 1")
        engine.addComment("fileA.kt", 5, "User2", "Comment 2")
        engine.addComment("fileB.kt", 15, "User3", "Comment 3")

        val fileAComments = engine.getCommentsForFile("fileA.kt")
        assertEquals(2, fileAComments.size)
        // Verify sorted by line number ascending
        assertEquals(5, fileAComments[0].lineNumber)
        assertEquals(20, fileAComments[1].lineNumber)
    }

    @Test
    fun testGetUnresolvedComments() {
        val c1 = engine.addComment("A.kt", 1, "User", "Issue 1")
        val c2 = engine.addComment("B.kt", 2, "User", "Issue 2")
        engine.resolveComment(c1.id)

        val unresolved = engine.getUnresolvedComments()
        assertEquals(1, unresolved.size)
        assertEquals(c2.id, unresolved.first().id)
    }

    @Test
    fun testDeleteComment() {
        val c = engine.addComment("A.kt", 1, "User", "Typo")
        assertEquals(1, engine.getAllComments().size)

        val deleted = engine.deleteComment(c.id)
        assertTrue(deleted)
        assertTrue(engine.getAllComments().isEmpty())
    }
}
