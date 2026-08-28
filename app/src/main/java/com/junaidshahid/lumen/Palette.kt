package com.junaidshahid.lumen

/**
 * A single low-saturation ramp that walks cool -> green -> warm as the numbers
 * climb. Nothing in it is fully saturated: the point is that a long session
 * should stay easy to look at.
 */
object Palette {

    const val MAX_EXP = 17

    private val ramp = intArrayOf(
        0x2F5D74, // 2
        0x2E7286, // 4
        0x2E8A8A, // 8
        0x34A08B, // 16
        0x4FB58A, // 32
        0x7BC98A, // 64
        0xA9D98D, // 128
        0xD8E08E, // 256
        0xEFCF86, // 512
        0xF0B075, // 1024
        0xEE8F73, // 2048
        0xE37187, // 4096
        0xC766A6, // 8192
        0x9E63BC, // 16384
        0x7268C9, // 32768
        0x5B7BD6, // 65536
        0x63A5E0  // 131072
    )

    /** Linear-space RGB for the tile body, written into [out] as three floats. */
    fun tileColor(exp: Int, out: FloatArray) {
        val rgb = ramp[(exp - 1).coerceIn(0, ramp.size - 1)]
        out[0] = srgbToLinear(((rgb shr 16) and 0xFF) / 255f)
        out[1] = srgbToLinear(((rgb shr 8) and 0xFF) / 255f)
        out[2] = srgbToLinear((rgb and 0xFF) / 255f)
    }

    /** ARGB int for the 2D overlay, which draws in plain sRGB. */
    fun tileArgb(exp: Int): Int =
        0xFF000000.toInt() or ramp[(exp - 1).coerceIn(0, ramp.size - 1)]

    /**
     * How much the tile glows from within. Kept near zero for small numbers so
     * that reaching a high tile actually reads as an event.
     */
    fun emissive(exp: Int): Float = when {
        exp <= 5 -> 0.05f
        exp <= 8 -> 0.10f
        exp <= 10 -> 0.18f
        else -> (0.18f + (exp - 10) * 0.06f).coerceAtMost(0.55f)
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    fun label(exp: Int): String = (1L shl exp).toString()
}
