package com.codeair.mhe.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.mhe.ui.theme.PocketGreen
import com.codeair.mhe.ui.theme.PocketOrange
import kotlinx.coroutines.launch

data class OnboardingTourSlide(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val phaseTag: String,
    val highlights: List<String>,
    val badges: List<String>,
    val quote: String,
)

/**
 * Comprehensive Product Onboarding & Feature Tour for v3.0.0.
 * Highlights all 14 engineering phases and architectural breakthroughs.
 */
@Composable
fun ProductOnboardingScreen(
    onComplete: () -> Unit,
) {
    val slides = listOf(
        OnboardingTourSlide(
            title = "Desktop-Grade Mobile IDE",
            subtitle = "Full Linux userspace powered by PRoot on Android",
            icon = Icons.Default.Terminal,
            phaseTag = "CORE ARCHITECTURE",
            highlights = listOf(
                "Real Ubuntu 20.04 ARM64 userspace inside PRoot — zero root required",
                "Full terminal with bash, zsh, coreutils, Node.js, Python, Git, and Clang",
                "Monaco-class code editor with syntax tree, tabs, multi-cursor, and undo stack",
                "Autonomous local verification: runs builds & tests directly on-device",
            ),
            badges = listOf("PRoot Linux", "No Root", "Native ARM64", "Full Terminal"),
            quote = "Your Android phone is now a complete desktop-grade development workstation.",
        ),
        OnboardingTourSlide(
            title = "Multi-Agent Engineering Team",
            subtitle = "10 Specialized autonomous agents collaborating in real-time",
            icon = Icons.Default.Groups,
            phaseTag = "PHASES 1-3 & 10-11",
            highlights = listOf(
                "10 Specialized roles: Manager, Architect, Frontend, Backend, QA, DevOps, Security, DBA, Docs, UX",
                "Hierarchical orchestration: Manager breaks down goals into milestones and task DAGs",
                "Iterative peer review loops: QA and Security review code diffs before merge",
                "Budget control, milestone tracking, and dynamic task replanning",
            ),
            badges = listOf("10 Specialized Roles", "Task DAG", "Automated QA", "Security Auditor"),
            quote = "Deploy an entire engineering department directly inside a single project workspace.",
        ),
        OnboardingTourSlide(
            title = "Autonomous Issue-to-PR Engine",
            subtitle = "From GitHub Issue triage to tested pull request automatically",
            icon = Icons.Default.RocketLaunch,
            phaseTag = "PHASES 4-7",
            highlights = listOf(
                "Native GitHub integration with gh CLI, OAuth token auth, and repo context indexing",
                "Autonomous triage: analyzes issues, reproduces errors, checks out feature branches",
                "Multi-file atomic code edits with syntax verification and test assertions",
                "Self-healing compile loops: automatically re-runs tests until 100% pass, then pushes PR",
            ),
            badges = listOf("GitHub Native", "Self-Healing", "Issue Triage", "Signed PRs"),
            quote = "Submit a bug report or feature request and watch the agent code, test, and ship the PR.",
        ),
        OnboardingTourSlide(
            title = "Cloud + Phone Hybrid Engine",
            subtitle = "Adaptive workload offloading with thermal & battery intelligence",
            icon = Icons.Default.CloudQueue,
            phaseTag = "PHASE 12",
            highlights = listOf(
                "Smart decision matrix: factors in battery level, thermal throttle, network, and RAM",
                "Lightweight code editing & fast unit tests remain snappy on the local device",
                "Heavy Gradle builds, docker compilation, and LLM inference offload to remote runners",
                "FastBuild artifact caching and seamless synchronization back to phone workspace",
            ),
            badges = listOf("Battery Aware", "Thermal Routing", "Remote Runners", "FastBuild Cache"),
            quote = "Save device battery and thermals by seamlessly offloading intensive builds to the cloud.",
        ),
        OnboardingTourSlide(
            title = "Visual DevTools & DOM Inspector",
            subtitle = "Embedded browser runtime with live DOM manipulation and inspection",
            icon = Icons.Default.Language,
            phaseTag = "PHASE 8",
            highlights = listOf(
                "Embedded Chromium browser with responsive viewport presets (Mobile, Tablet, Desktop)",
                "Interactive DOM element picker, computed CSS inspector, and live console logs",
                "Active localhost port auto-discovery and instant reverse proxy",
                "Direct screenshot & DOM snippet attachment into the agent prompt context",
            ),
            badges = listOf("Live DOM", "CSS Inspector", "Localhost Proxy", "Console Logs"),
            quote = "Inspect, test, and debug frontend web apps without leaving the PocketDev IDE.",
        ),
        OnboardingTourSlide(
            title = "Android App Factory v2.0",
            subtitle = "Production app scaffolding, native compilation, and Play Store prep",
            icon = Icons.Default.Build,
            phaseTag = "PHASE 9",
            highlights = listOf(
                "AI Blueprint Engine: decomposes prompts into production UI layouts and navigation models",
                "Multi-framework generators: Jetpack Compose, Flutter, React Native, and HTML5 WebApps",
                "Automated Gradle daemon build execution and debug APK package output",
                "Release pipeline: keystore generation, version bumping, and Play Store asset bundles",
            ),
            badges = listOf("Compose + Flutter", "APK Build", "Keystore Sign", "Play Store Ready"),
            quote = "Turn a single idea prompt into a fully compiled Android APK ready for release.",
        ),
        OnboardingTourSlide(
            title = "Marketplace & Team Collaboration",
            subtitle = "1-click developer registry & real-time workspace collaboration",
            icon = Icons.Default.Storefront,
            phaseTag = "PHASES 13-14",
            highlights = listOf(
                "AI Developer Marketplace: 1-click install for Agents, MCP Servers, Skills, and Templates",
                "Custom package registry: publish and share tools directly with your team",
                "Role-based access control (RBAC): Owner, Admin, Member, and Viewer permissions",
                "Collaborative workspaces: code comments, live review approvals, and activity audit trails",
            ),
            badges = listOf("MCP Servers", "Custom Skills", "RBAC Security", "Audit Trails"),
            quote = "Scale your development with vetted AI tools, MCP integrations, and team workflows.",
        ),
    )

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Header: App Branding + Skip Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "v3.0.0",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "PocketDev Tour",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                TextButton(onClick = onComplete) {
                    Text("Skip", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Carousel Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) { page ->
                val slide = slides[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))

                    // Phase Tag Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = slide.phaseTag,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp,
                        )
                    }

                    // Icon Hero
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(76.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                                tint = PocketOrange,
                            )
                        }
                    }

                    // Title & Subtitle
                    Text(
                        text = slide.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = slide.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    // Highlights Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            slide.highlights.forEach { highlight ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .padding(top = 2.dp),
                                        tint = PocketGreen,
                                    )
                                    Text(
                                        text = highlight,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    // Badges Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        slide.badges.forEach { badge ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = badge,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Quote / Insight
                    Text(
                        text = "“${slide.quote}”",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )

                    Spacer(Modifier.height(8.dp))
                }
            }

            // Bottom Navigation & Page Indicator
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Dot Indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(slides.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (isSelected) 22.dp else 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isSelected) PocketOrange else MaterialTheme.colorScheme.outlineVariant
                                ),
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Previous", fontSize = 13.sp)
                        }
                    }

                    val isLastPage = pagerState.currentPage == slides.lastIndex
                    Button(
                        onClick = {
                            if (isLastPage) {
                                onComplete()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text = if (isLastPage) "Get Started" else "Next (${pagerState.currentPage + 1}/${slides.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
