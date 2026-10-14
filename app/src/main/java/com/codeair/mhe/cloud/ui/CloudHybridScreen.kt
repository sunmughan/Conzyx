@file:OptIn(ExperimentalLayoutApi::class)

package com.codeair.mhe.cloud.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeair.mhe.cloud.CloudHybridCoordinator
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import com.codeair.mhe.cloud.model.ExecutionTarget
import com.codeair.mhe.cloud.model.HybridExecutionJob
import com.codeair.mhe.cloud.model.HybridJobState
import com.codeair.mhe.cloud.model.WorkerHealthStatus
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType
import kotlinx.coroutines.delay

/**
 * Cloud + Phone Hybrid Studio Screen (Phase 12 v2.3).
 * Visual dashboard managing workload routing, worker telemetry, live job pipelines, and policies.
 */
@Composable
fun CloudHybridScreen(
    coordinator: CloudHybridCoordinator,
    onClose: () -> Unit = {}
) {
    val state by coordinator.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Telemetry was NOT realtime: the coordinator reads device metrics only
    // once in init, so this screen showed a stale snapshot (e.g. battery 5%
    // when the phone was already at 13%). Re-read while the screen is open:
    // immediately on entry, then every 30s. The loop is cancelled
    // automatically when the screen closes; the reads are cheap OS queries.
    LaunchedEffect(Unit) {
        while (true) {
            coordinator.refreshDeviceMetrics()
            delay(30_000)
        }
    }

    val tabs = listOf("Telemetry & Stats", "Workers Fleet", "Job Pipeline", "Routing Policies")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Top Header
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Cloud Hybrid Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Phase 12 - Phone + Cloud Workload Routing",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }

        // Tab Navigation
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            when (selectedTab) {
                0 -> HybridTelemetryTab(coordinator = coordinator)
                1 -> HybridWorkersTab(coordinator = coordinator)
                2 -> HybridJobsPipelineTab(coordinator = coordinator)
                3 -> HybridPoliciesTab(coordinator = coordinator)
            }
        }
    }
}

/**
 * Tab 1: Hardware Telemetry and Execution Statistics.
 */
