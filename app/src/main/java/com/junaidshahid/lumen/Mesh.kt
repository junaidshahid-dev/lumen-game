package com.junaidshahid.lumen

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** An indexed triangle mesh of interleaved position + normal, living in a VAO. */
class Mesh(vertices: FloatArray, indices: IntArray) {

    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    private val indexCount = indices.size

    init {
        val vb = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(vertices).apply { position(0) }
        val ib = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder())
            .asIntBuffer().put(indices).apply { position(0) }

        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, ib, GLES30.GL_STATIC_DRAW
        )

        val stride = STRIDE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)

        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    companion object {
        const val STRIDE_FLOATS = 6
    }
}

object MeshFactory {

    /**
     * A rounded box, built by sweeping a rounded-rectangle outline through a
     * vertical profile that is itself rounded at the top and bottom rim.
     *
     * Doing it as a sweep rather than as six faces plus corner patches keeps the
     * normals continuous all the way around, which is what makes the specular
     * highlight travel smoothly over an edge instead of snapping.
     *
     * @param hx half extent on X
     * @param hy half height on Y (the extrusion axis)
     * @param hz half extent on Z
     * @param corner rounding of the four vertical corners, in the XZ plane
     * @param rim rounding of the top and bottom edges
     */
    fun roundedBox(
        hx: Float,
        hy: Float,
        hz: Float,
        corner: Float,
        rim: Float,
        cornerSegments: Int = 6,
        rimSegments: Int = 4
    ): Mesh {
        val r = rim.coerceAtMost(minOf(hy, corner * 0.98f))
        val cr = corner.coerceAtMost(minOf(hx, hz))

        // --- outline: positions and outward normals in the XZ plane ---
        val m = 4 * (cornerSegments + 1)
        val ox = FloatArray(m)
        val oz = FloatArray(m)
        val onx = FloatArray(m)
        val onz = FloatArray(m)

        val centers = arrayOf(
            floatArrayOf(hx - cr, hz - cr),
            floatArrayOf(-(hx - cr), hz - cr),
            floatArrayOf(-(hx - cr), -(hz - cr)),
            floatArrayOf(hx - cr, -(hz - cr))
        )
        var w = 0
        for (q in 0 until 4) {
            val base = q * (Math.PI / 2.0)
            for (s in 0..cornerSegments) {
                val a = base + (Math.PI / 2.0) * s / cornerSegments
                val nx = cos(a).toFloat()
                val nz = sin(a).toFloat()
                onx[w] = nx
                onz[w] = nz
                ox[w] = centers[q][0] + cr * nx
                oz[w] = centers[q][1] + cr * nz
                w++
            }
        }

        // --- vertical profile: bottom rim, then top rim. The straight side wall
        // falls out of the join between the two, since both ends sit at inset 0. ---
        val ringCount = 2 * (rimSegments + 1)
        val ringY = FloatArray(ringCount)
        val ringInset = FloatArray(ringCount)
        val ringSin = FloatArray(ringCount)
        val ringCos = FloatArray(ringCount)

        var ri = 0
        for (s in 0..rimSegments) {
            val a = -Math.PI / 2.0 + (Math.PI / 2.0) * s / rimSegments
            ringSin[ri] = sin(a).toFloat()
            ringCos[ri] = cos(a).toFloat()
            ringY[ri] = -(hy - r) + r * ringSin[ri]
            ringInset[ri] = r - r * ringCos[ri]
            ri++
        }
        for (s in 0..rimSegments) {
            val a = (Math.PI / 2.0) * s / rimSegments
            ringSin[ri] = sin(a).toFloat()
            ringCos[ri] = cos(a).toFloat()
            ringY[ri] = (hy - r) + r * ringSin[ri]
            ringInset[ri] = r - r * ringCos[ri]
            ri++
        }

        val verts = ArrayList<Float>((ringCount * m + 2) * Mesh.STRIDE_FLOATS)
        fun push(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float) {
            val len = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
            verts.add(px); verts.add(py); verts.add(pz)
            verts.add(nx / len); verts.add(ny / len); verts.add(nz / len)
        }

        for (j in 0 until ringCount) {
            val inset = ringInset[j]
            for (k in 0 until m) {
                push(
                    ox[k] - onx[k] * inset, ringY[j], oz[k] - onz[k] * inset,
                    onx[k] * ringCos[j], ringSin[j], onz[k] * ringCos[j]
                )
            }
        }
        val topCenter = ringCount * m
        push(0f, hy, 0f, 0f, 1f, 0f)
        val bottomCenter = topCenter + 1
        push(0f, -hy, 0f, 0f, -1f, 0f)

        val idx = ArrayList<Int>(ringCount * m * 6 + m * 6)
        for (j in 0 until ringCount - 1) {
            val a = j * m
            val b = (j + 1) * m
            for (k in 0 until m) {
                val k2 = (k + 1) % m
                idx.add(a + k); idx.add(b + k); idx.add(b + k2)
                idx.add(a + k); idx.add(b + k2); idx.add(a + k2)
            }
        }
        val topRing = (ringCount - 1) * m
        for (k in 0 until m) {
            val k2 = (k + 1) % m
            idx.add(topCenter); idx.add(topRing + k); idx.add(topRing + k2)
        }
        for (k in 0 until m) {
            val k2 = (k + 1) % m
            idx.add(bottomCenter); idx.add(k2); idx.add(k)
        }

        return Mesh(verts.toFloatArray(), idx.toIntArray())
    }

    /** Unit quad on the XZ plane facing +Y, spanning -0.5..0.5, with UVs in the normal slot. */
    fun quadXZ(): Mesh {
        val v = floatArrayOf(
            -0.5f, 0f, -0.5f, 0f, 0f, 0f,
            0.5f, 0f, -0.5f, 1f, 0f, 0f,
            0.5f, 0f, 0.5f, 1f, 1f, 0f,
            -0.5f, 0f, 0.5f, 0f, 1f, 0f
        )
        return Mesh(v, intArrayOf(0, 1, 2, 0, 2, 3))
    }
}
