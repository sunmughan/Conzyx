package com.codeair.mhe.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectChatTest {

    @Test
    fun testProjectChatCreation() {
        val chat = ProjectChat(title = "Feature Exploration")
        assertNotNull(chat.id)
        assertEquals("Feature Exploration", chat.title)
        assertTrue(chat.createdAtMillis > 0L)
        assertTrue(chat.updatedAtMillis > 0L)
    }

    @Test
    fun testProjectSlugSanitization() {
        assertEquals("my-awesome-repo", projectSlug("My Awesome Repo!"))
        assertEquals("mobile-harness-v2", projectSlug("Mobile Harness (v2)"))
        assertEquals("project", projectSlug("   "))
    }
}
