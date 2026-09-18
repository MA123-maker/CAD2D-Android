package com.example.cad2d.core

import kotlin.math.abs

class SnapEngine(
    private val gridStep: Double = 20.0,
    private val snapTolerance: Double = 14.0
) {
    fun snap(document: CadDocument, raw: Vec2, orthoAnchor: Vec2? = null): SnapResult {
        val candidates = mutableListOf<SnapResult>()

        document.entities.forEach { entity ->
            val visible = document.layers.firstOrNull { it.id == entity.layerId }?.visible ?: true
            if (!visible) return@forEach
            entity.snapCandidates().forEach { (type, point) ->
                if (point.distanceTo(raw) <= snapTolerance) {
                    candidates += SnapResult(point, type)
                }
            }
        }

        val lineEntities = document.entities.filterIsInstance<LineEntity>()
        for (i in 0 until lineEntities.size) {
            for (j in i + 1 until lineEntities.size) {
                val point = lineIntersection(lineEntities[i], lineEntities[j]) ?: continue
                if (point.distanceTo(raw) <= snapTolerance) {
                    candidates += SnapResult(point, SnapType.INTERSECTION)
                }
            }
        }

        orthoAnchor?.let { anchor ->
            val ortho = if (abs(raw.x - anchor.x) < abs(raw.y - anchor.y)) {
                Vec2(anchor.x, raw.y)
            } else {
                Vec2(raw.x, anchor.y)
            }
            if (ortho.distanceTo(raw) <= snapTolerance * 1.5) {
                candidates += SnapResult(ortho, SnapType.ORTHOGONAL)
            }
        }

        val grid = Vec2(
            kotlin.math.round(raw.x / gridStep) * gridStep,
            kotlin.math.round(raw.y / gridStep) * gridStep
        )
        candidates += SnapResult(grid, SnapType.GRID)

        return candidates.minByOrNull { it.point.distanceTo(raw) } ?: SnapResult(raw, SnapType.NONE)
    }
}
