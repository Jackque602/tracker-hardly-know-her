package dev.jackque.roamed.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.jackque.roamed.R
import dev.jackque.roamed.appContainer
import dev.jackque.roamed.core.stats.ExplorationStats
import dev.jackque.roamed.location.LocationTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The map, on the car screen.
 *
 * The template itself is almost empty: a strip of buttons, and a surface. Everything you actually
 * see - tiles, fog, your position, the status line - is painted by [CarMapRenderer]. That split is
 * on purpose. Navigation templates are the only ones that come with a drawing surface, but their
 * information slots are meant for turn-by-turn guidance, and this app is not guiding anyone
 * anywhere. Drawing the status text onto the canvas keeps the app honest about that and out of
 * the host's way.
 */
class CarMapScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val container = carContext.appContainer
    private val renderer = CarMapRenderer(
        carContext.applicationContext,
        container.exploration.index,
        container.exploration.airIndex,
    )

    /** Squares at the moment this screen opened, so "this trip" means this trip. */
    private var squaresAtStart: Int? = null
    private var tracking = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(this@CarMapScreen)
                watch()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
    }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle(if (tracking) "Stop" else "Start")
                        .setOnClickListener { toggleTracking() }
                        .build(),
                )
                .build(),
        )
        .setMapActionStrip(
            // Icons only, and no more than four: the host refuses a map strip with titles in it.
            ActionStrip.Builder()
                .addAction(iconAction(R.drawable.ic_car_recenter) { renderer.recenter() })
                .addAction(iconAction(R.drawable.ic_car_zoom_in) { renderer.zoomBy(1) })
                .addAction(iconAction(R.drawable.ic_car_zoom_out) { renderer.zoomBy(-1) })
                .build(),
        )
        .build()

    // --- the surface -------------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        renderer.attach(
            surface,
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderer.detach()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        renderer.scheduleDraw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        renderer.setStableArea(stableArea)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        // The host reports how far the content should move, which is the opposite of the view.
        renderer.panBy(-distanceX, -distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        // No inertia. A map that keeps sliding after you let go is the last thing a driver needs.
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        renderer.scaleBy(scaleFactor)
    }

    override fun onClick(x: Float, y: Float) {
        // Nothing on the map is tappable; the buttons do the work.
    }

    // --- state ------------------------------------------------------------------------------

    private fun watch() {
        lifecycleScope.launch {
            container.exploration.load()
            combine(container.exploration.state, container.settings.settings) { fog, settings ->
                fog to settings
            }.collect { (fog, settings) ->
                if (squaresAtStart == null) squaresAtStart = fog.cellCount
                val wasTracking = tracking
                tracking = settings.trackingEnabled
                val fix = fog.lastFix
                if (fix != null) {
                    renderer.setPosition(
                        CarMapRenderer.Position(fix.latitude, fix.longitude, fix.accuracy),
                    )
                } else {
                    // No fix yet this run - open on the middle of whatever has been uncovered,
                    // which is a far better guess than zero by zero.
                    container.exploration.index.bounds()?.let {
                        renderer.aimAtFallback(it.centerLatitude, it.centerLongitude)
                    }
                }
                refreshStatus()
                // The buttons only change when tracking does, and templates are rate limited.
                if (wasTracking != tracking) invalidate()
            }
        }
        lifecycleScope.launch {
            // "Last fix 40s ago" has to keep counting even when nothing is arriving - that silence
            // is the very thing worth noticing from the driver's seat.
            while (true) {
                delay(TICK_MILLIS)
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        val fog = container.exploration.state.value
        val started = squaresAtStart ?: fog.cellCount
        val gained = (fog.cellCount - started).coerceAtLeast(0)
        renderer.setStatus(
            if (tracking) {
                val fix = fog.lastFix
                if (fix == null) {
                    "Live · waiting for a fix"
                } else {
                    val ago = ((System.currentTimeMillis() - fix.timestamp) / 1000L)
                        .coerceAtLeast(0L)
                    "Live · last fix ${ago}s · $gained squares this trip"
                }
            } else {
                "Paused · ${fog.cellCount} squares · " +
                    ExplorationStats.formatArea(fog.areaSquareMeters)
            },
        )
    }

    private fun toggleTracking() {
        val turningOn = !tracking
        container.applicationScope.launch {
            container.settings.setTrackingEnabled(turningOn)
        }
        if (turningOn) {
            LocationTrackingService.start(carContext)
        } else {
            LocationTrackingService.stop(carContext)
        }
    }

    private fun iconAction(drawableRes: Int, onClick: () -> Unit): Action = Action.Builder()
        .setIcon(
            CarIcon.Builder(IconCompat.createWithResource(carContext, drawableRes)).build(),
        )
        .setOnClickListener { onClick() }
        .build()

    private companion object {
        const val TICK_MILLIS = 5_000L
    }
}
