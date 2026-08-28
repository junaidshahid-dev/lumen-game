package com.junaidshahid.lumen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Draws the whole scene: animated background, the glass slab with its recessed
 * wells, the tiles, their contact shadows, the numbers, and the merge sparks.
 *
 * Everything here runs on the GL thread. Input is handed over by posting to the
 * surface view's event queue, so no locking is needed.
 */
class SceneRenderer(private val game: Game) : GLSurfaceView.Renderer {

    // ---- scene layout, in world units where one grid cell is 1.0 ----
    private val grid = game.board.size
    private val cell = 1.0f
    private val tileHalf = 0.42f
    private val tileHeight = 0.17f
    private val slabHalf = (grid - 1) * 0.5f * cell + tileHalf + 0.15f
    private val slabHeight = 0.15f
    private val tileY = slabHeight + tileHeight

    private var bgProgram = 0
    private var litProgram = 0
    private var spriteProgram = 0

    private lateinit var tileMesh: Mesh
    private lateinit var slabMesh: Mesh
    private lateinit var wellMesh: Mesh
    private lateinit var quad: Mesh

    private var digitTex = 0
    private var shadowTex = 0
    private var sparkTex = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val normalMat = FloatArray(9)
    private val scratch = FloatArray(16)

    private val eye = floatArrayOf(0f, 9.33f, 5.83f)
    private val target = floatArrayOf(0f, 0.35f, 0.10f)
    private val liveEye = FloatArray(3)

    private var viewportW = 1
    private var viewportH = 1
    private var lastFrameNanos = 0L
    private var clock = 0f
    private var energy = 0f

    /** Smoothed device tilt, drives a small parallax on the camera. */
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    private var smoothTiltX = 0f
    private var smoothTiltY = 0f

    private val baseColor = FloatArray(3)
    private val rng = Random(LUMEN_SEED)

    private val particles = Particles(220)
    private var lastSparkedMove = -1

    // ---- uniform locations ----
    private var uBgTime = 0; private var uBgRes = 0; private var uBgEnergy = 0
    private var uLitMvp = 0; private var uLitModel = 0; private var uLitNrm = 0
    private var uLitBase = 0; private var uLitEye = 0; private var uLitEmissive = 0
    private var uLitAlpha = 0; private var uLitRough = 0
    private var uSpMvp = 0; private var uSpUvRect = 0; private var uSpTint = 0; private var uSpTex = 0

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.008f, 0.011f, 0.021f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // Culling stays off on purpose: the meshes are only a few thousand
        // triangles, and a wrong winding would silently erase geometry.
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        bgProgram = GlUtil.program(Shaders.BG_VERT, Shaders.BG_FRAG)
        litProgram = GlUtil.program(Shaders.LIT_VERT, Shaders.LIT_FRAG)
        spriteProgram = GlUtil.program(Shaders.SPRITE_VERT, Shaders.SPRITE_FRAG)

        uBgTime = GLES30.glGetUniformLocation(bgProgram, "uTime")
        uBgRes = GLES30.glGetUniformLocation(bgProgram, "uRes")
        uBgEnergy = GLES30.glGetUniformLocation(bgProgram, "uEnergy")

        uLitMvp = GLES30.glGetUniformLocation(litProgram, "uMvp")
        uLitModel = GLES30.glGetUniformLocation(litProgram, "uModel")
        uLitNrm = GLES30.glGetUniformLocation(litProgram, "uNrmMat")
        uLitBase = GLES30.glGetUniformLocation(litProgram, "uBase")
        uLitEye = GLES30.glGetUniformLocation(litProgram, "uEye")
        uLitEmissive = GLES30.glGetUniformLocation(litProgram, "uEmissive")
        uLitAlpha = GLES30.glGetUniformLocation(litProgram, "uAlpha")
        uLitRough = GLES30.glGetUniformLocation(litProgram, "uRoughness")

