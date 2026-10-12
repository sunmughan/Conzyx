package com.jarves.mh.factory.build

import com.jarves.mh.factory.model.AppBlueprint
import com.jarves.mh.factory.model.StoreListingBundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real Play Store listing preparation (v3).
 *
 * Generates the actual submission text files from the [AppBlueprint] into
 * `<projectDir>/play-listing/`. Content is derived from the blueprint —
 * nothing is hardcoded filler beyond clearly-marked placeholders the
 * developer must replace before submission.
 */
object PlayStorePreparationEngine {

    fun prepareListingBundle(projectDir: File, blueprint: AppBlueprint): StoreListingBundle {
        val listingDir = File(projectDir, "play-listing").apply { mkdirs() }
        val meta = blueprint.playStoreMetadata

        val titleFile = File(listingDir, "title.txt").apply {
            writeText((meta.title.ifBlank { blueprint.name }).take(30))
        }
        val shortDescFile = File(listingDir, "short-description.txt").apply {
            writeText((meta.shortDescription.ifBlank { blueprint.description }).take(80))
        }
        val fullDescFile = File(listingDir, "full-description.txt").apply {
            writeText(
                meta.fullDescription.ifBlank {
                    "${blueprint.name}\n\n${blueprint.description}\n\n" +
                        "Built with Mobile Harness App Factory."
                }.take(4000)
            )
        }
        val privacyPolicyFile = File(listingDir, "privacy-policy.md").apply {
            writeText(
                meta.privacyPolicyUrl.ifBlank {
                    "# Privacy Policy — ${blueprint.name}\n\n" +
                        "[TODO: replace with your published privacy policy URL before submitting " +
                        "to Google Play. A privacy policy URL is required for store listing.]"
                }
            )
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val complianceReportFile = File(listingDir, "compliance-report.md").apply {
            writeText(
                """
                # Play Store Compliance Checklist — ${blueprint.name}
                Generated: $date
                Package: ${blueprint.packageName}

                - [ ] Target SDK meets current Google Play requirements
                - [ ] Privacy policy URL is live and accessible
                - [ ] Data safety form completed in Play Console
                - [ ] App content rating questionnaire completed
                - [ ] Release signed with the upload key
                - [ ] versionCode is higher than the previous release

                [TODO: verify each item in the Play Console before submitting.]
                """.trimIndent()
            )
        }
        return StoreListingBundle(
            listingDir = listingDir,
            titleFile = titleFile,
            shortDescFile = shortDescFile,
            fullDescFile = fullDescFile,
            privacyPolicyFile = privacyPolicyFile,
            complianceReportFile = complianceReportFile
        )
    }
}
