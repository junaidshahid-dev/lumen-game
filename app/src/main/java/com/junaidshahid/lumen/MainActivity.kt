package com.junaidshahid.lumen

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

class MainActivity : Activity() {

    private lateinit var game: Game
    private lateinit var surface: GameSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var haptics: Haptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("lumen", Context.MODE_PRIVATE)
        game = Game(prefs)
        haptics = Haptics(this)

        surface = GameSurfaceView(this, game)
        overlay = OverlayView(this).apply { game = this@MainActivity.game }

        val root = FrameLayout(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        goEdgeToEdge(root)
        wireInput()

        // State can also change from the GL thread (Zen dissolving a tile), so
        // repaint the overlay through the view's own handler.
        game.onStateChanged = { overlay.postInvalidate() }
    }

    private fun goEdgeToEdge(root: android.view.View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // The bars are hidden but can be swiped back in, so the overlay still
        // needs their insets to avoid putting controls under a transient bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            overlay.applyInsets(bars.top, bars.bottom)
            insets
        }
    }

    private fun wireInput() {
        surface.onMoveApplied = { gained, milestone ->
            if (gained > 0) {
                overlay.notifyMerge(gained)
                haptics.merge(game.board.highestExp())
            } else {
                haptics.slide()
            }
            if (milestone > 0) overlay.notifyMilestone(milestone)
            overlay.invalidate()
        }
        surface.onMoveRejected = { haptics.reject() }

        overlay.listener = OverlayView.Listener { action ->
            haptics.tap()
            when (action) {
                OverlayView.Action.UNDO -> surface.mutate { game.undo() }
                OverlayView.Action.NEW_GAME -> surface.mutate { game.newGame() }
                OverlayView.Action.TOGGLE_MODE -> surface.mutate {
                    game.setMode(if (game.mode == Mode.ZEN) Mode.CLASSIC else Mode.ZEN)
                }
            }
            overlay.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
    }

    override fun onPause() {
        game.save()
        surface.onPause()
        super.onPause()
    }
}
