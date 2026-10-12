package com.codeair.mhe.collab.firebase

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Firestore security ruleset: the generator output must contain
 * every match block and every auth invariant the engines rely on. If a rule is
 * weakened or a collection path renamed, this test fails first.
 */
class FirestoreSecurityRulesTest {

    private val rules = FirestoreSecurityRules.generate()

    @Test
    fun `rules target firestore documents`() {
        assertTrue(rules.contains("service cloud.firestore"))
        assertTrue(rules.contains("rules_version = '2'"))
    }

    @Test
    fun `anonymous access is denied everywhere`() {
        assertTrue(rules.contains("function signedIn()"))
        assertTrue(rules.contains("request.auth != null"))
    }

    @Test
    fun `workspace match block exists with member-gated reads`() {
        assertTrue(rules.contains("match /workspaces/{wsId}"))
        assertTrue(rules.contains("allow read: if isMember(wsId)"))
        assertTrue(rules.contains("allow update, delete: if isOwner(wsId)"))
    }

    @Test
    fun `members subcollection restricts role changes to owner`() {
        assertTrue(rules.contains("match /members/{memberId}"))
        assertTrue(rules.contains("allow update: if isOwner(wsId)"))
        assertTrue(rules.contains("request.resource.data.role != 'OWNER'"))
        assertTrue(rules.contains("resource.data.role != 'OWNER'"))
    }

    @Test
    fun `invites are writable only with invite rights`() {
        assertTrue(rules.contains("match /invites/{inviteId}"))
        assertTrue(rules.contains("allow create, update: if canInvite(wsId)"))
    }

    @Test
    fun `presence writes are restricted to own uid`() {
        assertTrue(rules.contains("match /presence/{uid}"))
        assertTrue(rules.contains("uid == request.auth.uid"))
    }

    @Test
    fun `sync documents allow edit roles and restrict deletes`() {
        assertTrue(rules.contains("match /sync/{docId}"))
        assertTrue(rules.contains("allow create, update: if canEdit(wsId)"))
        assertTrue(rules.contains("allow delete: if canInvite(wsId)"))
    }

    @Test
    fun `role helpers cover the full hierarchy`() {
        assertTrue(rules.contains("'OWNER'"))
        assertTrue(rules.contains("'MAINTAINER'"))
        assertTrue(rules.contains("'CONTRIBUTOR'"))
    }
}
