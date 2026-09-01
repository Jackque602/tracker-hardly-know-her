package dev.jackque.roamed.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jackque.roamed.BuildConfig
import dev.jackque.roamed.appContainer
import dev.jackque.roamed.data.repo.MapStyle
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context, container))

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingErase by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val backupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val geoJsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json"),
    ) { uri -> uri?.let(viewModel::exportGeoJson) }
    val gpxExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri -> uri?.let(viewModel::exportGpx) }
    val backupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importBackup) }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            item {
                SettingsCard("Recording") {
                    SliderRow(
                        label = "Check position every",
                        value = settings.updateIntervalSeconds.toFloat(),
                        range = 5f..300f,
                        display = "${settings.updateIntervalSeconds} s",
                        onChange = { viewModel.setUpdateInterval(it.roundToInt()) },
                    )
                    SliderRow(
                        label = "Only after moving",
                        value = settings.minDisplacementMeters.toFloat(),
                        range = 0f..200f,
                        display = "${settings.minDisplacementMeters} m",
                        onChange = { viewModel.setMinDisplacement(it.roundToInt()) },
                    )
                    SwitchRow(
                        label = "High accuracy",
                        description = "Uses GPS aggressively. More precise edges, noticeably more battery.",
                        checked = settings.highAccuracyMode,
                        onChange = viewModel::setHighAccuracy,
                    )
                    SwitchRow(
                        label = "Join up the dots",
                        description = "Fills the gap between two fixes so driving leaves a continuous trail.",
                        checked = settings.connectTheDots,
                        onChange = viewModel::setConnectTheDots,
                    )
                }
            }

            item {
                SettingsCard("Uncovering") {
                    SliderRow(
                        label = "Reveal radius",
                        value = settings.revealRadiusMeters.toFloat(),
                        range = 25f..500f,
                        display = "${settings.revealRadiusMeters} m",
                        onChange = { viewModel.setRevealRadius(it.roundToInt()) },
                    )
                    SliderRow(
                        label = "Ignore fixes worse than",
                        value = settings.maxAccuracyMeters.toFloat(),
                        range = 10f..300f,
                        display = "${settings.maxAccuracyMeters} m",
                        onChange = { viewModel.setMaxAccuracy(it.roundToInt()) },
                    )
                    SliderRow(
                        label = "Fog thickness",
                        value = settings.fogOpacity,
                        range = 0.2f..1f,
                        display = "${(settings.fogOpacity * 100).roundToInt()}%",
                        onChange = viewModel::setFogOpacity,
                    )
                }
            }

            item {
                SettingsCard("Map") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapStyle.entries.forEach { style ->
                            FilterChip(
                                selected = settings.mapStyle == style,
                                onClick = { viewModel.setMapStyle(style) },
                                label = {
                                    Text(
                                        when (style) {
                                            MapStyle.STANDARD -> "Standard"
                                            MapStyle.TOPOGRAPHIC -> "Topographic"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        label = "Show today's trail",
                        description = "Draws the last 24 hours of fixes on top of the fog.",
                        checked = settings.showTrail,
                        onChange = viewModel::setShowTrail,
                    )
                    SwitchRow(
                        label = "Name countries and regions",
                        description = "Looks up new areas so the stats screen can count countries. Needs a connection.",
                        checked = settings.resolvePlaces,
                        onChange = viewModel::setResolvePlaces,
                    )
                }
            }

            item {
                SettingsCard("Your data") {
                    Text(
                        "Everything stays on this phone. Nothing is uploaded anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SliderRow(
                        label = "Keep raw fixes for",
                        value = settings.keepRawFixesDays.toFloat(),
                        range = 0f..1_095f,
                        display = if (settings.keepRawFixesDays == 0) "don't store"
                        else "${settings.keepRawFixesDays} days",
                        onChange = { viewModel.setKeepRawFixesDays(it.roundToInt()) },
                    )
                    Text(
                        "The uncovered map is kept forever either way - this only affects the raw " +
                            "trail used for the overlay and GPX export.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { backupExporter.launch("roamed-backup.json") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export backup") }
                    OutlinedButton(
                        onClick = { backupImporter.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import backup") }
                    OutlinedButton(
                        onClick = { geoJsonExporter.launch("roamed-explored.geojson") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export uncovered area as GeoJSON") }
                    OutlinedButton(
                        onClick = { gpxExporter.launch("roamed-track.gpx") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export trail as GPX") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmingErase = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Erase everything", color = MaterialTheme.colorScheme.error) }
                }
            }

            item {
                Text(
                    text = "Roamed ${BuildConfig.VERSION_NAME}\n" +
                        "Map tiles © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmingErase) {
        AlertDialog(
            onDismissRequest = { confirmingErase = false },
            title = { Text("Erase everything?") },
            text = {
                Text(
                    "This deletes every uncovered square, every recorded fix and every statistic. " +
                        "It cannot be undone - export a backup first if you might want it back.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingErase = false
                        viewModel.clearEverything()
                    },
                ) { Text("Erase", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingErase = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
