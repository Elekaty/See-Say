package com.seeandsay.chronologer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seeandsay.chronologer.data.NarrationLog
import com.seeandsay.chronologer.engine.CameraSourceMode
import kotlin.math.abs
import kotlin.math.roundToInt

private val ChronologerScheme = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF10203A),
    secondary = Color(0xFF3DDC97),
    onSecondary = Color(0xFF052216),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2330),
    onSurfaceVariant = Color(0xFF9AA8B8),
    outline = Color(0xFF2E3A4C),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2A0C0C),
)

private val CodeBackground = Color(0xFF0B0E14)
private val CodeForeground = Color(0xFFD6E2F0)
private val DevicePalette = listOf(
    Color(0xFF7C9CFF),
    Color(0xFF3DDC97),
    Color(0xFFFFB020),
    Color(0xFFFF7B72),
    Color(0xFFC084FC),
    Color(0xFF56D4DD),
)

@Composable
fun SeeAndSayDashboard(
    viewModel: ChronologerViewModel,
    onRequestPermissions: () -> Unit,
) {
    val apiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val cameraMode by viewModel.selectedCameraMode.collectAsStateWithLifecycle()
    val processing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val autoPolling by viewModel.isAutoPolling.collectAsStateWithLifecycle()
    val interval by viewModel.pollIntervalSeconds.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.isTtsEnabled.collectAsStateWithLifecycle()
    val banner by viewModel.statusBannerText.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val preview by viewModel.previewBitmap.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    var confirmClear by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = ChronologerScheme) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Header()
                }
                item {
                    ConfigHeader(
                        apiKey = apiKey,
                        cameraMode = cameraMode,
                        ttsEnabled = ttsEnabled,
                        onApiKey = viewModel::setGeminiApiKey,
                        onMode = viewModel::selectCameraMode,
                        onPair = viewModel::pairMetaGlasses,
                        onTts = viewModel::setTtsEnabled,
                    )
                }
                item {
                    ViewportCard(
                        preview = preview,
                        cameraMode = cameraMode,
                        banner = banner,
                        processing = processing,
                        autoPolling = autoPolling,
                        interval = interval,
                        onNarrate = viewModel::captureAndAnalyzeNow,
                        onTogglePolling = viewModel::toggleAutoPolling,
                        onInterval = viewModel::setPollInterval,
                        onRequestPermissions = onRequestPermissions,
                    )
                }
                item {
                    SearchExportRow(
                        query = query,
                        onQuery = viewModel::setSearchQuery,
                        onExport = viewModel::exportDatabase,
                        onClear = { confirmClear = true },
                    )
                }
                if (logs.isEmpty()) {
                    item { EmptyChronology(query.isNotBlank()) }
                } else {
                    items(logs, key = { it.id }) { log ->
                        ChronologyCard(
                            log = log,
                            expanded = expanded[log.id] == true,
                            onToggle = { expanded[log.id] = expanded[log.id] != true },
                        )
                    }
                }
            }
        }
        if (confirmClear) {
            ClearHistoryDialog(
                onDismiss = { confirmClear = false },
                onConfirm = {
                    confirmClear = false
                    viewModel.clearHistory()
                },
            )
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "SEE AND SAY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp,
        )
        Text(
            text = "Digital Chronologer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "First-person capture, microscopic logs, spoken summary only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigHeader(
    apiKey: String,
    cameraMode: CameraSourceMode,
    ttsEnabled: Boolean,
    onApiKey: (String) -> Unit,
    onMode: (CameraSourceMode) -> Unit,
    onPair: () -> Unit,
    onTts: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(true) }
    var revealKey by remember { mutableStateOf(false) }
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(bottom = if (open) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Key, camera source, and executive speech",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Collapse configuration" else "Expand configuration",
            )
        }
        if (open) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(onClick = { revealKey = !revealKey }) {
                        Text(if (revealKey) "Hide" else "Show")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Camera source",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val modes = listOf(
                CameraSourceMode.PHONE_CAMERAX to "Phone CameraX",
                CameraSourceMode.META_GLASSES_DAT to "Ray-Ban Meta Glasses",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = cameraMode == mode,
                        onClick = { onMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) {
                        Text(label, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text("Connect Meta AI Handshake")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Executive TTS", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Speaks the app and activity only. Code stays on screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ttsEnabled, onCheckedChange = onTts)
            }
        }
    }
}

@Composable
private fun ViewportCard(
    preview: Bitmap?,
    cameraMode: CameraSourceMode,
    banner: String,
    processing: Boolean,
    autoPolling: Boolean,
    interval: Int,
    onNarrate: () -> Unit,
    onTogglePolling: () -> Unit,
    onInterval: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    var slider by remember { mutableStateOf(interval.toFloat()) }
    LaunchedEffect(interval) { slider = interval.toFloat() }
    SectionCard {
        Text("Live viewport", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF090C11)),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null && !preview.isRecycled) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Live camera preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (cameraMode == CameraSourceMode.META_GLASSES_DAT) "GLASSES" else "PHONE",
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (cameraMode == CameraSourceMode.META_GLASSES_DAT) {
                            "Waiting for a Ray-Ban frame"
                        } else {
                            "Waiting for the phone camera"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (banner.contains("permission", ignoreCase = true)) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("Grant camera, microphone, and Bluetooth")
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = banner,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (processing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onNarrate,
            enabled = !processing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (processing) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Narrate & Log Scene Now")
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-Chronologer Polling", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Every ${slider.roundToInt()}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoPolling, onCheckedChange = { onTogglePolling() })
        }
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onInterval(slider.roundToInt()) },
            valueRange = ChronologerViewModel.MIN_INTERVAL_SECONDS.toFloat()..ChronologerViewModel.MAX_INTERVAL_SECONDS.toFloat(),
            steps = ChronologerViewModel.MAX_INTERVAL_SECONDS - ChronologerViewModel.MIN_INTERVAL_SECONDS - 1,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("4s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("30s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchExportRow(
    query: String,
    onQuery: (String) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search chronology") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text("Export JSON")
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyChronology(filtered: Boolean) {
    SectionCard {
        Text(
            text = if (filtered) "No logs match that search." else "No chronology yet.",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (filtered) {
                "Try an app name, an activity, or a line of visible text."
            } else {
                "Narrate a scene and the microscopic log will land here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChronologyCard(
    log: NarrationLog,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceBadge(log.activeDevice.ifBlank { "Unknown device" })
                Spacer(Modifier.width(8.dp))
                Text(
                    text = log.formattedTimestamp.ifBlank { "Unstamped" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse log" else "Expand log",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = log.primaryApp.ifBlank { "No primary app" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = log.primaryActivity.ifBlank { "No primary activity" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    "VISIBLE TEXT & CODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.8.sp,
                )
                SelectionContainer {
                    Text(
                        text = log.visibleText.ifBlank { "No legible text in this frame." },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CodeBackground)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                        color = CodeForeground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                MetadataChips(log)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataChips(log: NarrationLog) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetaChip("Micro", log.microActions)
        MetaChip("Physical", log.physicalAction)
        MetaChip("Ambient", log.ambientContext)
    }
}

@Composable
private fun DeviceBadge(device: String) {
    val color = DevicePalette[abs(device.hashCode()) % DevicePalette.size]
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(50), contentColor = color) {
        Text(
            text = device,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun MetaChip(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = "$label: ${value.ifBlank { "—" }}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = { content() })
    }
}

@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear chronology?") },
        text = { Text("This deletes every narration log stored on this device.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
