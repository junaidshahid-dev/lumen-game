package com.junaidshahid.lumen

import kotlin.random.Random

/**
 * A fixed-capacity spark pool, stored as parallel arrays so a burst allocates
 * nothing on the GL thread.
 *
 * Live particles are kept packed at the front of the arrays: when one dies it is
 * swapped with the last live entry, which keeps [forEach] a straight walk.
 */
class Particles(private val capacity: Int) {

    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val vz = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val maxLife = FloatArray(capacity)
    private val size = FloatArray(capacity)
    private val cr = FloatArray(capacity)
    private val cg = FloatArray(capacity)
    private val cb = FloatArray(capacity)

    var alive = 0
        private set

    fun burst(
        ox: Float, oy: Float, oz: Float, count: Int,
        r: Float, g: Float, b: Float, rng: Random
    ) {
        repeat(count) {
            if (alive >= capacity) return
            val i = alive++
            val angle = rng.nextFloat() * (Math.PI * 2).toFloat()
            val speed = 0.9f + rng.nextFloat() * 1.5f
            x[i] = ox + (rng.nextFloat() - 0.5f) * 0.16f
            y[i] = oy + rng.nextFloat() * 0.05f
            z[i] = oz + (rng.nextFloat() - 0.5f) * 0.16f
            vx[i] = kotlin.math.cos(angle) * speed * 0.55f
            vz[i] = kotlin.math.sin(angle) * speed * 0.55f
            vy[i] = 1.1f + rng.nextFloat() * 1.4f
            maxLife[i] = 0.45f + rng.nextFloat() * 0.35f
            life[i] = maxLife[i]
            size[i] = 0.09f + rng.nextFloat() * 0.10f
            // Lift the spark colour well above the tile colour so it reads as light.
            cr[i] = (r * 0.4f + 0.75f).coerceAtMost(1f)
            cg[i] = (g * 0.4f + 0.80f).coerceAtMost(1f)
            cb[i] = (b * 0.4f + 0.85f).coerceAtMost(1f)
        }
    }

    fun advance(dt: Float) {
        var i = 0
        while (i < alive) {
            life[i] -= dt
            if (life[i] <= 0f) {
                val last = --alive
                if (i != last) swap(i, last)
                continue
            }
            vy[i] -= 5.2f * dt          // gravity
            val drag = 1f - 2.2f * dt
            vx[i] *= drag
            vz[i] *= drag
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            z[i] += vz[i] * dt
            i++
        }
    }

    inline fun forEach(
        body: (x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float, a: Float) -> Unit
    ) {
        for (i in 0 until alive) {
            val t = lifeFraction(i)
            // Grow slightly while fading, so a spark dissolves rather than blinks.
            body(px(i), py(i), pz(i), psize(i) * (1.35f - 0.35f * t), pr(i), pg(i), pb(i), t * t)
        }
    }

    @PublishedApi internal fun px(i: Int) = x[i]
    @PublishedApi internal fun py(i: Int) = y[i]
    @PublishedApi internal fun pz(i: Int) = z[i]
    @PublishedApi internal fun psize(i: Int) = size[i]
    @PublishedApi internal fun pr(i: Int) = cr[i]
    @PublishedApi internal fun pg(i: Int) = cg[i]
    @PublishedApi internal fun pb(i: Int) = cb[i]
    @PublishedApi internal fun lifeFraction(i: Int) = (life[i] / maxLife[i]).coerceIn(0f, 1f)

    private fun swap(a: Int, b: Int) {
        fun sw(arr: FloatArray) { val t = arr[a]; arr[a] = arr[b]; arr[b] = t }
        sw(x); sw(y); sw(z); sw(vx); sw(vy); sw(vz)
        sw(life); sw(maxLife); sw(size); sw(cr); sw(cg); sw(cb)
    }
}
