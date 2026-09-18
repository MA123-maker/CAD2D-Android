package com.example.cad2d.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val EPS = 1e-6

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Double) = Vec2(x * scale, y * scale)
}

data class Bounds2D(val min: Vec2, val max: Vec2) {
    fun contains(point: Vec2): Boolean =
        point.x in min.x..max.x && point.y in min.y..max.y

    fun expanded(padding: Double): Bounds2D = Bounds2D(
        Vec2(min.x - padding, min.y - padding),
        Vec2(max.x + padding, max.y + padding)
    )
}

data class Transform2D(
    val translate: Vec2 = Vec2(0.0, 0.0),
    val scale: Double = 1.0,
    val rotateDegrees: Double = 0.0,
    val pivot: Vec2 = Vec2(0.0, 0.0)
)

enum class SnapType { ENDPOINT, MIDPOINT, INTERSECTION, ORTHOGONAL, GRID, NONE }

data class SnapResult(val point: Vec2, val type: SnapType)

data class Layer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val color: Int = 0xFFE6EDF5.toInt()
)

sealed class CadEntity {
    abstract val id: String
    abstract val layerId: String
    abstract fun transformed(transform: Transform2D): CadEntity
    abstract fun bounds(): Bounds2D
    abstract fun snapCandidates(): List<Pair<SnapType, Vec2>>
}

data class LineEntity(
    override val id: String,
    override val layerId: String,
    val a: Vec2,
    val b: Vec2
) : CadEntity() {
    override fun transformed(transform: Transform2D): CadEntity =
        copy(a = a.transform(transform), b = b.transform(transform))

    override fun bounds(): Bounds2D = Bounds2D(
        Vec2(min(a.x, b.x), min(a.y, b.y)),
        Vec2(max(a.x, b.x), max(a.y, b.y))
    )

    override fun snapCandidates(): List<Pair<SnapType, Vec2>> = listOf(
        SnapType.ENDPOINT to a,
        SnapType.ENDPOINT to b,
        SnapType.MIDPOINT to Vec2((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
    )
}

data class CircleEntity(
    override val id: String,
    override val layerId: String,
    val center: Vec2,
    val radius: Double
) : CadEntity() {
    override fun transformed(transform: Transform2D): CadEntity =
        copy(center = center.transform(transform), radius = radius * transform.scale)

    override fun bounds(): Bounds2D = Bounds2D(
        Vec2(center.x - radius, center.y - radius),
        Vec2(center.x + radius, center.y + radius)
    )

    override fun snapCandidates(): List<Pair<SnapType, Vec2>> = listOf(
        SnapType.ENDPOINT to Vec2(center.x + radius, center.y),
        SnapType.ENDPOINT to Vec2(center.x - radius, center.y),
        SnapType.ENDPOINT to Vec2(center.x, center.y + radius),
        SnapType.ENDPOINT to Vec2(center.x, center.y - radius),
        SnapType.MIDPOINT to center
    )
}

data class ArcEntity(
    override val id: String,
    override val layerId: String,
    val center: Vec2,
    val radius: Double,
    val startAngleDeg: Double,
    val endAngleDeg: Double
) : CadEntity() {
    override fun transformed(transform: Transform2D): CadEntity = copy(
        center = center.transform(transform),
        radius = radius * transform.scale,
        startAngleDeg = startAngleDeg + transform.rotateDegrees,
        endAngleDeg = endAngleDeg + transform.rotateDegrees
    )

    override fun bounds(): Bounds2D = Bounds2D(
        Vec2(center.x - radius, center.y - radius),
        Vec2(center.x + radius, center.y + radius)
    )

    override fun snapCandidates(): List<Pair<SnapType, Vec2>> {
        val start = pointOnCircle(center, radius, startAngleDeg)
        val end = pointOnCircle(center, radius, endAngleDeg)
        return listOf(
            SnapType.ENDPOINT to start,
            SnapType.ENDPOINT to end,
            SnapType.MIDPOINT to pointOnCircle(center, radius, (startAngleDeg + endAngleDeg) / 2.0)
        )
    }
}

data class PolylineEntity(
    override val id: String,
    override val layerId: String,
    val points: List<Vec2>,
    val closed: Boolean = false
) : CadEntity() {
    override fun transformed(transform: Transform2D): CadEntity =
        copy(points = points.map { it.transform(transform) })

    override fun bounds(): Bounds2D {
        val minX = points.minOfOrNull { it.x } ?: 0.0
        val minY = points.minOfOrNull { it.y } ?: 0.0
        val maxX = points.maxOfOrNull { it.x } ?: 0.0
        val maxY = points.maxOfOrNull { it.y } ?: 0.0
        return Bounds2D(Vec2(minX, minY), Vec2(maxX, maxY))
    }

    override fun snapCandidates(): List<Pair<SnapType, Vec2>> {
        if (points.isEmpty()) return emptyList()
        val pointsSnap = points.map { SnapType.ENDPOINT to it }
        val mids = points.zipWithNext().map { (a, b) ->
            SnapType.MIDPOINT to Vec2((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
        }
        return pointsSnap + mids
    }
}

data class DimensionEntity(
    override val id: String,
    override val layerId: String,
    val from: Vec2,
    val to: Vec2,
    val offset: Double,
    val text: String
) : CadEntity() {
    override fun transformed(transform: Transform2D): CadEntity = copy(
        from = from.transform(transform),
        to = to.transform(transform),
        offset = offset * transform.scale
    )

    override fun bounds(): Bounds2D = Bounds2D(
        Vec2(min(from.x, to.x), min(from.y, to.y)),
        Vec2(max(from.x, to.x), max(from.y, to.y))
    ).expanded(abs(offset))

    override fun snapCandidates(): List<Pair<SnapType, Vec2>> = listOf(
        SnapType.ENDPOINT to from,
        SnapType.ENDPOINT to to,
        SnapType.MIDPOINT to Vec2((from.x + to.x) / 2.0, (from.y + to.y) / 2.0)
    )
}

fun Vec2.distanceTo(other: Vec2): Double = hypot(x - other.x, y - other.y)

fun Vec2.transform(transform: Transform2D): Vec2 {
    val shifted = this - transform.pivot
    val rad = Math.toRadians(transform.rotateDegrees)
    val rotated = Vec2(
        shifted.x * cos(rad) - shifted.y * sin(rad),
        shifted.x * sin(rad) + shifted.y * cos(rad)
    )
    return rotated * transform.scale + transform.pivot + transform.translate
}

fun pointOnCircle(center: Vec2, radius: Double, angleDeg: Double): Vec2 {
    val rad = Math.toRadians(angleDeg)
    return Vec2(center.x + radius * cos(rad), center.y + radius * sin(rad))
}

fun angleOf(center: Vec2, point: Vec2): Double = Math.toDegrees(atan2(point.y - center.y, point.x - center.x))

fun lineIntersection(l1: LineEntity, l2: LineEntity): Vec2? {
    val x1 = l1.a.x
    val y1 = l1.a.y
    val x2 = l1.b.x
    val y2 = l1.b.y
    val x3 = l2.a.x
    val y3 = l2.a.y
    val x4 = l2.b.x
    val y4 = l2.b.y

    val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    if (abs(den) < EPS) return null

    val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / den
    val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / den
    return Vec2(px, py)
}

fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 < EPS) return p.distanceTo(a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    val proj = Vec2(a.x + t * dx, a.y + t * dy)
    return p.distanceTo(proj)
}
