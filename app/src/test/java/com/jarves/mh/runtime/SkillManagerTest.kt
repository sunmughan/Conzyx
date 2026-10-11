package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillManagerTest {

    @Test
    fun ensureDefaultSkillsProvisionsAllDefaultSkillsWithGitHubSources() {
        val tempDir = Files.createTempDirectory("skill-manager-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val skills = manager.installed()
            assertEquals("Should provision 28 default skills", 28, skills.size)

            val browser = skills.firstOrNull { it.name == "browser-automation" }
            assertNotNull("browser-automation skill should be provisioned", browser)
            assertTrue(browser!!.description.contains("Chromium"))
            assertEquals("built-in", browser.source)
            assertTrue(File(browser.path, "SKILL.md").isFile)

            val geoSleuth = skills.firstOrNull { it.name == "geo-sleuth" }
            assertNotNull("geo-sleuth skill should be provisioned", geoSleuth)
            assertEquals("https://github.com/Oldcircle/geo-sleuth", geoSleuth!!.source)
            assertTrue(File(geoSleuth.path, "SKILL.md").isFile)

            val stickman = skills.firstOrNull { it.name == "stickman-video-director" }
            assertNotNull("stickman-video-director skill should be provisioned", stickman)
            assertEquals("https://github.com/kaomei/stickman-video-director", stickman!!.source)
            assertTrue(File(stickman.path, "SKILL.md").isFile)

            val apiAnything = skills.firstOrNull { it.name == "api-anything" }
            assertNotNull("api-anything skill should be provisioned", apiAnything)
            assertEquals("https://github.com/goodnight000/api-anything", apiAnything!!.source)
            assertTrue(File(apiAnything.path, "SKILL.md").isFile)

            val logoDesign = skills.firstOrNull { it.name == "logo-design-skill" }
            assertNotNull("logo-design-skill should be provisioned", logoDesign)
            assertEquals("https://github.com/kaankiziltug/logo-design-skill", logoDesign!!.source)
            assertTrue(File(logoDesign.path, "SKILL.md").isFile)

            val anyps5 = skills.firstOrNull { it.name == "anyps5-director" }
            assertNotNull("anyps5-director skill should be provisioned", anyps5)
            assertEquals("https://github.com/boykopovar/AnyPS5", anyps5!!.source)
            assertTrue(File(anyps5.path, "SKILL.md").isFile)

            val ytIntel = skills.firstOrNull { it.name == "youtube-video-intel" }
            assertNotNull("youtube-video-intel skill should be provisioned", ytIntel)
            assertEquals("https://github.com/jarves/youtube-video-intel", ytIntel!!.source)
            assertTrue(File(ytIntel.path, "SKILL.md").isFile)

            val fridgeChef = skills.firstOrNull { it.name == "fridge-vision-chef" }
            assertNotNull("fridge-vision-chef skill should be provisioned", fridgeChef)
            assertEquals("https://github.com/jarves/fridge-vision-chef", fridgeChef!!.source)
            assertTrue(File(fridgeChef.path, "SKILL.md").isFile)

            val expenseSleuth = skills.firstOrNull { it.name == "receipt-expense-sleuth" }
            assertNotNull("receipt-expense-sleuth skill should be provisioned", expenseSleuth)
            assertEquals("https://github.com/jarves/receipt-expense-sleuth", expenseSleuth!!.source)
            assertTrue(File(expenseSleuth.path, "SKILL.md").isFile)

            val legalBuster = skills.firstOrNull { it.name == "contract-legal-buster" }
            assertNotNull("contract-legal-buster skill should be provisioned", legalBuster)
            assertEquals("https://github.com/jarves/contract-legal-buster", legalBuster!!.source)
            assertTrue(File(legalBuster.path, "SKILL.md").isFile)

            val smarthome = skills.firstOrNull { it.name == "smarthome-iot-commander" }
            assertNotNull("smarthome-iot-commander skill should be provisioned", smarthome)
            assertEquals("https://github.com/jarves/smarthome-iot-commander", smarthome!!.source)
            assertTrue(File(smarthome.path, "SKILL.md").isFile)

            val academic = skills.firstOrNull { it.name == "academic-paper-architect" }
            assertNotNull("academic-paper-architect skill should be provisioned", academic)
            assertEquals("https://github.com/jarves/academic-paper-architect", academic!!.source)
            assertTrue(File(academic.path, "SKILL.md").isFile)

            val docTransmuter = skills.firstOrNull { it.name == "document-transmuter" }
            assertNotNull("document-transmuter skill should be provisioned", docTransmuter)
            assertEquals("https://github.com/jarves/document-transmuter", docTransmuter!!.source)
            assertTrue(File(docTransmuter.path, "SKILL.md").isFile)

            val marp = skills.firstOrNull { it.name == "marp-presentation-deck" }
            assertNotNull("marp-presentation-deck skill should be provisioned", marp)
            assertEquals("https://github.com/jarves/marp-presentation-deck", marp!!.source)
            assertTrue(File(marp.path, "SKILL.md").isFile)

            val creativeImage = skills.firstOrNull { it.name == "creative-image-director" }
            assertNotNull("creative-image-director skill should be provisioned", creativeImage)
            assertEquals("https://github.com/jarves/creative-image-director", creativeImage!!.source)
            assertTrue(File(creativeImage.path, "SKILL.md").isFile)

            val viralGrowth = skills.firstOrNull { it.name == "viral-growth-creator" }
            assertNotNull("viral-growth-creator skill should be provisioned", viralGrowth)
            assertEquals("https://github.com/jarves/viral-growth-creator", viralGrowth!!.source)
            assertTrue(File(viralGrowth.path, "SKILL.md").isFile)

            val fullstack = skills.firstOrNull { it.name == "fullstack-dev-accelerator" }
            assertNotNull("fullstack-dev-accelerator skill should be provisioned", fullstack)
            assertEquals("https://github.com/jarves/fullstack-dev-accelerator", fullstack!!.source)
            assertTrue(File(fullstack.path, "SKILL.md").isFile)
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
            assertEquals(28, synced.size)
            assertTrue(synced.any { it.name == "browser-automation" })
            assertTrue(synced.any { it.name == "geo-sleuth" })
            assertTrue(synced.any { it.name == "stickman-video-director" })
            assertTrue(synced.any { it.name == "api-anything" })
            assertTrue(synced.any { it.name == "logo-design-skill" })
            assertTrue(synced.any { it.name == "anyps5-director" })
            assertTrue(synced.any { it.name == "youtube-video-intel" })
            assertTrue(synced.any { it.name == "academic-paper-architect" })
            assertTrue(synced.any { it.name == "viral-growth-creator" })
            assertTrue(synced.any { it.name == "fullstack-dev-accelerator" })

            assertTrue(File(workspaceDir, ".agents/skills/browser-automation/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/geo-sleuth/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/anyps5-director/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/youtube-video-intel/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/academic-paper-architect/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/viral-growth-creator/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/fullstack-dev-accelerator/SKILL.md").isFile)
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
            assertTrue(promptContext.contains("geo-sleuth"))
            assertTrue(promptContext.contains("anyps5-director"))
            assertTrue(promptContext.contains("youtube-video-intel"))
            assertTrue(promptContext.contains("academic-paper-architect"))
            assertTrue(promptContext.contains("viral-growth-creator"))
            assertTrue(promptContext.contains("fullstack-dev-accelerator"))
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

            // Non-github skills (e.g. browser-automation with source="built-in") should not be checked
            val installed = manager.installed()
            val githubSkills = installed.filter { it.source.startsWith("https://github.com/") }
            assertEquals(16, githubSkills.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
