package com.junaidshahid.lumen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.abs

/**
 * The GL surface, plus the two inputs that reach it: swipes and device tilt.
 *
 * Moves are posted onto the GL thread with [queueEvent] so that game state is
 * only ever touched from one thread.
 */
class GameSurfaceView(context: Context, private val game: Game) : GLSurfaceView(context) {

    private val renderer = SceneRenderer(game)
    private val swipeThreshold = 22f * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var gestureConsumed = false

    /** Delivered on the main thread with the points the move earned (0 for a pure slide). */
    var onMoveApplied: ((gained: Long, milestoneExp: Int) -> Unit)? = null
    var onMoveRejected: (() -> Unit)? = null

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Portrait only, so x is left/right lean and y is the forward tilt.
            // Divided by g and clamped, which keeps a shaken phone from lurching.
            renderer.tiltX = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            renderer.tiltY = ((event.values[1] / SensorManager.GRAVITY_EARTH) - 0.5f)
                .coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager?.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(tiltListener)
        super.onPause()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureConsumed = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureConsumed) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) < swipeThreshold && abs(dy) < swipeThreshold) return true

                // Fire as soon as the threshold is crossed rather than on lift:
                // the game should feel like it is tracking the thumb.
                gestureConsumed = true
                val dir = if (abs(dx) > abs(dy)) {
                    if (dx > 0) Dir.RIGHT else Dir.LEFT
                } else {
                    if (dy > 0) Dir.DOWN else Dir.UP
                }
                apply(dir)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureConsumed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun apply(dir: Dir) {
        queueEvent {
            val before = game.board.score
            if (game.move(dir)) {
                val gained = game.board.score - before
                val milestone = game.consumeMilestone()
                post { onMoveApplied?.invoke(gained, milestone) }
            } else {
                post { onMoveRejected?.invoke() }
            }
        }
    }

    /** Runs a mutation on the GL thread, for the buttons in the overlay. */
    fun mutate(block: () -> Unit) = queueEvent(block)
}
