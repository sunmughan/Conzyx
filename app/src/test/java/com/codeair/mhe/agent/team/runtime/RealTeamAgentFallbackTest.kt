package com.codeair.mhe.agent.team.runtime

import com.codeair.mhe.model.AgentKind
import com.codeair.mhe.model.ProviderKind
import com.codeair.mhe.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine resolution for team tasks: a role's wired engine runs when usable,
 * otherwise the user's selected agent, otherwise any usable engine. A
 * signed-in Antigravity session counts as auth (the Antigravity CLI never
 * reads a vault API key).
 */
class RealTeamAgentFallbackTest {

    private val profile = ProviderProfile(kind = ProviderKind.ANTHROPIC)

    @Test
    fun testNotInstalledIsNotReady() {
        val reason = readinessReason(
            kind = AgentKind.CLAUDE_CODE,
            isInstalled = false,
            sessionAuthAvailable = false,
            provider = profile,
            secret = "sk-key",
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("not installed"))
    }

    @Test
    fun testSessionAuthBypassesMissingVaultSecret() {
        // Antigravity with a signed-in (Gemini) session but no vault key:
        // usable. This is the case the old gate rejected.
        val reason = readinessReason(
            kind = AgentKind.ANTIGRAVITY,
            isInstalled = true,
            sessionAuthAvailable = true,
            provider = profile,
            secret = null,
        )
        assertNull(reason)
    }

    @Test
    fun testMissingSecretWithoutSessionIsNotReady() {
        val reason = readinessReason(
            kind = AgentKind.CLAUDE_CODE,
            isInstalled = true,
            sessionAuthAvailable = false,
            provider = profile,
            secret = null,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("API key"))
    }

    @Test
    fun testInstalledWithSecretIsReady() {
        val reason = readinessReason(
            kind = AgentKind.CLAUDE_CODE,
            isInstalled = true,
            sessionAuthAvailable = false,
            provider = profile,
            secret = "sk-key",
        )
        assertNull(reason)
    }

    @Test
    fun testRequestedReadyWins() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = AgentKind.ANTIGRAVITY,
            readinessOf = { null },
        )
        assertEquals(AgentResolution.Ready(AgentKind.CLAUDE_CODE), resolution)
    }

    @Test
    fun testPreferredUsedWhenRequestedNotReady() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = AgentKind.DEEPSEEK_HARNESS,
            readinessOf = { kind ->
                if (kind == AgentKind.DEEPSEEK_HARNESS) null else "not usable"
            },
        )
        assertEquals(AgentResolution.Ready(AgentKind.DEEPSEEK_HARNESS), resolution)
    }

    @Test
    fun testAntigravityFallbackWhenWiredEngineHasNoKey() {
        // Manager wired to Claude Code, no Claude key saved; Antigravity
        // session signed in: the task must run on Antigravity.
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = null,
            readinessOf = { kind ->
                if (kind == AgentKind.ANTIGRAVITY) null else "not usable"
            },
        )
        assertEquals(AgentResolution.Ready(AgentKind.ANTIGRAVITY), resolution)
    }

    @Test
    fun testUnusableNamesEveryEngineTried() {
        val resolution = resolveEffectiveKind(
            requested = AgentKind.CLAUDE_CODE,
            preferred = null,
            readinessOf = { "not usable" },
        )
        assertTrue(resolution is AgentResolution.Unusable)
        val reasons = (resolution as AgentResolution.Unusable).reasons
        assertTrue(reasons.contains("Claude Code"))
        assertTrue(reasons.contains("Antigravity"))
    }
}
