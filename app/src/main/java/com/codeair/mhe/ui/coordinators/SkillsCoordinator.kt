package com.codeair.mhe.ui.coordinators

import android.net.Uri
import com.codeair.mhe.data.AppPreferences
import com.codeair.mhe.model.DevStack
import com.codeair.mhe.runtime.RuntimeInstallEvent
import com.codeair.mhe.runtime.RuntimeInstaller
import com.codeair.mhe.runtime.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skills domain coordinator (Milestone 1).
 *
 * Explicit owner of the skills state machine: installed skills, update
 * checks, GitHub/URI imports, removal and the tools+skills auto-update flow.
 * All operations go through the real [SkillManager] (and [RuntimeInstaller]
 * for the agent/toolchain legs of auto-update); failures surface as honest
 * messages, never fake success.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class SkillsCoordinator(
    private val host: CoordinatorHost,
    private val skillManager: SkillManager,
    private val installer: RuntimeInstaller,
    private val preferences: AppPreferences,
) {
    fun refreshSkills() {
        host.updateState { it.copy(skills = skillManager.installed()) }
    }

    fun checkSkillUpdates() {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Checking installed skills for updates…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.checkUpdates() } }
            result.onSuccess { updates ->
                host.updateState {
                    it.copy(
                        skillsBusy = false,
                        skillUpdates = updates.mapTo(mutableSetOf()) { update -> update.name },
                        skillsMessage = if (updates.isEmpty()) "All remotely sourced skills are up to date." else updates.size.toString() + " skill update" + (if (updates.size == 1) "" else "s") + " available.",
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill update check failed.") }
            }
        }
    }

    fun updateSkill(name: String) {
        if (host.state().skillsBusy) return
        val skill = host.state().skills.firstOrNull { it.name == name } ?: return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Updating $name…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.update(skill) } }
            result.onSuccess {
                host.updateState {
                    it.copy(
                        skills = skillManager.installed(),
                        skillsBusy = false,
                        skillUpdates = it.skillUpdates - name,
                        skillsMessage = "$name updated successfully.",
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill update failed.") }
            }
        }
    }

    fun searchSkills(query: String) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Searching GitHub…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.searchGitHub(query) } }
            result.onSuccess { matches -> host.updateState { it.copy(skillsBusy = false, skillSearchResults = matches, skillsMessage = "Found " + matches.size + " repositories.") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "GitHub skill search failed.") } }
        }
    }

    fun importSkillFromGitHub(url: String) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Importing skill bundle…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromGitHub(url) } }
            result.onSuccess { installed -> host.updateState { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill bundle(s).") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") } }
        }
    }

    fun importSkillFromUri(uri: Uri) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Importing local skill bundle…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromUri(uri) } }
            result.onSuccess { installed -> host.updateState { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill bundle(s).") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") } }
        }
    }

    fun removeSkill(name: String) {
        host.scope.launch(Dispatchers.IO) {
            val removed = skillManager.remove(name)
            host.updateState { it.copy(skills = skillManager.installed(), skillsMessage = if (removed) "Removed " + name else "Skill was not installed.") }
        }
    }

    fun toggleAutoUpdateToolsAndSkills(enabled: Boolean) {
        preferences.autoUpdateToolsAndSkills = enabled
        host.updateState { it.copy(autoUpdateToolsAndSkills = enabled) }
        if (enabled) {
            autoUpdateToolsAndSkills(force = true)
        }
    }

    fun autoUpdateToolsAndSkills(force: Boolean = false) {
        if (!preferences.autoUpdateToolsAndSkills) return
        val current = host.state()
        if (current.isRunning || current.projectTerminalRunning || current.devStackInstalling != null || current.agentUpdating != null || current.autoUpdatingToolsOrSkills) return
        val now = System.currentTimeMillis()
        if (!force && (now - preferences.lastToolsAutoUpdateCheckMillis < 4L * 60L * 60L * 1000L)) return

        host.scope.launch {
            host.updateState { it.copy(autoUpdatingToolsOrSkills = true, toolsAutoUpdateMessage = "Checking for tool and skill updates…") }
            val updatedItems = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                // 1. Universal Skills auto-update
                runCatching {
                    val updates = skillManager.checkUpdates()
                    if (updates.isNotEmpty()) {
                        updates.forEach { updateInfo ->
                            val skill = skillManager.installed().firstOrNull { it.name == updateInfo.name }
                            if (skill != null) {
                                runCatching {
                                    skillManager.update(skill)
                                    updatedItems.add("Skill ${skill.name}")
                                }
                            }
                        }
                    }
                }

                // 2. Agents / Coding CLI Tools auto-update
                runCatching {
                    val agentUpdates = installer.checkAgentUpdates()
                    agentUpdates.forEach { (kind, updateInfo) ->
                        if (!host.state().isRunning && host.state().agentUpdating == null) {
                            runCatching {
                                installer.updateAgent(kind, updateInfo.latestVersion) {}
                                updatedItems.add("${kind.title} ${updateInfo.latestVersion}")
                            }
                        }
                    }
                }

                // 3. Installed Development Toolchains auto-update
                runCatching {
                    val installed = installer.installedStacks().filter { it != DevStack.WEB }
                    installed.forEach { stack ->
                        if (!host.state().isRunning && host.state().devStackInstalling == null) {
                            runCatching {
                                var didUpdate = false
                                installer.updateStack(stack) { progress ->
                                    if (progress.event == RuntimeInstallEvent.COMPLETED &&
                                        !progress.message.contains("already up to date", ignoreCase = true) &&
                                        !progress.message.contains("tools are up to date", ignoreCase = true)) {
                                        didUpdate = true
                                    }
                                }
                                if (didUpdate) {
                                    updatedItems.add(stack.label)
                                }
                            }
                        }
                    }
                }
            }

            preferences.lastToolsAutoUpdateCheckMillis = System.currentTimeMillis()
            val message = if (updatedItems.isEmpty()) {
                "Tools, toolchains, and skills are up to date"
            } else {
                "Updated: ${updatedItems.joinToString(", ")}"
            }
            host.updateState {
                it.copy(
                    autoUpdatingToolsOrSkills = false,
                    toolsAutoUpdateMessage = message,
                    skills = skillManager.installed(),
                    skillUpdates = emptySet(),
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
        }
    }
}
