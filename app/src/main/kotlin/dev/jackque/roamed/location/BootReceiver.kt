package dev.jackque.roamed.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.jackque.roamed.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Puts tracking back on after a reboot or an app update.
 *
 * Without this, the map would quietly stop filling in the first time the phone restarted overnight
 * and the gap would only become obvious weeks later.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val pendingResult = goAsync()
        val container = context.appContainer
        container.applicationScope.launch(Dispatchers.Default) {
            try {
                if (container.settings.settings.first().trackingEnabled) {
                    LocationTrackingService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
