package com.example.cad2d.core

import java.util.UUID

data class CadDocument(
    val version: Int = 1,
    val name: String = "Untitled",
    val layers: List<Layer> = listOf(defaultLayer()),
    val activeLayerId: String = defaultLayer().id,
    val entities: List<CadEntity> = emptyList()
) {
    fun deepCopy(): CadDocument = copy(
        layers = layers.toList(),
        entities = entities.map { entity ->
            when (entity) {
                is LineEntity -> entity.copy()
                is CircleEntity -> entity.copy()
                is ArcEntity -> entity.copy()
                is PolylineEntity -> entity.copy(points = entity.points.toList())
                is DimensionEntity -> entity.copy()
            }
        }
    )

    fun findEntity(id: String): CadEntity? = entities.firstOrNull { it.id == id }

    companion object {
        fun defaultLayer() = Layer(
            id = "layer-0",
            name = "Layer 0",
            visible = true,
            locked = false,
            color = 0xFFE6EDF5.toInt()
        )

        fun newLayer(name: String, color: Int = 0xFF56B6C2.toInt()): Layer = Layer(
            id = "layer-${UUID.randomUUID()}",
            name = name,
            visible = true,
            locked = false,
            color = color
        )

        fun newEntityId(): String = UUID.randomUUID().toString()
    }
}