@Composable
private fun HybridTelemetryTab(coordinator: CloudHybridCoordinator) {
    val state by coordinator.state.collectAsState()
    val metrics = state.deviceMetrics

    // Sticky bottom primary action bar: Build APK stays reachable while scrolling.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Device Health Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Phone Hardware Telemetry (PRoot)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))

                // RAM Gauge: real readings only. Before the first real collection
                // (metrics.collected == false) show placeholders, never defaults.
                Text(
                    text = if (metrics.collected) "RAM Available: ${metrics.availableRamMb} MB / ${metrics.totalRamMb} MB (${metrics.ramUsagePercent}% used)" else "RAM Available: Collecting real readings...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (metrics.collected) (metrics.ramUsagePercent.toFloat() / 100f).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = if (metrics.collected && metrics.availableRamMb < 1500L) Color(0xFFF57C00) else MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(12.dp))
                // Quick Badges Row (uniform pills, wraps on narrow screens)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryChip(
                        icon = Icons.Default.BatteryChargingFull,
                        label = if (metrics.collected) "${metrics.batteryLevelPercent}% ${if (metrics.isCharging) "Charging" else "Battery"}" else "Battery: Collecting...",
                        color = if (metrics.collected && metrics.batteryLevelPercent > 20) Color(0xFF2E7D32) else if (metrics.collected) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TelemetryChip(
                        icon = Icons.Default.Wifi,
                        label = if (!metrics.collected) "Network: Collecting..." else if (metrics.isWifiConnected) "Wi-Fi" else "Cellular",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Hybrid Execution Split
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Workload Offloading Balance",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBox(
                        title = "Local Tasks",
                        value = "${state.totalLocalJobs}",
                        subtitle = "On-Device",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primaryContainer
                    )
                    MetricBox(
                        title = "Cloud Offloaded",
                        value = "${state.totalOffloadedJobs}",
                        subtitle = "Remote Runners",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    )
                    MetricBox(
                        title = "Cloud Spend",
                        value = "$${state.totalCloudSpendUsd}",
                        subtitle = "USD total",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    )
                }
            }
        }

        // Quick Workload Trigger Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Test run: watch a task route between phone and cloud",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Dispatch benchmark workloads to observe dynamic routing in action.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coordinator.submitExecutionJob(
                                title = "Run 420 Unit Tests",
                                workloadType = WorkloadType.LARGE_TEST_SUITE
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text("Test Suite", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            coordinator.submitExecutionJob(
                                title = "Synthesize Models",
                                workloadType = WorkloadType.AI_CODE_SYNTHESIS
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text("GPU LLM", fontSize = 11.sp)
                    }
                }
            }
        }
        }
        // Sticky bottom primary action
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Button(
                onClick = {
                    coordinator.submitExecutionJob(
                        title = "Assemble Release APK",
                        workloadType = WorkloadType.HEAVY_GRADLE_BUILD
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Build APK", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Tab 2: Registered Cloud Worker Fleet.
 */
@Composable
private fun HybridWorkersTab(coordinator: CloudHybridCoordinator) {
    val state by coordinator.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Keep the heading on its own line and place Add Runner before Ping All.
        // A wrapping action row avoids the vertical, character-by-character button seen on phones.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Cloud Runner Fleet (${state.registeredWorkers.size} registered)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("+ Add Runner", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = { coordinator.pingWorkers() },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ping All", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }

        if (showAddDialog) {
            AddWorkerCard(
                onAdd = { name, url, region, gpu, cost ->
                    coordinator.registerCustomWorker(name, url, region, gpu, cost)
                    showAddDialog = false
                },
                onCancel = { showAddDialog = false }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.registeredWorkers.isEmpty()) {
                item {
                    Text(
                        text = "No cloud runners registered. Add your own runner with + Add Runner, then Ping All to verify it really responds.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            items(state.registeredWorkers, key = { it.id }) { worker ->
                WorkerCard(worker = worker)
            }
        }
    }
}

@Composable
private fun WorkerCard(worker: CloudWorkerSpec) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // Medium: the URL text used to squeeze the health pill
                    // off the row. The text column now takes the leftover
                    // width and ellipsizes instead.
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (worker.gpuAvailable) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (worker.gpuAvailable) Icons.Default.Speed else Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column {
                        Text(text = worker.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(text = "${worker.region} • ${worker.endpointUrl}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    // Medium: hardcoded green/orange pill + white text was
                    // low-contrast in light mode; theme containers pair
                    // correctly per theme.
                    color = if (worker.healthStatus == WorkerHealthStatus.HEALTHY) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (worker.healthStatus == WorkerHealthStatus.HEALTHY) "${worker.pingLatencyMs}ms" else worker.healthStatus.name,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (worker.healthStatus == WorkerHealthStatus.HEALTHY) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChipBadge("${worker.cpuCores} Cores")
                ChipBadge("${worker.ramGb} GB RAM")
                if (worker.gpuAvailable) ChipBadge("NVIDIA GPU")
                ChipBadge("$${worker.costPerHourUsd}/hr")
            }
        }
    }
}

/**
 * QA Medium: validates an Add-Runner endpoint URL (format only — it cannot
 * prove the host exists). Requires an http(s) scheme and a host, so a
 * bare hostname, a typo'd scheme, or an empty value never registers.
 */
internal fun isValidRunnerUrl(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return false
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    if (uri.host.isNullOrBlank()) return false
    return true
}

@Composable
private fun AddWorkerCard(
    onAdd: (String, String, String, Boolean, Double) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("Self-Hosted VPS Runner") }
    // Medium: the URL used to be prefilled with a placeholder endpoint
    // (https://vps.mobileharness-extended.io:8080) that likely does not
    // exist — one tap registered a dead host. It now starts empty and
    // Register stays disabled until a real http(s) URL is entered.
    var url by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("custom-vps") }
    var isGpu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Register New Cloud Runner Node", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Runner Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Endpoint URL") },
                placeholder = { Text("https://your-vps:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = url.isNotBlank() && !isValidRunnerUrl(url),
            )
            if (url.isNotBlank() && !isValidRunnerUrl(url)) {
                Text(
                    "Enter a valid http(s) endpoint URL, e.g. https://your-vps:8080",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Medium: the two buttons overflowed on narrow phones.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAdd(name, url.trim(), region, isGpu, 0.04) },
                    enabled = name.isNotBlank() && isValidRunnerUrl(url),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Register Runner")
                }
                OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Tab 3: Hybrid Execution Jobs Pipeline & Logs.
 */
@Composable
private fun HybridJobsPipelineTab(coordinator: CloudHybridCoordinator) {
    val state by coordinator.state.collectAsState()
    val allJobs = state.activeJobs + state.completedJobs

    if (allJobs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Cloud jobs appear here after you run a task. Trigger one from the Telemetry tab to see routing in action.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allJobs, key = { it.id }) { job ->
                JobCard(job = job)
            }
        }
    }
}

@Composable
private fun JobCard(job: HybridExecutionJob) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (job.state == HybridJobState.COMPLETED) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (job.routingDecision?.target == ExecutionTarget.CLOUD) Color(0xFF1976D2) else Color(0xFF388E3C)
                    ) {
                        Text(
                            text = if (job.routingDecision?.target == ExecutionTarget.CLOUD) "CLOUD" else "LOCAL",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(text = job.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (job.state) {
                        HybridJobState.COMPLETED -> Color(0xFF2E7D32)
                        HybridJobState.FAILED -> Color(0xFFD32F2F)
                        // Honest states: never green. NOT_CONFIGURED is amber, SIMULATED is grey.
                        HybridJobState.NOT_CONFIGURED -> Color(0xFFEF6C00)
                        else -> MaterialTheme.colorScheme.primary
                    }
                ) {
                    Text(
                        text = job.state.name,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            job.routingDecision?.reason?.let { reason ->
                Text(text = reason, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (job.producedArtifacts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    job.producedArtifacts.forEach { art ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                                Text(art.filename, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }

            if (job.outputLogs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (expanded) "Hide Logs" else "Show Output Logs (${job.outputLogs.size})", fontSize = 10.sp)
                }

                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1E1E1E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            job.outputLogs.forEach { line ->
                                Text(
                                    text = line,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF81C784)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 4: Workload Routing Policies.
 */
@Composable
private fun HybridPoliciesTab(coordinator: CloudHybridCoordinator) {
    val state by coordinator.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Workload Routing Policy",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Select how Mobile Harness Extended balances on-device battery/thermals against cloud execution speed.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        WorkloadRoutingPolicy.entries.forEach { policy ->
            val isSelected = state.routingPolicy == policy
            Card(
                onClick = { coordinator.setPolicy(policy) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = policy.displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = policy.description,
                            fontSize = 11.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MetricBox(title: String, value: String, subtitle: String, modifier: Modifier = Modifier, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.4f),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChipBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
