package com.calico.roomscan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vectors and quaternions for the pose retargeter.
 *
 * Vectors are plain [FloatArray] of 3, quaternions of 4 laid out (x, y, z, w) to
 * match glTF. Matrices are column major 16-float arrays, same as android.opengl.Matrix,
 * so results can be handed straight to GL. Nothing here touches the Android SDK, which
 * keeps the retargeting maths unit testable on the JVM.
 */
object V3 {
    fun of(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)

    fun mid(a: FloatArray, b: FloatArray) =
        floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun length(a: FloatArray) = sqrt(dot(a, a))

    /** Unit vector, or +Y for a degenerate input so callers never see NaN. */
    fun normalize(a: FloatArray): FloatArray {
        val len = length(a)
        return if (len < 1e-6f) floatArrayOf(0f, 1f, 0f) else scale(a, 1f / len)
    }

    /** Component of [a] perpendicular to unit vector [axis]. */
    fun reject(a: FloatArray, axis: FloatArray) = sub(a, scale(axis, dot(a, axis)))
}

object Quat {
    fun identity() = floatArrayOf(0f, 0f, 0f, 1f)

    /** Hamilton product: the rotation of applying [b] first, then [a]. */
    fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    /** Inverse of a unit quaternion, which is just its conjugate. */
    fun inverse(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    fun normalize(q: FloatArray): FloatArray {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (len < 1e-9f) identity() else floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
    }

    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val u = floatArrayOf(q[0], q[1], q[2])
        val w = q[3]
        val a = V3.scale(u, 2f * V3.dot(u, v))
        val b = V3.scale(v, w * w - V3.dot(u, u))
        val c = V3.scale(V3.cross(u, v), 2f * w)
        return V3.add(V3.add(a, b), c)
    }

    fun axisAngle(axis: FloatArray, radians: Float): FloatArray {
        val n = V3.normalize(axis)
        val s = sin(radians / 2f)
        return floatArrayOf(n[0] * s, n[1] * s, n[2] * s, cos(radians / 2f))
    }

    /**
     * Shortest rotation taking [from] onto [to]. The antiparallel case has no shortest
     * arc, so any perpendicular axis is picked rather than returning NaN.
     */
    fun fromTo(from: FloatArray, to: FloatArray): FloatArray {
        val a = V3.normalize(from)
        val b = V3.normalize(to)
        val d = V3.dot(a, b).coerceIn(-1f, 1f)
        if (d > 0.999999f) return identity()
        if (d < -0.999999f) {
            val other = if (abs(a[0]) < 0.9f) V3.of(1f, 0f, 0f) else V3.of(0f, 1f, 0f)
            return axisAngle(V3.cross(a, other), Math.PI.toFloat())
        }
        val axis = V3.cross(a, b)
        return normalize(floatArrayOf(axis[0], axis[1], axis[2], 1f + d))
    }

    /** Rotation part of a column major matrix, assuming positive scale. */
    fun fromMatrix(m: FloatArray): FloatArray {
        val sx = V3.length(floatArrayOf(m[0], m[1], m[2]))
        val sy = V3.length(floatArrayOf(m[4], m[5], m[6]))
        val sz = V3.length(floatArrayOf(m[8], m[9], m[10]))
        if (sx < 1e-9f || sy < 1e-9f || sz < 1e-9f) return identity()
        val r = floatArrayOf(
            m[0] / sx, m[1] / sx, m[2] / sx,
            m[4] / sy, m[5] / sy, m[6] / sy,
            m[8] / sz, m[9] / sz, m[10] / sz,
        )
        val trace = r[0] + r[4] + r[8]
        return normalize(
            when {
                trace > 0f -> {
                    val s = sqrt(trace + 1f) * 2f
                    floatArrayOf((r[5] - r[7]) / s, (r[6] - r[2]) / s, (r[1] - r[3]) / s, s / 4f)
                }
                r[0] > r[4] && r[0] > r[8] -> {
                    val s = sqrt(1f + r[0] - r[4] - r[8]) * 2f
                    floatArrayOf(s / 4f, (r[3] + r[1]) / s, (r[6] + r[2]) / s, (r[5] - r[7]) / s)
                }
                r[4] > r[8] -> {
                    val s = sqrt(1f + r[4] - r[0] - r[8]) * 2f
                    floatArrayOf((r[3] + r[1]) / s, s / 4f, (r[7] + r[5]) / s, (r[6] - r[2]) / s)
                }
                else -> {
                    val s = sqrt(1f + r[8] - r[0] - r[4]) * 2f
                    floatArrayOf((r[6] + r[2]) / s, (r[7] + r[5]) / s, s / 4f, (r[1] - r[3]) / s)
                }
            }
        )
    }

    /** Column major rotation matrix. */
    fun toMatrix(q: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        return floatArrayOf(
            1f - 2f * (y * y + z * z), 2f * (x * y + z * w), 2f * (x * z - y * w), 0f,
            2f * (x * y - z * w), 1f - 2f * (x * x + z * z), 2f * (y * z + x * w), 0f,
            2f * (x * z + y * w), 2f * (y * z - x * w), 1f - 2f * (x * x + y * y), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /**
     * Rotation whose local axes are the given orthonormal columns.
     * The caller is responsible for the basis actually being orthonormal.
     */
    fun fromBasis(xAxis: FloatArray, yAxis: FloatArray, zAxis: FloatArray) = fromMatrix(
        floatArrayOf(
            xAxis[0], xAxis[1], xAxis[2], 0f,
            yAxis[0], yAxis[1], yAxis[2], 0f,
            zAxis[0], zAxis[1], zAxis[2], 0f,
            0f, 0f, 0f, 1f,
        )
    )
}

/** Column major 4x4 helpers, mirroring android.opengl.Matrix but usable off device. */
object M4 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        multiplyInto(a, b, out)
        return out
    }

    /** Output must not alias either input. Useful for the per-frame joint palette. */
    fun multiplyInto(a: FloatArray, b: FloatArray, out: FloatArray, offset: Int = 0) {
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            out[offset + col * 4 + row] = sum
        }
    }

    /** Translation, rotation and scale composed in the order glTF defines. */
    fun trs(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
        val m = Quat.toMatrix(r)
        for (col in 0 until 3) for (row in 0 until 3) m[col * 4 + row] *= s[col]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]
        return m
    }

    fun translation(m: FloatArray) = floatArrayOf(m[12], m[13], m[14])

    fun transformPoint(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )

    fun scaling(s: Float) = floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f)
}
