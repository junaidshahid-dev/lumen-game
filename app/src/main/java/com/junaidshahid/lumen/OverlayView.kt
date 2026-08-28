package com.junaidshahid.lumen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The flat UI layer sitting on top of the GL surface: wordmark, score cards,
 * controls and the end-of-game panel.
 *
 * It is drawn with Canvas rather than a view hierarchy so the glass styling
 * matches the 3D scene, and so a touch that is not on a control can fall
 * straight through to the board underneath.
 */
class OverlayView(context: Context) : View(context) {

    fun interface Listener {
        fun onAction(action: Action)
    }

    enum class Action { UNDO, NEW_GAME, TOGGLE_MODE }

    var listener: Listener? = null
    var game: Game? = null

    private var topInset = 0
    private var bottomInset = 0

    /**
     * Insets can arrive after the view is already sized (the bars are hidden at
     * startup and swipe back in later), so the control row is re-placed here
     * rather than only in [onSizeChanged].
     */
    fun applyInsets(top: Int, bottom: Int) {
        if (top == topInset && bottom == bottomInset) return
        topInset = top
        bottomInset = bottom
        if (width > 0 && height > 0) layoutButtons(width, height)
        invalidate()
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private val rect = RectF()

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val buttons = ArrayList<Button>(4)
    private var pressedId: Action? = null

    /** Score is eased towards the real value so a big merge reads as a climb. */
    private var shownScore = 0f
    private var floaterValue = 0L
    private var floaterAge = 1f
    private var milestone = 0
    private var milestoneAge = 1f
    private var overFade = 0f
    private var lastFrame = 0L

    private class Button(val action: Action, val rect: RectF, var label: String, var enabled: Boolean)

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    fun notifyMerge(gained: Long) {
        if (gained <= 0) return
        floaterValue = gained
        floaterAge = 0f
        invalidate()
    }

    fun notifyMilestone(exp: Int) {
        if (exp <= 0) return
        milestone = exp
        milestoneAge = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutButtons(w, h)
    }

    private fun layoutButtons(w: Int, h: Int) {
        buttons.clear()
        val side = dp(20f)
        val btnH = dp(48f)
        val gap = dp(10f)
        val y = h - bottomInset - dp(26f) - btnH
        val usable = w - side * 2 - gap * 2
        val wide = usable * 0.38f
        val narrow = (usable - wide) / 2f

        var x = side
        buttons.add(Button(Action.TOGGLE_MODE, RectF(x, y, x + wide, y + btnH), "Classic", true))
        x += wide + gap
        buttons.add(Button(Action.UNDO, RectF(x, y, x + narrow, y + btnH), "Undo", false))
        x += narrow + gap
        buttons.add(Button(Action.NEW_GAME, RectF(x, y, x + narrow, y + btnH), "New", true))
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val g = game ?: return

        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f
        else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrame = now

        // Ease the counter, age the transient bits.
        shownScore += (g.scoreForUi - shownScore) * (1f - Math.pow(0.0001, dt.toDouble()).toFloat())
        if (abs(g.scoreForUi - shownScore) < 0.6f) shownScore = g.scoreForUi.toFloat()
        if (floaterAge < 1f) floaterAge = (floaterAge + dt / 0.9f).coerceAtMost(1f)
        if (milestoneAge < 1f) milestoneAge = (milestoneAge + dt / 2.6f).coerceAtMost(1f)
        val overTarget = if (g.status == Status.OVER) 1f else 0f
        overFade += (overTarget - overFade) * (1f - Math.pow(0.002, dt.toDouble()).toFloat())

        drawHeader(canvas, g)
        drawButtons(canvas, g)
        if (milestoneAge < 1f) drawMilestone(canvas)
        if (overFade > 0.004f) drawGameOver(canvas, g)

        val busy = floaterAge < 1f || milestoneAge < 1f ||
            abs(g.scoreForUi - shownScore) > 0.6f || abs(overTarget - overFade) > 0.004f
        if (busy) postInvalidateOnAnimation()
    }

    private fun drawHeader(canvas: Canvas, g: Game) {
        val side = dp(20f)
        val top = topInset + dp(18f)

        text.typeface = light
        text.color = Color.argb(235, 228, 238, 255)
        text.textSize = dp(27f)
        text.letterSpacing = 0.34f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("LUMEN", side, top + dp(24f), text)

        text.typeface = medium
        text.textSize = dp(9.5f)
        text.letterSpacing = 0.24f
        text.color = Color.argb(120, 150, 178, 214)
        canvas.drawText(
            if (g.mode == Mode.ZEN) "ENDLESS DRIFT" else "MERGE THE LIGHT",
            side, top + dp(41f), text
        )
        text.letterSpacing = 0f

        val cardW = dp(92f)
        val cardH = dp(58f)
        val gap = dp(9f)
        val right = width - side
        drawStatCard(canvas, RectF(right - cardW, top, right, top + cardH), "SCORE",
            shownScore.roundToLong().toString(), true)
        drawStatCard(
            canvas,
            RectF(right - cardW * 2 - gap, top, right - cardW - gap, top + cardH),
            "BEST", g.best.toString(), false
        )

        if (floaterAge < 1f) {
            val a = 1f - floaterAge
            text.typeface = medium
            text.textSize = dp(17f)
            text.textAlign = Paint.Align.CENTER
            text.color = Color.argb((a * 235).toInt(), 150, 226, 200)
            canvas.drawText(
                "+$floaterValue",
                right - cardW / 2f,
                top + cardH + dp(20f) - floaterAge * dp(22f),
                text
            )
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawStatCard(canvas: Canvas, r: RectF, label: String, value: String, accent: Boolean) {
        glassPanel(canvas, r, dp(17f), if (accent) 0.16f else 0.10f)

        text.typeface = medium
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(8.5f)
        text.letterSpacing = 0.22f
        text.color = Color.argb(130, 148, 176, 212)
        canvas.drawText(label, r.centerX(), r.top + dp(17f), text)

        text.letterSpacing = 0f
        text.textSize = if (value.length > 6) dp(17f) else dp(21f)
        text.color = if (accent) Color.argb(248, 232, 244, 255) else Color.argb(205, 196, 214, 240)
        canvas.drawText(value, r.centerX(), r.bottom - dp(13f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawButtons(canvas: Canvas, g: Game) {
        if (buttons.isEmpty()) layoutButtons(width, height)
        for (b in buttons) {
            when (b.action) {
                Action.TOGGLE_MODE -> {
                    b.label = if (g.mode == Mode.ZEN) "Zen mode" else "Classic"
                    b.enabled = true
                }

                Action.UNDO -> b.enabled = g.canUndo
                Action.NEW_GAME -> b.enabled = true
            }
            val pressed = pressedId == b.action
            val alpha = if (b.enabled) 1f else 0.38f
            glassPanel(
                canvas, b.rect, b.rect.height() / 2f,
                (if (pressed) 0.26f else 0.13f) * alpha
            )

            text.typeface = medium
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(13.5f)
            text.letterSpacing = 0.06f
            text.color = Color.argb((alpha * 232).toInt(), 216, 232, 255)
            text.getTextBounds(b.label, 0, b.label.length, bounds)
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - bounds.exactCenterY(), text)
            text.textAlign = Paint.Align.LEFT
            text.letterSpacing = 0f
        }
    }

    private fun drawMilestone(canvas: Canvas) {
        // Rise, hold, then fade.
        val a = when {
            milestoneAge < 0.12f -> milestoneAge / 0.12f
            milestoneAge > 0.72f -> 1f - (milestoneAge - 0.72f) / 0.28f
            else -> 1f
        }.coerceIn(0f, 1f)

        val cy = height * 0.24f - milestoneAge * dp(16f)
        text.typeface = light
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.3f
        text.textSize = dp(11f)
        text.color = Color.argb((a * 170).toInt(), 160, 200, 240)
        canvas.drawText("YOU REACHED", width / 2f, cy - dp(26f), text)

        text.textSize = dp(46f)
        text.letterSpacing = 0.06f
        text.color = Color.argb((a * 245).toInt(), 236, 246, 255)
        canvas.drawText((1L shl milestone).toString(), width / 2f, cy + dp(18f), text)

        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0f
    }

    private fun drawGameOver(canvas: Canvas, g: Game) {
        fill.shader = null
        fill.color = Color.argb((overFade * 200).toInt(), 4, 7, 16)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val w = width - dp(48f) * 2
        val h = dp(200f)
        val r = RectF((width - w) / 2f, (height - h) / 2f - dp(30f), (width + w) / 2f, (height + h) / 2f - dp(30f))
        glassPanel(canvas, r, dp(26f), 0.20f * overFade)

        text.textAlign = Paint.Align.CENTER
        text.typeface = light
        text.textSize = dp(24f)
        text.letterSpacing = 0.1f
        text.color = Color.argb((overFade * 240).toInt(), 232, 242, 255)
        canvas.drawText("No moves left", r.centerX(), r.top + dp(56f), text)

        text.typeface = medium
        text.textSize = dp(11f)
        text.letterSpacing = 0.2f
        text.color = Color.argb((overFade * 130).toInt(), 150, 178, 214)
        canvas.drawText("FINAL SCORE", r.centerX(), r.top + dp(92f), text)

        text.typeface = light
        text.textSize = dp(42f)
        text.letterSpacing = 0f
        text.color = Color.argb((overFade * 250).toInt(), 236, 246, 255)
        canvas.drawText(g.scoreForUi.toString(), r.centerX(), r.top + dp(140f), text)

        text.typeface = medium
        text.textSize = dp(11.5f)
        text.color = Color.argb((overFade * 150).toInt(), 150, 178, 214)
        canvas.drawText(
            "Switch to Zen mode to keep going",
            r.centerX(), r.bottom - dp(24f), text
        )
        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0f
    }

    /**
     * The shared glass look: a top-lit vertical gradient, a hairline border that
     * is brightest at the top edge, and nothing else. Keeping every panel on one
     * routine is what makes the flat UI sit convincingly on the 3D scene.
     */
    private fun glassPanel(canvas: Canvas, r: RectF, radius: Float, strength: Float) {
        fill.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Color.argb((strength * 255).toInt().coerceIn(0, 255), 170, 200, 255),
            Color.argb((strength * 110).toInt().coerceIn(0, 255), 90, 120, 190),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fill)
        fill.shader = null

        stroke.strokeWidth = dp(1f)
        stroke.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Color.argb((strength * 430).toInt().coerceIn(0, 255), 200, 224, 255),
            Color.argb((strength * 90).toInt().coerceIn(0, 255), 120, 150, 210),
            Shader.TileMode.CLAMP
        )
        rect.set(r)
        rect.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(rect, radius, radius, stroke)
        stroke.shader = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = game
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = buttons.firstOrNull { it.enabled && it.rect.contains(event.x, event.y) }
                if (hit != null) {
                    pressedId = hit.action
                    invalidate()
                    return true
                }
                // The end panel is modal: swallow strays so a swipe cannot
                // restart play on a board the user is still looking at.
                return g != null && g.status == Status.OVER
            }

            MotionEvent.ACTION_UP -> {
                val pressed = pressedId
                pressedId = null
                invalidate()
                if (pressed != null) {
                    val stillInside = buttons.firstOrNull { it.action == pressed }
                        ?.rect?.contains(event.x, event.y) == true
                    if (stillInside) {
                        performClick()
                        listener?.onAction(pressed)
                    }
                    return true
                }
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedId = null
                invalidate()
                return false
            }
        }
        return pressedId != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
