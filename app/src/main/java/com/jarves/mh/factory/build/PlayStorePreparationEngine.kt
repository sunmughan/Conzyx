package com.jarves.mh.factory.build

import com.jarves.mh.factory.model.AppBlueprint
import com.jarves.mh.factory.model.StoreListingBundle
import com.jarves.mh.workspace.SafeFileOps
import java.io.File

data class ComplianceAuditItem(
    val title: String,
    val passed: Boolean,
    val detail: String,
)

/** Generates bounded Play Store listing files and a transparent local compliance report. */
object PlayStorePreparationEngine {
    fun prepareListingBundle(projectDir: File, blueprint: AppBlueprint): StoreListingBundle {
        val listingDir = File(projectDir, "play-store-listing")
        check(listingDir.isDirectory || listingDir.mkdirs()) { "Could not create listing directory" }

        val title = (blueprint.playStoreMetadata.title.ifBlank { blueprint.name }).trim().take(30)
        val shortDescription = blueprint.playStoreMetadata.shortDescription.trim().take(80)
        val fullDescription = blueprint.playStoreMetadata.fullDescription.trim().take(4_000)
        val titleFile = File(listingDir, "title.txt")
        val shortDescFile = File(listingDir, "short-description.txt")
        val fullDescFile = File(listingDir, "full-description.txt")
        val privacyPolicyFile = File(listingDir, "privacy-policy.md")
        val complianceReportFile = File(listingDir, "compliance-report.txt")

        val privacyPolicy = """
            # Privacy Policy for ${blueprint.name}

            This policy describes how ${blueprint.name} handles information when you use the application.
            The app should collect only information required for its stated features and should explain
            any data sharing, retention, security and deletion practices to users before release.

            ## Data and permissions
            Document the data collected by each enabled feature and request permissions only when needed.
            Keep credentials and private keys out of source control and diagnostic logs.

            ## Contact Us
            Contact: ${blueprint.playStoreMetadata.contactEmail}
        """.trimIndent() + "\n"

        val audit = runComplianceAudit(blueprint)
        val report = buildString {
            appendLine("Play Store Compliance Audit")
            appendLine("App: ${blueprint.name}")
            appendLine("Target SDK 34+: ${if (blueprint.buildConfig.targetSdk >= 34) "PASS" else "FAIL"}")
            audit.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"} — ${it.title}: ${it.detail}") }
            appendLine(if (audit.all { it.passed }) "PASS" else "FAIL")
            appendLine("This report is a local preflight checklist, not a guarantee of Play Console approval.")
        }

        val writes = listOf(
            titleFile to title,
            shortDescFile to shortDescription,
            fullDescFile to fullDescription,
            privacyPolicyFile to privacyPolicy,
            complianceReportFile to report,
        )
        writes.forEach { (file, text) ->
            check(SafeFileOps.atomicWriteText(file, text)) { "Could not write ${file.name}" }
        }
        return StoreListingBundle(listingDir, titleFile, shortDescFile, fullDescFile, privacyPolicyFile, complianceReportFile)
    }

    fun runComplianceAudit(blueprint: AppBlueprint): List<ComplianceAuditItem> = listOf(
        ComplianceAuditItem(
            title = "Target SDK 34 or newer",
            passed = blueprint.buildConfig.targetSdk >= 34,
            detail = "Configured target SDK is ${blueprint.buildConfig.targetSdk}",
        ),
        ComplianceAuditItem(
            title = "Valid Android package name",
            passed = blueprint.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")),
            detail = blueprint.packageName,
        ),
        ComplianceAuditItem(
            title = "App title is present",
            passed = blueprint.name.isNotBlank(),
            detail = if (blueprint.name.isNotBlank()) "Title is present" else "Title is empty",
        ),
        ComplianceAuditItem(
            title = "Privacy policy contact is present",
            passed = blueprint.playStoreMetadata.contactEmail.isNotBlank(),
            detail = blueprint.playStoreMetadata.contactEmail,
        ),
    )
}