        uSpMvp = GLES30.glGetUniformLocation(spriteProgram, "uMvp")
        uSpUvRect = GLES30.glGetUniformLocation(spriteProgram, "uUvRect")
        uSpTint = GLES30.glGetUniformLocation(spriteProgram, "uTint")
        uSpTex = GLES30.glGetUniformLocation(spriteProgram, "uTex")

        tileMesh = MeshFactory.roundedBox(tileHalf, tileHeight, tileHalf, 0.15f, 0.055f, 7, 4)
        slabMesh = MeshFactory.roundedBox(slabHalf, slabHeight, slabHalf, 0.30f, 0.07f, 8, 4)
        wellMesh = MeshFactory.roundedBox(tileHalf, 0.03f, tileHalf, 0.14f, 0.022f, 6, 2)
        quad = MeshFactory.quadXZ()

        digitTex = GlUtil.texture(buildDigitAtlas())
        shadowTex = GlUtil.texture(buildRadialSprite(192, 0.55f, 2.1f))
        sparkTex = GlUtil.texture(buildRadialSprite(64, 1.0f, 2.6f))

        lastFrameNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportW, viewportH)

        // Fit by width: on a portrait phone the board is the widest thing on
        // screen, and the spare vertical room becomes the header and controls.
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        val dist = hypot(
            hypot(eye[0] - target[0], eye[1] - target[1]),
            eye[2] - target[2]
        )
        // The near edge of the slab sits ~9% closer to the camera than its centre,
        // so it projects wider than the centre-distance fit would suggest. The
        // 1.12 factor pays for that, and the constant is plain margin.
        val wanted = slabHalf * 1.12f + 0.14f
        val tanH = wanted / dist
        val tanV = tanH / aspect
        val near = 1.0f
        Matrix.frustumM(proj, 0, -tanH * near, tanH * near, -tanV * near, tanV * near, near, 60f)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        // A long stall (app resumed, GC pause) must not teleport the animation.
        if (dt > 0.1f) dt = 0.1f
        clock += dt

        game.advance(dt)
        energy = (energy - dt * 1.6f).coerceAtLeast(0f)
        emitSparksIfNeeded()
        particles.advance(dt)

        smoothTiltX += (tiltX - smoothTiltX) * (1f - Math.pow(0.0025, dt.toDouble()).toFloat())
        smoothTiltY += (tiltY - smoothTiltY) * (1f - Math.pow(0.0025, dt.toDouble()).toFloat())

        liveEye[0] = eye[0] + smoothTiltX * 0.42f
        liveEye[1] = eye[1] + smoothTiltY * 0.26f
        liveEye[2] = eye[2] - smoothTiltY * 0.10f

        Matrix.setLookAtM(
            view, 0,
            liveEye[0], liveEye[1], liveEye[2],
            target[0], target[1], target[2],
            0f, 1f, 0f
        )
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawBackground()
        drawSlab()
        drawWells()
        drawShadows()
        drawTiles()
        drawParticles()
    }

    // ------------------------------------------------------------------ passes

    private fun drawBackground() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(bgProgram)
        GLES30.glUniform1f(uBgTime, clock)
        GLES30.glUniform2f(uBgRes, viewportW.toFloat(), viewportH.toFloat())
        GLES30.glUniform1f(uBgEnergy, energy)
        GLES30.glBindVertexArray(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun beginLit() {
        GLES30.glUseProgram(litProgram)
        GLES30.glUniform3f(uLitEye, liveEye[0], liveEye[1], liveEye[2])
    }

    private fun drawSlab() {
        beginLit()
        Matrix.setIdentityM(model, 0)
        baseColor[0] = 0.020f; baseColor[1] = 0.030f; baseColor[2] = 0.055f
        submitLit(0.55f, 1f, 0.02f)
        slabMesh.draw()
    }

    private fun drawWells() {
        beginLit()
        baseColor[0] = 0.010f; baseColor[1] = 0.016f; baseColor[2] = 0.032f
        for (r in 0 until grid) for (c in 0 until grid) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, cellX(c.toFloat()), slabHeight - 0.012f, cellZ(r.toFloat()))
            submitLit(0.75f, 1f, 0f)
            wellMesh.draw()
        }
    }

    private fun drawShadows() {
        GLES30.glUseProgram(spriteProgram)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        bindSpriteTexture(shadowTex)
        GLES30.glUniform4f(uSpUvRect, 0f, 0f, 1f, 1f)

        val t = game.animTime / Game.MOVE_DURATION
        forEachDrawnTile(t) { _, x, z, scale, alpha, lift ->
            Matrix.setIdentityM(model, 0)
            // The shadow drifts slightly away from the key light and softens as
            // the tile lifts, which is what sells the pop as vertical motion.
            Matrix.translateM(model, 0, x + 0.10f * lift, slabHeight + 0.004f, z + 0.06f * lift)
            val s = tileHalf * 2f * (1.32f + lift * 0.55f) * scale
            Matrix.scaleM(model, 0, s, 1f, s)
            Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
            GLES30.glUniformMatrix4fv(uSpMvp, 1, false, mvp, 0)
            GLES30.glUniform4f(uSpTint, 0f, 0.01f, 0.03f, 0.62f * alpha / (1f + lift * 1.4f))
            quad.draw()
        }

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawTiles() {
        val t = game.animTime / Game.MOVE_DURATION

        beginLit()
        GLES30.glEnable(GLES30.GL_BLEND)
        forEachDrawnTile(t) { tile, x, z, scale, alpha, lift ->
            Palette.tileColor(tile.exp, baseColor)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, tileY + lift * 0.30f, z)
            Matrix.scaleM(model, 0, scale, scale, scale)
            submitLit(0.22f, alpha, Palette.emissive(tile.exp) + lift * 0.35f)
            tileMesh.draw()
        }
        GLES30.glDisable(GLES30.GL_BLEND)

        // Numbers ride on the tile tops, depth-tested but not depth-writing so
        // they never z-fight with the surface they sit on.
        GLES30.glUseProgram(spriteProgram)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        bindSpriteTexture(digitTex)

        forEachDrawnTile(t) { tile, x, z, scale, alpha, lift ->
            val idx = (tile.exp - 1).coerceIn(0, Palette.MAX_EXP - 1)
            GLES30.glUniform4f(
                uSpUvRect,
                (idx % ATLAS_COLS) * (1f / ATLAS_COLS),
                (idx / ATLAS_COLS) * (1f / ATLAS_ROWS),
                1f / ATLAS_COLS,
                1f / ATLAS_ROWS
            )
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(
                model, 0, x,
                tileY + tileHeight + 0.012f + lift * 0.30f, z
            )
            Matrix.scaleM(model, 0, LABEL_W * scale, 1f, LABEL_H * scale)
            Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
            GLES30.glUniformMatrix4fv(uSpMvp, 1, false, mvp, 0)
            // Dark numerals on the bright high-value tiles, light ones elsewhere.
            if (tile.exp in 7..10) {
                GLES30.glUniform4f(uSpTint, 0.06f, 0.09f, 0.13f, 0.92f * alpha)
            } else {
                GLES30.glUniform4f(uSpTint, 0.96f, 0.98f, 1f, 0.95f * alpha)
            }
            quad.draw()
        }

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawParticles() {
        if (particles.alive == 0) return
        GLES30.glUseProgram(spriteProgram)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Additive, so overlapping sparks bloom instead of flattening.
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        bindSpriteTexture(sparkTex)
        GLES30.glUniform4f(uSpUvRect, 0f, 0f, 1f, 1f)

        // Billboard basis: rows 0 and 1 of the view rotation are the camera's
        // right and up axes expressed in world space.
        val rx = view[0]; val ry = view[4]; val rz = view[8]
        val ux = view[1]; val uy = view[5]; val uz = view[9]

        particles.forEach { px, py, pz, size, r, g, b, a ->
            Matrix.setIdentityM(model, 0)
            model[0] = rx * size; model[1] = ry * size; model[2] = rz * size
            model[8] = ux * size; model[9] = uy * size; model[10] = uz * size
            model[12] = px; model[13] = py; model[14] = pz
            Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
            GLES30.glUniformMatrix4fv(uSpMvp, 1, false, mvp, 0)
            GLES30.glUniform4f(uSpTint, r, g, b, a)
            quad.draw()
        }

        GLES30.glDepthMask(true)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ------------------------------------------------------------- draw helpers

    /**
     * Walks every tile that should appear this frame — settled tiles, tiles in
     * flight, ghosts being absorbed, and anything Zen mode is dissolving —
     * handing the caller its interpolated position, scale, alpha and pop height.
     */
    private inline fun forEachDrawnTile(
        t: Float,
        body: (tile: Tile, x: Float, z: Float, scale: Float, alpha: Float, lift: Float) -> Unit
    ) {
        val slide = easeOutCubic((t / SLIDE_FRACTION).coerceIn(0f, 1f))

        for (tile in game.board.tiles) {
            val x = cellX(lerp(tile.prevCol.toFloat(), tile.col.toFloat(), slide))
            val z = cellZ(lerp(tile.prevRow.toFloat(), tile.row.toFloat(), slide))

            var scale = 1f
            var alpha = 1f
            var lift = 0f

            if (tile.spawned) {
                val s = ((t - SPAWN_START) / (1f - SPAWN_START)).coerceIn(0f, 1f)
                scale = lerp(0.18f, 1f, easeOutBack(s, 1.4f))
                alpha = smoothstep(0f, 0.45f, s)
            } else if (tile.mergedThisMove) {
                val s = ((t - SLIDE_FRACTION) / (1f - SLIDE_FRACTION)).coerceIn(0f, 1f)
                // Punch up to 1.18x then settle back to 1.
                scale = 1f + 0.18f * kotlin.math.sin(s * Math.PI.toFloat()) * (1f - s * 0.25f)
                lift = kotlin.math.sin(s * Math.PI.toFloat()) * (1f - s * 0.3f)
            }
            body(tile, x, z, scale, alpha, lift)
        }

        // Absorbed halves keep travelling under the survivor, then vanish.
        for (ghost in game.ghosts) {
            val x = cellX(lerp(ghost.prevCol.toFloat(), ghost.col.toFloat(), slide))
            val z = cellZ(lerp(ghost.prevRow.toFloat(), ghost.row.toFloat(), slide))
            val fade = 1f - smoothstep(SLIDE_FRACTION * 0.75f, SLIDE_FRACTION, t)
            if (fade <= 0.001f) continue
            body(ghost, x, z, 1f - 0.12f * (1f - fade), fade, 0f)
        }

        game.dissolving?.let { d ->
            val s = (game.dissolveTime / Game.DISSOLVE_DURATION).coerceIn(0f, 1f)
            body(
                d, cellX(d.col.toFloat()), cellZ(d.row.toFloat()),
                lerp(1f, 0.25f, s), 1f - s, s * 0.6f
            )
        }
    }

    private fun submitLit(roughness: Float, alpha: Float, emissive: Float) {
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        // Uniform scale only, so the upper 3x3 doubles as the normal matrix once
        // the scale is divided out.
        val sx = 1f / sqrt(model[0] * model[0] + model[1] * model[1] + model[2] * model[2])
        val sy = 1f / sqrt(model[4] * model[4] + model[5] * model[5] + model[6] * model[6])
        val sz = 1f / sqrt(model[8] * model[8] + model[9] * model[9] + model[10] * model[10])
        normalMat[0] = model[0] * sx; normalMat[1] = model[1] * sx; normalMat[2] = model[2] * sx
        normalMat[3] = model[4] * sy; normalMat[4] = model[5] * sy; normalMat[5] = model[6] * sy
        normalMat[6] = model[8] * sz; normalMat[7] = model[9] * sz; normalMat[8] = model[10] * sz

        GLES30.glUniformMatrix4fv(uLitMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uLitModel, 1, false, model, 0)
        GLES30.glUniformMatrix3fv(uLitNrm, 1, false, normalMat, 0)
        GLES30.glUniform3f(uLitBase, baseColor[0], baseColor[1], baseColor[2])
        GLES30.glUniform1f(uLitRough, roughness)
        GLES30.glUniform1f(uLitAlpha, alpha)
        GLES30.glUniform1f(uLitEmissive, emissive)
    }

    private fun bindSpriteTexture(tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(uSpTex, 0)
    }

    private fun cellX(col: Float) = (col - (grid - 1) * 0.5f) * cell
    private fun cellZ(row: Float) = (row - (grid - 1) * 0.5f) * cell

    private fun emitSparksIfNeeded() {
        val merges = game.mergePoints
        if (merges.isEmpty()) return
        // mergePoints is cleared when the timeline ends; fire the burst once, at
        // the moment the halves meet rather than when the swipe started.
        if (game.animTime < Game.MOVE_DURATION * SLIDE_FRACTION) return
        if (game.moveSerial == lastSparkedMove) return
        lastSparkedMove = game.moveSerial

        for (tile in merges) {
            Palette.tileColor(tile.exp, baseColor)
            particles.burst(
                cellX(tile.col.toFloat()), tileY + tileHeight, cellZ(tile.row.toFloat()),
                10 + tile.exp.coerceAtMost(8),
                baseColor[0], baseColor[1], baseColor[2], rng
            )
            energy = (energy + 0.25f + tile.exp * 0.02f).coerceAtMost(1.2f)
        }
    }

    // ------------------------------------------------------------ texture bakes

    /** Bakes every reachable number into one atlas so digits cost no draw setup. */
    private fun buildDigitAtlas(): Bitmap {
        val bmp = Bitmap.createBitmap(ATLAS_COLS * CELL_W, ATLAS_ROWS * CELL_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()

        for (exp in 1..Palette.MAX_EXP) {
            val idx = exp - 1
            val label = Palette.label(exp)
            val cx = (idx % ATLAS_COLS) * CELL_W + CELL_W / 2f
            val cy = (idx / ATLAS_COLS) * CELL_H + CELL_H / 2f

            // Size to the cell height first, then shrink if the digits run wide.
            paint.textSize = CELL_H * 0.72f
            paint.getTextBounds(label, 0, label.length, bounds)
            val maxW = CELL_W * 0.88f
            if (bounds.width() > maxW) paint.textSize *= maxW / bounds.width()
            paint.getTextBounds(label, 0, label.length, bounds)

            canvas.drawText(label, cx, cy - bounds.exactCenterY(), paint)
        }
        return bmp
    }

    /**
     * A soft radial dot used for both contact shadows and sparks. [falloff]
     * above 1 pulls the edge in, giving a tighter core and a longer tail.
     */
    private fun buildRadialSprite(size: Int, peakAlpha: Float, falloff: Float): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val stops = 8
        val colors = IntArray(stops)
        val positions = FloatArray(stops)
        for (i in 0 until stops) {
            val p = i / (stops - 1f)
            positions[i] = p
            val a = ((1f - p).coerceIn(0f, 1f)).let { Math.pow(it.toDouble(), falloff.toDouble()).toFloat() }
            colors[i] = Color.argb((a * peakAlpha * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size / 2f, size / 2f,
                colors, positions, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bmp
    }

    companion object {
        private const val ATLAS_COLS = 4
        private const val ATLAS_ROWS = 5
        private const val CELL_W = 256
        private const val CELL_H = 128

        /** Quad size for a number, matching the 2:1 atlas cell aspect. */
        private const val LABEL_W = 0.74f
        private const val LABEL_H = 0.37f

        /** Fraction of the move timeline spent sliding; the rest is the pop. */
        private const val SLIDE_FRACTION = 0.62f
        private const val SPAWN_START = 0.45f

        private const val LUMEN_SEED = 0x4C554D45L
    }
}
