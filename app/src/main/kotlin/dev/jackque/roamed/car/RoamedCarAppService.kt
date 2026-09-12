package dev.jackque.roamed.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The way in from Android Auto.
 *
 * Declared as a navigation app in the manifest, which is not a boast - it is the only category the
 * Car App Library gives a drawing surface to, and without a surface there is no map, only lists of
 * text. The consequence is that this build could never go on Google Play: it would be reviewed
 * against the turn-by-turn navigation guidelines, which it makes no attempt to meet. Sideloading
 * it, which is the only way this app is installed anyway, also needs "Unknown sources" enabled in
 * Android Auto's own developer settings or the car will never list it.
 */
class RoamedCarAppService : CarAppService() {

    /**
     * Which hosts may drive this app.
     *
     * A debug build trusts anything, because the desktop head unit used for testing is not a
     * signed Google host. A release build accepts only the hosts the library itself vouches for -
     * anything else could drive the car screen and, through it, the tracker.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = RoamedSession()
}

/** One connection to a car screen. There is only ever one screen to show. */
class RoamedSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarMapScreen(carContext)
}
