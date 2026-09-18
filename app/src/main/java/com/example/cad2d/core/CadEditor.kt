package com.example.cad2d.core

import kotlin.math.abs

class CadEditor(initial: CadDocument = CadDocument()) {
    var document: CadDocument = initial.deepCopy()
        private set

    private val undoStack = ArrayDeque<CadDocument>()
    private val redoStack = ArrayDeque<CadDocument>()

    var selectedIds: Set<String> = emptySet()
        private set

    fun execute(update: (CadDocument) -> CadDocument) {
        undoStack.addLast(document.deepCopy())
        document = update(document.deepCopy())
        redoStack.clear()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(document.deepCopy())
        document = previous
        selectedIds = selectedIds.filterTo(mutableSetOf()) { id -> document.findEntity(id) != null }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(document.deepCopy())
        document = next
        selectedIds = selectedIds.filterTo(mutableSetOf()) { id -> document.findEntity(id) != null }
    }

    fun selectByPoint(point: Vec2, tolerance: Double): String? {
        val hit = document.entities.reversed().firstOrNull { entity -> hitEntity(entity, point, tolerance) }
        selectedIds = if (hit == null) emptySet() else setOf(hit.id)
        return hit?.id
    }

    fun selectByBounds(bounds: Bounds2D) {
        selectedIds = document.entities
            .filter { bounds.expanded(0.1).contains(it.bounds().min) || bounds.expanded(0.1).contains(it.bounds().max) }
            .map { it.id }
            .toSet()
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    fun addLayer(name: String): Layer {
        val layer = CadDocument.newLayer(name)
        execute { doc ->
            doc.copy(layers = doc.layers + layer, activeLayerId = layer.id)
        }
        return layer
    }

    fun setActiveLayer(layerId: String) {
        if (document.layers.none { it.id == layerId }) return
        execute { doc -> doc.copy(activeLayerId = layerId) }
    }

    fun addEntity(entity: CadEntity) {
        execute { doc -> doc.copy(entities = doc.entities + entity) }
    }

    fun deleteSelection() {
        if (selectedIds.isEmpty()) return
        execute { doc -> doc.copy(entities = doc.entities.filterNot { it.id in selectedIds }) }
        clearSelection()
    }

    fun moveSelection(delta: Vec2) {
        transformSelection(Transform2D(translate = delta))
    }

    fun copySelection(delta: Vec2) {
        if (selectedIds.isEmpty()) return
        execute { doc ->
            val copies = doc.entities.filter { it.id in selectedIds }.map { entity ->
                when (val moved = entity.transformed(Transform2D(translate = delta))) {
                    is LineEntity -> moved.copy(id = CadDocument.newEntityId())
                    is CircleEntity -> moved.copy(id = CadDocument.newEntityId())
                    is ArcEntity -> moved.copy(id = CadDocument.newEntityId())
                    is PolylineEntity -> moved.copy(id = CadDocument.newEntityId())
                    is DimensionEntity -> moved.copy(id = CadDocument.newEntityId())
                    else -> moved
                }
            }
            doc.copy(entities = doc.entities + copies)
        }
    }

    fun rotateSelection(pivot: Vec2, degrees: Double) {
        transformSelection(Transform2D(rotateDegrees = degrees, pivot = pivot))
    }

    fun mirrorSelection(a: Vec2, b: Vec2) {
        if (selectedIds.isEmpty()) return
        execute { doc ->
            doc.copy(entities = doc.entities.map { entity ->
                if (entity.id !in selectedIds) return@map entity
                mirror(entity, a, b)
            })
        }
    }

    fun trimSelection(cutPoint: Vec2) {
        if (selectedIds.size != 1) return
        val targetId = selectedIds.first()
        execute { doc ->
            val updated = doc.entities.map { entity ->
                if (entity.id != targetId) return@map entity
                if (entity !is LineEntity) return@map entity
                val da = entity.a.distanceTo(cutPoint)
                val db = entity.b.distanceTo(cutPoint)
                if (da < db) entity.copy(a = cutPoint) else entity.copy(b = cutPoint)
            }
            doc.copy(entities = updated)
        }
    }

    fun extendSelection(toPoint: Vec2) {
        if (selectedIds.size != 1) return
        val targetId = selectedIds.first()
        execute { doc ->
            val updated = doc.entities.map { entity ->
                if (entity.id != targetId || entity !is LineEntity) return@map entity
                val da = entity.a.distanceTo(toPoint)
                val db = entity.b.distanceTo(toPoint)
                if (da < db) entity.copy(a = toPoint) else entity.copy(b = toPoint)
            }
            doc.copy(entities = updated)
        }
    }

    fun offsetSelection(distance: Double) {
        if (selectedIds.isEmpty()) return
        execute { doc ->
            val additions = mutableListOf<CadEntity>()
            doc.entities.forEach { entity ->
                if (entity.id !in selectedIds) return@forEach
                when (entity) {
                    is LineEntity -> {
                        val dx = entity.b.x - entity.a.x
                        val dy = entity.b.y - entity.a.y
                        val len = kotlin.math.hypot(dx, dy)
                        if (len > 1e-6) {
                            val ox = -dy / len * distance
                            val oy = dx / len * distance
                            additions += entity.copy(
                                id = CadDocument.newEntityId(),
                                a = Vec2(entity.a.x + ox, entity.a.y + oy),
                                b = Vec2(entity.b.x + ox, entity.b.y + oy)
                            )
                        }
                    }
                    is CircleEntity -> additions += entity.copy(
                        id = CadDocument.newEntityId(),
                        radius = abs(entity.radius + distance)
                    )
                    is PolylineEntity -> additions += entity.copy(
                        id = CadDocument.newEntityId(),
                        points = entity.points.map { it + Vec2(distance, distance) }
                    )
                    else -> Unit
                }
            }
            doc.copy(entities = doc.entities + additions)
        }
    }

    fun updateName(name: String) {
        execute { doc -> doc.copy(name = name) }
    }

    fun replaceDocument(newDocument: CadDocument) {
        undoStack.clear()
        redoStack.clear()
        document = newDocument.deepCopy()
        selectedIds = emptySet()
    }

    private fun transformSelection(transform: Transform2D) {
        if (selectedIds.isEmpty()) return
        execute { doc ->
            doc.copy(entities = doc.entities.map { entity ->
                if (entity.id !in selectedIds) entity else entity.transformed(transform)
            })
        }
    }

    private fun mirror(entity: CadEntity, a: Vec2, b: Vec2): CadEntity {
        fun mirrorPoint(p: Vec2): Vec2 {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-9) return p
            val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
            val proj = Vec2(a.x + t * dx, a.y + t * dy)
            return Vec2(2 * proj.x - p.x, 2 * proj.y - p.y)
        }

        return when (entity) {
            is LineEntity -> entity.copy(a = mirrorPoint(entity.a), b = mirrorPoint(entity.b))
            is CircleEntity -> entity.copy(center = mirrorPoint(entity.center))
            is ArcEntity -> entity.copy(center = mirrorPoint(entity.center))
            is PolylineEntity -> entity.copy(points = entity.points.map(::mirrorPoint))
            is DimensionEntity -> entity.copy(from = mirrorPoint(entity.from), to = mirrorPoint(entity.to))
        }
    }

    private fun hitEntity(entity: CadEntity, point: Vec2, tolerance: Double): Boolean = when (entity) {
        is LineEntity -> distanceToSegment(point, entity.a, entity.b) <= tolerance
        is CircleEntity -> kotlin.math.abs(entity.center.distanceTo(point) - entity.radius) <= tolerance
        is ArcEntity -> kotlin.math.abs(entity.center.distanceTo(point) - entity.radius) <= tolerance
        is PolylineEntity -> entity.points.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= tolerance }
        is DimensionEntity -> distanceToSegment(point, entity.from, entity.to) <= tolerance
    }
}
