package dev.jackque.roamed.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The three permissions this app needs, and how far along the user is.
 *
 * Android insists that background location is asked for *after* foreground location has already
 * been granted, in a separate prompt - asking for both at once silently drops the background half.
 */
class LocationPermissionState(
    val foregroundGranted: Boolean,
    val backgroundGranted: Boolean,
    val notificationsGranted: Boolean,
    val requestForeground: () -> Unit,
    val requestBackground: () -> Unit,
    val requestNotifications: () -> Unit,
) {
    /** Enough to record while the app is open. */
    val canTrack: Boolean get() = foregroundGranted

    /** Enough to keep recording with the screen off, which is the point of the app. */
    val canTrackInBackground: Boolean get() = foregroundGranted && backgroundGranted
}

@Composable
fun rememberLocationPermissionState(): LocationPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var foreground by remember { mutableStateOf(context.hasForegroundLocation()) }
    var background by remember { mutableStateOf(context.hasBackgroundLocation()) }
    var notifications by remember { mutableStateOf(context.hasNotifications()) }

    fun refresh() {
        foreground = context.hasForegroundLocation()
        background = context.hasBackgroundLocation()
        notifications = context.hasNotifications()
    }

    // Permissions can be granted or revoked in Settings while we are backgrounded.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    return LocationPermissionState(
        foregroundGranted = foreground,
        backgroundGranted = background,
        notificationsGranted = notifications,
        requestForeground = {
            foregroundLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
        requestBackground = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        },
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

private fun Context.hasForegroundLocation(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.hasBackgroundLocation(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        // Before Android 10 the foreground grant covered background use as well.
        hasForegroundLocation()
    }

private fun Context.hasNotifications(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
