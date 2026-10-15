package com.codeair.mhe.runtime

import com.codeair.mhe.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillManagerTest {

    @Test
    fun ensureDefaultSkillsProvisionsBuiltInSkills() {
        val tempDir = Files.createTempDirectory("skill-manager-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val skills = manager.installed()
            assertEquals("Should provision 12 built-in skills", 12, skills.size)

            val browser = skills.firstOrNull { it.name == "browser-automation" }
            assertNotNull("browser-automation skill should be provisioned", browser)
            assertTrue(browser!!.description.contains("Chromium"))
            assertEquals("built-in", browser.source)
            assertTrue(File(browser.path, "SKILL.md").isFile)

            val android = skills.firstOrNull { it.name == "android-development" }
            assertNotNull("android-development skill should be provisioned", android)
            assertEquals("built-in", android!!.source)
            assertTrue(File(android.path, "SKILL.md").isFile)

            assertTrue("retired stub skills must not be seeded",
                skills.none { it.name == "academic-paper-architect" || it.name == "geo-sleuth" || it.name == "fullstack-dev-accelerator" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ensureDefaultSkillsRemovesRetiredStubSkills() {
        val tempDir = Files.createTempDirectory("skill-retire-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val stubDir = File(tempDir, "academic-paper-architect").apply { mkdirs() }
            File(stubDir, "SKILL.md").writeText("---\nname: academic-paper-architect\n---\n")
            val registry = File(tempDir, "registry.json")
            registry.writeText(
                """[{"name":"academic-paper-architect","description":"stub","source":"https://github.com/jarves/academic-paper-architect","installedAtMillis":1,"path":"${stubDir.absolutePath}","enabled":true}]"""
            )
            manager.ensureDefaultSkills()
            val skills = manager.installed()
            assertTrue(skills.none { it.name == "academic-paper-architect" })
            assertFalse(stubDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun syncToWorkspaceCopiesSkillsIntoAgentsDirectory() {
        val skillsDir = Files.createTempDirectory("skills-dir").toFile()
        val workspaceDir = Files.createTempDirectory("workspace-dir").toFile()
        try {
            val manager = SkillManager(baseDir = skillsDir)
            manager.ensureDefaultSkills()

            val synced = manager.syncToWorkspace(workspaceDir)
            assertEquals(12, synced.size)
            assertTrue(synced.any { it.name == "browser-automation" })
            assertTrue(synced.any { it.name == "android-development" })
            assertTrue(synced.any { it.name == "python-development" })

            assertTrue(File(workspaceDir, ".agents/skills/browser-automation/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/android-development/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/python-development/SKILL.md").isFile)
        } finally {
            skillsDir.deleteRecursively()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun buildPromptContextIncludesProvisionedSkills() {
        val tempDir = Files.createTempDirectory("skill-prompt-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val promptContext = manager.buildPromptContext(AgentKind.ANTIGRAVITY)
            assertTrue(promptContext.contains("<mobile_harness_skills>"))
            assertTrue(promptContext.contains("browser-automation"))
            assertTrue(promptContext.contains("android-development"))
            assertTrue(promptContext.contains("python-development"))
            assertTrue(promptContext.contains("Selected agent: antigravity"))
            assertTrue(promptContext.contains("</mobile_harness_skills>"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun checkUpdatesFiltersGitHubSources() {
        val tempDir = Files.createTempDirectory("skill-update-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val installed = manager.installed()
            val githubSkills = installed.filter { it.source.startsWith("https://github.com/") }
            assertEquals(0, githubSkills.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun setEnabledDisablesAndReenablesSkill() {
        val tempDir = Files.createTempDirectory("skill-toggle-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            assertTrue(manager.setEnabled("android-development", false))
            val disabled = manager.installed().first { it.name == "android-development" }
            assertFalse(disabled.enabled)
            assertTrue(manager.installed().first { it.name == "browser-automation" }.enabled)

            assertTrue(manager.setEnabled("android-development", true))
            assertTrue(manager.installed().first { it.name == "android-development" }.enabled)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun setEnabledReturnsFalseForUnknownSkill() {
        val tempDir = Files.createTempDirectory("skill-toggle-unknown-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()
            assertFalse(manager.setEnabled("no-such-skill", false))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun enabledSkillsExcludesDisabled() {
        val tempDir = Files.createTempDirectory("skill-enabled-list-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()
            val total = manager.installed().size

            manager.setEnabled("android-development", false)
            manager.setEnabled("python-development", false)

            val enabled = manager.enabledSkills()
            assertEquals(total - 2, enabled.size)
            assertTrue(enabled.none { it.name == "android-development" || it.name == "python-development" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildPromptContextExcludesDisabledSkills() {
        val tempDir = Files.createTempDirectory("skill-prompt-toggle-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            manager.setEnabled("android-development", false)

            val promptContext = manager.buildPromptContext(AgentKind.ANTIGRAVITY)
            assertTrue(promptContext.contains("browser-automation"))
            assertFalse(promptContext.contains("android-development"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun syncToWorkspaceSkipsDisabledSkills() {
        val tempDir = Files.createTempDirectory("skill-sync-toggle-test").toFile()
        val workspaceDir = Files.createTempDirectory("skill-sync-toggle-workspace").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            manager.setEnabled("android-development", false)
            val synced = manager.syncToWorkspace(workspaceDir)

            assertTrue(synced.none { it.name == "android-development" })
            assertFalse(File(workspaceDir, ".agents/skills/android-development").exists())
            assertTrue(File(workspaceDir, ".agents/skills/browser-automation/SKILL.md").isFile)
        } finally {
            tempDir.deleteRecursively()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun enabledFlagSurvivesRegistryRoundTrip() {
        val tempDir = Files.createTempDirectory("skill-toggle-persist-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()
            manager.setEnabled("android-development", false)

            val reloaded = SkillManager(baseDir = tempDir)
            assertFalse(reloaded.installed().first { it.name == "android-development" }.enabled)
            assertTrue(reloaded.enabledSkills().none { it.name == "android-development" })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
