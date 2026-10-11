package com.jarves.mh.terminal

/**
 * Pure terminal command policy. Kept separate from UI state so destructive-command
 * detection, package-manager interaction rules and URL parsing can be tested directly.
 */
object TerminalCommandPolicy {

    /**
     * Package management must never block on Y/N, locale, timezone, service-restart,
     * or config-file dialogs in the phone UI. Other commands remain interactive.
     */
    fun prepareInteractiveShellCommand(command: String): String {
        val normalizedApt = command
            .replace(Regex("(?<![\\w-])sudo\\s+apt(?:-get)?\\s+"), "apt-get ")
            .replace(Regex("(?<![\\w-])apt\\s+"), "apt-get ")
            .replace(
                Regex("(?<![\\w-])apt-get\\s+(install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|fix-broken)\\b"),
                "apt-get -y -o Dpkg::Options::=--force-confold \$1",
            )
        return "export DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a TZ=Etc/UTC LC_ALL=C.UTF-8; $normalizedApt"
    }

    fun shouldAutoConfirmPackageCommand(command: String, output: String): Boolean {
        val packageCommand = Regex("(?i)(^|[;&|]\\s*)(sudo\\s+)?(apt|apt-get|dpkg)\\b").containsMatchIn(command)
        if (!packageCommand) return false
        val tail = output.takeLast(500)
        return Regex("(?i)(do you want to continue|continue\\?)\\s*\\[[Yy]/[Nn]\\]").containsMatchIn(tail)
    }

    fun isDestructiveTerminalCommand(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "rm -rf", "rm -fr", "git reset --hard", "git clean -f", "git push --force",
            "mkfs", "dd if=", "chmod -r 777", "shutdown", "reboot", ":(){", "kill \$(pgrep", "pkill -f",
        ).any(normalized::contains) || Regex("(curl|wget).*(\\||>)\\s*(sh|bash)").containsMatchIn(normalized)
    }

    fun detectPreviewUrl(output: String): String? {
        val match = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d{2,5})(?:/[^\\s]*)?")
            .findAll(output)
            .lastOrNull()
            ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return "http://127.0.0.1:$port/"
    }

    fun detectServerUrl(command: String): String? {
        val match = Regex("""python(?:3)?\\s+-m\\s+http\\.server(?:\\s+(\\d{2,5}))?""")
            .find(command)
            ?: return null
        val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8000
        return port.takeIf { it in 1..65535 }?.let { "http://127.0.0.1:$it/" }
    }

    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(command)
}
