package com.codeair.mhe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun `stop phrases stop the task`() {
        assertEquals(VoiceCommand.StopTask, VoiceCommandParser.parse("stop"))
        assertEquals(VoiceCommand.StopTask, VoiceCommandParser.parse("Stop the task"))
        assertEquals(VoiceCommand.StopTask, VoiceCommandParser.parse("stop the task please"))
        assertEquals(VoiceCommand.StopTask, VoiceCommandParser.parse("cancel task."))
    }

    @Test
    fun `resume phrases resume the session`() {
        assertEquals(VoiceCommand.ResumeSession, VoiceCommandParser.parse("resume"))
        assertEquals(VoiceCommand.ResumeSession, VoiceCommandParser.parse("Resume session"))
    }

    @Test
    fun `command center phrases open it`() {
        assertEquals(VoiceCommand.OpenCommandCenter, VoiceCommandParser.parse("open command center"))
        assertEquals(VoiceCommand.OpenCommandCenter, VoiceCommandParser.parse("command center"))
    }

    @Test
    fun `status phrases show status`() {
        assertEquals(VoiceCommand.ShowStatus, VoiceCommandParser.parse("status"))
        assertEquals(VoiceCommand.ShowStatus, VoiceCommandParser.parse("What's the status?"))
        assertEquals(VoiceCommand.ShowStatus, VoiceCommandParser.parse("show status"))
    }

    @Test
    fun `anything else is dictation with original text`() {
        val parsed = VoiceCommandParser.parse("  Fix the login bug. ")
        assertTrue(parsed is VoiceCommand.Dictate)
        assertEquals("Fix the login bug.", (parsed as VoiceCommand.Dictate).text)
        assertEquals(
            VoiceCommand.Dictate("add a settings screen"),
            VoiceCommandParser.parse("add a settings screen"),
        )
    }
}
