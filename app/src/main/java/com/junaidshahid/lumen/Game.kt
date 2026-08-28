package com.junaidshahid.lumen

import android.content.SharedPreferences
import androidx.core.content.edit

enum class Mode { CLASSIC, ZEN }

enum class Status { PLAYING, OVER }

/**
 * Owns the rules layer on top of [Board]: the two modes, one-step undo, the best
 * score, and the timeline the renderer animates against.
 *
 * Every mutating call happens on the GL thread (posted through the surface
 * view's event queue), so only the fields the 2D overlay reads are volatile.
 */
class Game(private val prefs: SharedPreferences) {

    val board = Board()

    @Volatile var mode: Mode = Mode.CLASSIC
        private set

    @Volatile var status: Status = Status.PLAYING
        private set

    @Volatile var best: Long = 0L
        private set

    @Volatile var scoreForUi: Long = 0L
        private set

    /** Pending celebration, drained by [consumeMilestone]. */
    @Volatile var milestoneExp: Int = 0
        private set

    /**
     * Highest power already celebrated. Kept separate from [milestoneExp] so that
     * draining the pending banner cannot make it fire again on the next move.
     */
    private var announcedExp = 0

    /** Bumped on every accepted move; the renderer uses it to fire sparks once. */
    @Volatile var moveSerial: Int = 0
        private set

    var animTime: Float = MOVE_DURATION
        private set

    /** Tiles absorbed by the move in flight; drawn until the timeline completes. */
    var ghosts: List<Tile> = emptyList()
        private set

    /** Board cells where a merge landed this move, for the spark burst. */
    var mergePoints: List<Tile> = emptyList()
        private set

    /** Cell that Zen mode dissolved, animated as a fade-out rather than a slide. */
    var dissolving: Tile? = null
        private set
    var dissolveTime: Float = DISSOLVE_DURATION
        private set

    private var undoExps: IntArray? = null
    private var undoScore: Long = 0L

    // Written on the GL thread, read by the overlay on the UI thread to decide
    // whether the Undo control is live.
    @Volatile private var undoAvailable = false

    var onStateChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = undoAvailable
    val isAnimating: Boolean get() = animTime < MOVE_DURATION

    init {
        best = prefs.getLong(KEY_BEST, 0L)
        mode = if (prefs.getString(KEY_MODE, "CLASSIC") == "ZEN") Mode.ZEN else Mode.CLASSIC
        if (!restore()) newGame()
    }

    fun newGame() {
        board.reset()
        status = Status.PLAYING
        undoAvailable = false
        ghosts = emptyList()
        mergePoints = emptyList()
        dissolving = null
        animTime = MOVE_DURATION
        milestoneExp = 0
        announcedExp = 0
        scoreForUi = board.score
        notifyChanged()
    }

    fun setMode(next: Mode) {
        if (next == mode) return
        mode = next
        prefs.edit { putString(KEY_MODE, next.name) }
        // Zen has no losing state, so switching into it revives a finished board.
        if (next == Mode.ZEN && status == Status.OVER) {
            status = Status.PLAYING
            makeRoomIfStuck()
        } else if (next == Mode.CLASSIC && !board.hasMoves()) {
            status = Status.OVER
        }
        notifyChanged()
    }

    /** Returns true when the move changed the board and an animation should run. */
    fun move(dir: Dir): Boolean {
        if (status == Status.OVER) return false
        if (isAnimating) {
            // Snap the in-flight animation instead of dropping the input, so a
            // fast run of swipes is never swallowed mid-slide.
            animTime = MOVE_DURATION
            ghosts = emptyList()
            mergePoints = emptyList()
        }

        val before = board.snapshot()
        val beforeScore = board.score

        val result = board.move(dir) ?: return false
        board.spawn()

        undoExps = before
        undoScore = beforeScore
        undoAvailable = true

        ghosts = result.ghosts
        mergePoints = board.tiles.filter { it.mergedThisMove }
        animTime = 0f
        dissolving = null
        moveSerial++

        val top = board.highestExp()
        if (top >= WIN_EXP && top > announcedExp) {
            announcedExp = top
            milestoneExp = top
        }

        scoreForUi = board.score
        if (board.score > best) {
            best = board.score
            prefs.edit { putLong(KEY_BEST, best) }
        }

        if (!board.hasMoves()) {
            if (mode == Mode.ZEN) makeRoomIfStuck() else status = Status.OVER
        }

        notifyChanged()
        return true
    }

    fun undo() {
        val snapshot = undoExps ?: return
        if (!undoAvailable) return
        board.restore(snapshot, undoScore)
        undoAvailable = false
        status = Status.PLAYING
        ghosts = emptyList()
        mergePoints = emptyList()
        dissolving = null
        animTime = MOVE_DURATION
        scoreForUi = board.score
        notifyChanged()
    }

    /** Zen mode never ends: when the board jams, the smallest tile evaporates. */
    private fun makeRoomIfStuck() {
        var guard = 0
        while (!board.hasMoves() && guard++ < board.size * board.size) {
            val gone = board.dissolveWeakest() ?: break
            dissolving = gone
            dissolveTime = 0f
        }
    }

    fun advance(dt: Float) {
        if (animTime < MOVE_DURATION) {
            animTime = (animTime + dt).coerceAtMost(MOVE_DURATION)
            if (animTime >= MOVE_DURATION) {
                ghosts = emptyList()
                mergePoints = emptyList()
            }
        }
        if (dissolveTime < DISSOLVE_DURATION) {
            dissolveTime = (dissolveTime + dt).coerceAtMost(DISSOLVE_DURATION)
            if (dissolveTime >= DISSOLVE_DURATION) dissolving = null
        }
    }

    fun consumeMilestone(): Int {
        val m = milestoneExp
        milestoneExp = 0
        return m
    }

    // ---- persistence ----

    fun save() {
        prefs.edit {
            putString(KEY_GRID, board.snapshot().joinToString(","))
            putLong(KEY_SCORE, board.score)
            putLong(KEY_BEST, best)
            putString(KEY_MODE, mode.name)
            putString(KEY_STATUS, status.name)
        }
    }

    private fun restore(): Boolean {
        val raw = prefs.getString(KEY_GRID, null) ?: return false
        val parts = raw.split(",")
        if (parts.size != board.size * board.size) return false
        val exps = IntArray(parts.size)
        for (i in parts.indices) exps[i] = parts[i].toIntOrNull() ?: return false
        if (exps.all { it == 0 }) return false

        board.restore(exps, prefs.getLong(KEY_SCORE, 0L))
        // Whatever was already on the board has been seen, so resuming a session
        // must not replay its celebration.
        announcedExp = board.highestExp()
        status = if (prefs.getString(KEY_STATUS, "PLAYING") == "OVER") Status.OVER else Status.PLAYING
        if (mode == Mode.ZEN) status = Status.PLAYING
        scoreForUi = board.score
        return true
    }

    private fun notifyChanged() {
        onStateChanged?.invoke()
    }

    companion object {
        /** Seconds for a full slide-and-settle. Short enough to chain swipes. */
        const val MOVE_DURATION = 0.20f
        const val DISSOLVE_DURATION = 0.45f
        const val WIN_EXP = 11 // 2048

        private const val KEY_BEST = "best"
        private const val KEY_GRID = "grid"
        private const val KEY_SCORE = "score"
        private const val KEY_MODE = "mode"
        private const val KEY_STATUS = "status"
    }
}
