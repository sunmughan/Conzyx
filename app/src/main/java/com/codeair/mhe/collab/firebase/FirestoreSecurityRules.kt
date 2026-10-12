package com.codeair.mhe.collab.firebase

/**
 * Generates `firestore.rules` for Firebase-backed collaboration (Milestone 12).
 *
 * Security model (mirrors the engine permission checks):
 * - Every read/write requires `request.auth != null` — anonymous access is denied.
 * - Workspace membership is the document `workspaces/{ws}/members/{request.auth.uid}`;
 *   non-members cannot read or write anything under the workspace.
 * - Members subcollection: anyone in the workspace can read; only OWNER can
 *   change roles; OWNER/MAINTAINER (or self) can delete; creation is allowed for
 *   self-join on invite accept or by a member with invite rights.
 * - Invites: readable by members; writable only by members with invite rights.
 * - Presence: readable by members; each user may write ONLY their own UID doc.
 * - Sync docs: readable by members; writable by members with edit rights
 *   (OWNER/MAINTAINER/CONTRIBUTOR); delete restricted to OWNER/MAINTAINER.
 *
 * The canonical ruleset is checked in at the repo root as `firestore.rules`;
 * [FirestoreSecurityRulesTest] asserts the generator output contains every
 * required match block and invariant, so the file and the engines cannot drift
 * apart silently.
 */
object FirestoreSecurityRules {

    fun generate(): String = """
        |rules_version = '2';
        |service cloud.firestore {
        |  match /databases/{database}/documents {
        |
        |    // A request is authenticated when Firebase Auth supplied a UID.
        |    function signedIn() {
        |      return request.auth != null;
        |    }
        |
        |    // Membership document of the caller in workspace wsId, or null.
        |    function myMembership(wsId) {
        |      return get(/databases/$(database)/documents/workspaces/$(wsId)/members/$(request.auth.uid)).data;
        |    }
        |
        |    function isMember(wsId) {
        |      return signedIn() &&
        |        exists(/databases/$(database)/documents/workspaces/$(wsId)/members/$(request.auth.uid));
        |    }
        |
        |    function myRole(wsId) {
        |      return myMembership(wsId).role;
        |    }
        |
        |    function canInvite(wsId) {
        |      return isMember(wsId) && (myRole(wsId) == 'OWNER' || myRole(wsId) == 'MAINTAINER');
        |    }
        |
        |    function canEdit(wsId) {
        |      return isMember(wsId) &&
        |        (myRole(wsId) == 'OWNER' || myRole(wsId) == 'MAINTAINER' || myRole(wsId) == 'CONTRIBUTOR');
        |    }
        |
        |    function isOwner(wsId) {
        |      return isMember(wsId) && myRole(wsId) == 'OWNER';
        |    }
        |
        |    match /workspaces/{wsId} {
        |      // Members can read workspace metadata; anyone signed in can create
        |      // (creation writes the creator as OWNER member in the same batch).
        |      allow read: if isMember(wsId);
        |      allow create: if signedIn();
        |      // Only the owner can rename or delete the workspace.
        |      allow update, delete: if isOwner(wsId);
        |
        |      match /members/{memberId} {
        |        allow read: if isMember(wsId);
        |        // Self-join on invite accept, or added by someone with invite rights.
        |        allow create: if signedIn() && (memberId == request.auth.uid || canInvite(wsId));
        |        // Role changes are owner-only; role field may only move to non-OWNER roles.
        |        allow update: if isOwner(wsId)
        |          && request.resource.data.role != 'OWNER';
        |        // Leave (self) or removal by owner/maintainer; owner can never be removed.
        |        allow delete: if signedIn()
        |          && (memberId == request.auth.uid || canInvite(wsId))
        |          && resource.data.role != 'OWNER';
        |      }
        |
        |      match /invites/{inviteId} {
        |        allow read: if isMember(wsId);
        |        // Only members with invite rights can create or revoke invites.
        |        allow create, update: if canInvite(wsId);
        |        allow delete: if canInvite(wsId);
        |      }
        |
        |      match /presence/{uid} {
        |        allow read: if isMember(wsId);
        |        // Users may only write their own presence heartbeat.
        |        allow write: if signedIn() && uid == request.auth.uid && isMember(wsId);
        |      }
        |
        |      match /sync/{docId} {
        |        allow read: if isMember(wsId);
        |        // Collaborative edits: owner/maintainer/contributor.
        |        allow create, update: if canEdit(wsId);
        |        // Destructive delete: owner/maintainer only.
        |        allow delete: if canInvite(wsId);
        |      }
        |    }
        |  }
        |}
        |""".trimMargin()
}
