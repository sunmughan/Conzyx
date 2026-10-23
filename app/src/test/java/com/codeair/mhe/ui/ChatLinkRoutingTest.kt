package com.codeair.mhe.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat link routing (v3.4.10): workspace file references must open inside
 * the app and never escape to a browser as file:// web URLs; bare domains
 * must become real tappable links.
 */
class ChatLinkRoutingTest {

    @Test
    fun normalizeKeepsFileAndWorkspaceUrlsUntouched() {
        assertEquals(
            "file://workspace/resources/views/services.blade.php",
            normalizeMarkdownUrl("file://workspace/resources/views/services.blade.php"),
        )
        assertEquals(
            "/workspace/app/Models/User.php",
            normalizeMarkdownUrl("/workspace/app/Models/User.php"),
        )
        assertEquals("https://example.com/a", normalizeMarkdownUrl("https://example.com/a"))
        assertEquals("https://krithikasalon.in", normalizeMarkdownUrl("krithikasalon.in"))
    }

    @Test
    fun fileUrlsClassifyToWorkspaceFilesWithProjectRelativePaths() {
        assertEquals(
            ChatLinkTarget.WorkspaceFile("resources/views/services.blade.php", false),
            classifyChatLink("file://workspace/resources/views/services.blade.php"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("app/Models/User.php", false),
            classifyChatLink("file:///workspace/app/Models/User.php"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("config/app.php", false),
            classifyChatLink("/workspace/config/app.php"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("a/b.txt", false),
            classifyChatLink("file://localhost/workspace/a/b.txt"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("src/main.py", false),
            classifyChatLink("/root/workspace/src/main.py"),
        )
    }

    @Test
    fun archivesClassifyAsWorkspaceFilesButFlaggedArchive() {
        assertEquals(
            ChatLinkTarget.WorkspaceFile("backup/site.zip", true),
            classifyChatLink("/workspace/backup/site.zip"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("release/app-release.apk", true),
            classifyChatLink("file://workspace/release/app-release.apk"),
        )
    }

    @Test
    fun webLinksClassifyAsWeb() {
        assertEquals(
            ChatLinkTarget.Web("https://krithikasalon.in"),
            classifyChatLink("https://krithikasalon.in"),
        )
        assertEquals(
            ChatLinkTarget.Web("https://krithikasalon.in"),
            classifyChatLink("krithikasalon.in"),
        )
        assertEquals(
            ChatLinkTarget.Web("https://www.example.com/pricing"),
            classifyChatLink("www.example.com/pricing"),
        )
    }

    @Test
    fun bareFileNamesAndRelativePathsClassifyAsWorkspaceFiles() {
        assertEquals(
            ChatLinkTarget.WorkspaceFile("services.blade.php", false),
            classifyChatLink("services.blade.php"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("app/Models/User.php", false),
            classifyChatLink("app/Models/User.php"),
        )
        assertEquals(
            ChatLinkTarget.WorkspaceFile("README.md", false),
            classifyChatLink("README.md"),
        )
    }

    @Test
    fun blankLinkIsIgnored() {
        assertEquals(ChatLinkTarget.Ignored, classifyChatLink("   "))
        assertEquals(ChatLinkTarget.Ignored, classifyChatLink("file://workspace/"))
    }

    @Test
    fun bareDomainDetectionSkipsFileNamesAndVersions() {
        val sentence = "Site is live at krithikasalon.in for review."
        val start = sentence.indexOf("krithikasalon.in")
        assertEquals(
            start + "krithikasalon.in".length,
            bareDomainEnd(sentence, start),
        )
        // File names are not web domains.
        val fileSentence = "Edited services.blade.php and app.css today"
        assertEquals(-1, bareDomainEnd(fileSentence, fileSentence.indexOf("services.blade.php")))
        assertEquals(-1, bareDomainEnd(fileSentence, fileSentence.indexOf("app.css")))
        // Version numbers are not web domains.
        val versionSentence = "Running version 3.4.10 now"
        assertEquals(-1, bareDomainEnd(versionSentence, versionSentence.indexOf("3.4.10")))
        // An email address is not linkified mid-token.
        val emailSentence = "Mail me at dev@krithikasalon.in please"
        assertEquals(-1, bareDomainEnd(emailSentence, emailSentence.indexOf("krithikasalon")))
    }

    @Test
    fun bareDomainInParagraphBecomesTappableLinkButFileNameDoesNot() {
        val annotated = buildMarkdownAnnotatedString(
            text = "Live at krithikasalon.in — updated services.blade.php and see https://example.com/x",
            primaryColor = Color.Blue,
            codeColor = Color.Red,
            codeBg = Color.Gray,
            onOpenUri = {},
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
            .map { (it.item as LinkAnnotation.Url).url }
        assertTrue("bare domain linkified: $links", "https://krithikasalon.in" in links)
        assertTrue("explicit https link kept: $links", "https://example.com/x" in links)
        assertTrue(
            "file name never became a web link: $links",
            links.none { it.contains("services.blade.php") },
        )
    }

    @Test
    fun markdownFileLinkSurvivesNormalizationIntoTheClassifier() {
        // The exact v3.4.9 failure chain: a markdown file link was normalized
        // into an https:// URL before the tap handler saw it, so it escaped
        // to Chrome. The normalized form must still classify as a file.
        val normalized = normalizeMarkdownUrl("file://workspace/resources/views/services.blade.php")
        assertEquals(
            ChatLinkTarget.WorkspaceFile("resources/views/services.blade.php", false),
            classifyChatLink(normalized),
        )
        val normalizedPath = normalizeMarkdownUrl("/workspace/app/Models/User.php")
        assertEquals(
            ChatLinkTarget.WorkspaceFile("app/Models/User.php", false),
            classifyChatLink(normalizedPath),
        )
    }
}
