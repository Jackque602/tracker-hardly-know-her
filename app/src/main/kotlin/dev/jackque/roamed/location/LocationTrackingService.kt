package dev.jackque.roamed.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.jackque.roamed.R
import dev.jackque.roamed.appContainer
import dev.jackque.roamed.core.stats.ExplorationStats
import dev.jackque.roamed.data.repo.Fix
import dev.jackque.roamed.data.repo.RecordOutcome
import dev.jackque.roamed.data.repo.RoamedSettings
import dev.jackque.roamed.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Keeps a location subscription alive for as long as tracking is on.
 *
 * A foreground service is the only way Android will let an app watch your position over hours, and
 * the persistent notification is the honest side of that bargain: it is always obvious when the
 * app is recording, and it can be stopped from the shade without opening the app.
 */
class LocationTrackingService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    private lateinit var client: FusedLocationProviderClient
    private lateinit var placeResolver: PlaceResolver

    @Volatile
    private var settings = RoamedSettings()
    private var subscribed = false
    private var activeRequestSignature: String? = null
    private var lastNotificationAt = 0L
    private var lastPruneAt = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.toList()
            scope.launch { locations.forEach { handleLocation(it) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        placeResolver = PlaceResolver(applicationContext)
        createNotificationChannel()

        scope.launch {
            appContainer.settings.settings.collectLatest { latest ->
                settings = latest
                if (!latest.trackingEnabled) {
                    stopTracking()
                } else {
                    applyLocationRequest()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The service's own scope dies with stopTracking(), so persist the flag on the
            // application scope or the "off" would never reach disk.
            appContainer.applicationScope.launch { appContainer.settings.setTrackingEnabled(false) }
            stopTracking()
            return START_NOT_STICKY
        }

        startInForeground()

        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing; not starting updates.")
            stopTracking()
            return START_NOT_STICKY
        }
        applyLocationRequest()
        return START_STICKY
    }

    override fun onDestroy() {
        removeUpdates()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun stopTracking() {
        removeUpdates()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * (Re)subscribes only when the request has actually changed - collecting settings emits on
     * every unrelated toggle, and tearing down a GPS subscription to rebuild an identical one
     * costs a fresh fix acquisition each time.
     */
    private fun applyLocationRequest() {
        if (!hasLocationPermission()) return
        val current = settings
        val signature = "${current.updateIntervalSeconds}/${current.minDisplacementMeters}/${current.highAccuracyMode}"
        if (subscribed && signature == activeRequestSignature) return

        removeUpdates()
        val intervalMillis = current.updateIntervalSeconds * 1_000L
        val request = LocationRequest.Builder(
            if (current.highAccuracyMode) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMillis,
        )
            .setMinUpdateIntervalMillis(max(5_000L, intervalMillis / 2))
            .setMinUpdateDistanceMeters(current.minDisplacementMeters.toFloat())
            .setWaitForAccurateLocation(false)
            .build()

        try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            subscribed = true
            activeRequestSignature = signature
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked while running", e)
            stopTracking()
        }
    }

    private fun removeUpdates() {
        if (!subscribed) return
        client.removeLocationUpdates(locationCallback)
        subscribed = false
        activeRequestSignature = null
    }

    private suspend fun handleLocation(location: Location) {
        val fix = Fix(
            timestamp = if (location.time > 0) location.time else System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed else null,
        )

        val repository = appContainer.exploration
        repository.load()
        val outcome = repository.recordFix(fix, settings)
        if (outcome is RecordOutcome.Rejected) return

        if (settings.resolvePlaces) {
            placeResolver.resolve(fix)?.let { repository.recordPlace(it) }
        }
        maybePrune()
        maybeUpdateNotification()
    }

    /** Retention housekeeping, at most once every six hours while tracking. */
    private suspend fun maybePrune() {
        val now = System.currentTimeMillis()
        if (now - lastPruneAt < PRUNE_INTERVAL_MILLIS) return
        lastPruneAt = now
        appContainer.exploration.pruneRawFixes(settings.keepRawFixesDays)
    }

    private fun maybeUpdateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MILLIS) return
        lastNotificationAt = now
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = appContainer.exploration.state.value
        val summary = if (state.cellCount == 0) {
            getString(R.string.notification_waiting)
        } else {
            getString(
                R.string.notification_summary,
                ExplorationStats.formatArea(state.areaSquareMeters),
                ExplorationStats.formatPercent(
                    ExplorationStats.percentOfEarthLand(state.areaSquareMeters),
                ),
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(summary)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_tracking_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RoamedTracking"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MILLIS = 30_000L
        private const val PRUNE_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
        const val ACTION_STOP = "dev.jackque.roamed.action.STOP_TRACKING"

        /**
         * Starting a foreground service is refused in plenty of ordinary situations (no
         * permission, background start restrictions on Android 12+), so this never throws at the
         * caller - it reports whether the service actually got going.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start tracking service", e)
            false
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, LocationTrackingService::class.java)
                        .setAction(ACTION_STOP),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop tracking service", e)
            }
        }
    }
}
