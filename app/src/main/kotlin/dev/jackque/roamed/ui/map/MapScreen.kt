package dev.jackque.roamed.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jackque.roamed.appContainer
import dev.jackque.roamed.core.stats.ExplorationStats
import dev.jackque.roamed.data.repo.MapStyle
import dev.jackque.roamed.location.LocationTrackingService
import dev.jackque.roamed.ui.common.rememberLocationPermissionState
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))

    val fogState by viewModel.fogState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trail by viewModel.trail.collectAsStateWithLifecycle()
    val permissions = rememberLocationPermissionState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var followMe by remember { mutableStateOf(true) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            setFlingEnabled(true)
            // A single world, so panning east forever cannot walk off into empty repeats.
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            setMinZoomLevel(2.5)
            setMaxZoomLevel(19.0)
            getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(4.0)
        }
    }
    val overlay = remember { FogOverlay(viewModel.index) }

    DisposableEffect(mapView, overlay) {
        mapView.overlays.add(overlay)
        onDispose {
            mapView.overlays.remove(overlay)
            mapView.onDetach()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The service is started and stopped from the switch, not from the setting alone, so the
    // foreground-service start always happens while the app is visible.
    LaunchedEffect(settings.trackingEnabled, permissions.foregroundGranted) {
        if (settings.trackingEnabled && permissions.foregroundGranted) {
            LocationTrackingService.start(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val style = when (settings.mapStyle) {
                    MapStyle.STANDARD -> TileSourceFactory.MAPNIK
                    MapStyle.TOPOGRAPHIC -> TileSourceFactory.OpenTopo
                }
                if (view.tileProvider.tileSource != style) view.setTileSource(style)

                overlay.opacity = settings.fogOpacity
                overlay.showTrail = settings.showTrail
                overlay.trail = trail
                fogState.lastFix?.let { fix ->
                    overlay.currentPosition = TrailPoint(fix.latitude, fix.longitude)
                    overlay.currentAccuracyMeters = fix.accuracy
                    if (followMe) {
                        view.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                        if (view.zoomLevelDouble < FOLLOW_ZOOM) view.controller.setZoom(FOLLOW_ZOOM)
                        followMe = false
                    }
                }
                view.invalidate()
            },
        )

        FogSummaryCard(
            cellCount = fogState.cellCount,
            areaSquareMeters = fogState.areaSquareMeters,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
        )

        if (!permissions.canTrack) {
            PermissionCard(
                title = "Roamed needs your location",
                body = "Nothing gets uncovered until the app can see where you are. Your positions " +
                    "stay on this phone.",
                actionLabel = "Allow location",
                onAction = permissions.requestForeground,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        } else if (!permissions.canTrackInBackground) {
            PermissionCard(
                title = "Keep uncovering in the background",
                body = "Right now the map only fills in while Roamed is open. Choose \"Allow all " +
                    "the time\" to have it keep up with you.",
                actionLabel = "Allow all the time",
                onAction = permissions.requestBackground,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(
                onClick = {
                    fogState.lastFix?.let { fix ->
                        mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                        mapView.controller.setZoom(FOLLOW_ZOOM)
                    } ?: run { followMe = true }
                },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Centre on me")
            }
            Spacer(Modifier.height(12.dp))
            FloatingActionButton(
                onClick = {
                    val turningOn = !settings.trackingEnabled
                    if (turningOn && !permissions.notificationsGranted) {
                        permissions.requestNotifications()
                    }
                    viewModel.setTracking(turningOn)
                    if (turningOn) {
                        LocationTrackingService.start(context)
                    } else {
                        LocationTrackingService.stop(context)
                    }
                },
                containerColor = if (settings.trackingEnabled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Icon(
                    imageVector = if (settings.trackingEnabled) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (settings.trackingEnabled) "Pause tracking" else "Start tracking",
                )
            }
        }
    }
}

@Composable
private fun FogSummaryCard(
    cellCount: Int,
    areaSquareMeters: Double,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Stat(
                value = ExplorationStats.formatPercent(
                    ExplorationStats.percentOfEarthLand(areaSquareMeters),
                ),
                label = "of Earth's land",
            )
            Stat(value = ExplorationStats.formatArea(areaSquareMeters), label = "uncovered")
            Stat(value = cellCount.toString(), label = "squares")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private const val FOLLOW_ZOOM = 15.0
