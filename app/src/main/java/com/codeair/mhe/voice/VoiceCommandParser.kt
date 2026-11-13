package com.codeair.mhe.voice

/**
 * Phase 5: voice commands.
 *
 * Speech recognition already dictates into the composer; this parser
 * lifts control phrases out of the transcript so a spoken "stop" stops
 * the running task instead of being sent to the agent as a prompt.
 * Matching is a normalized exact or leading-phrase match, so dictation
 * can never accidentally fire a command mid-sentence.
 */
sealed interface VoiceCommand {
    data object StopTask : VoiceCommand
    data object ResumeSession : VoiceCommand
    data object OpenCommandCenter : VoiceCommand
    data object ShowStatus : VoiceCommand
    data class Dictate(val text: String) : VoiceCommand
}

object VoiceCommandParser {
    private val commands: List<Pair<List<String>, VoiceCommand>> = listOf(
        listOf("stop the task", "stop task", "cancel the task", "cancel task", "stop") to
            VoiceCommand.StopTask,
        listOf("resume the session", "resume session", "resume the task", "resume task", "resume") to
            VoiceCommand.ResumeSession,
        listOf("open command center", "open the command center", "show command center", "command center") to
            VoiceCommand.OpenCommandCenter,
        listOf("show status", "what is the status", "whats the status", "status") to
            VoiceCommand.ShowStatus,
    )

    /**
     * Parses a speech transcript. Anything that is not a command phrase
     * comes back as [VoiceCommand.Dictate] with the original text.
     */
    fun parse(transcript: String): VoiceCommand {
        val normalized = transcript.trim().lowercase()
            .replace("'", "")
            .replace(Regex("[.,!?;:]+$"), "")
            .replace(Regex("\\s+"), " ")
        for ((phrases, command) in commands) {
            for (phrase in phrases) {
                if (normalized == phrase || normalized.startsWith("$phrase ")) return command
            }
        }
        return VoiceCommand.Dictate(transcript.trim())
    }
}
