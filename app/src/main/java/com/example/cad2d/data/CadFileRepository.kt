package com.example.cad2d.data

import com.example.cad2d.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CadFileRepository(private val baseDir: File) {

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    fun listDocuments(): List<String> = baseDir
        .listFiles { file -> file.isFile && file.extension == "cad2d" }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        ?: emptyList()

    fun newDocument(name: String = "Untitled"): CadDocument = CadDocument(name = name)

    fun save(document: CadDocument, fileName: String): File {
        val sanitized = fileName.ifBlank { "Untitled" }.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val target = File(baseDir, "$sanitized.cad2d")
        target.writeText(serialize(document), Charsets.UTF_8)
        return target
    }

    fun open(fileName: String): CadDocument {
        val target = File(baseDir, "$fileName.cad2d")
        return deserialize(target.readText(Charsets.UTF_8))
    }

    fun serialize(document: CadDocument): String {
        val root = JSONObject()
        root.put("version", document.version)
        root.put("name", document.name)
        root.put("activeLayerId", document.activeLayerId)
        root.put("layers", JSONArray().apply {
            document.layers.forEach { layer ->
                put(JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("visible", layer.visible)
                    put("locked", layer.locked)
                    put("color", layer.color)
                })
            }
        })
        root.put("entities", JSONArray().apply {
            document.entities.forEach { entity ->
                put(entityToJson(entity))
            }
        })
        return root.toString(2)
    }

    fun deserialize(raw: String): CadDocument {
        val root = JSONObject(raw)
        val version = root.optInt("version", 1)
        val layersJson = root.optJSONArray("layers") ?: JSONArray()
        val layers = buildList {
            for (i in 0 until layersJson.length()) {
                val layer = layersJson.getJSONObject(i)
                add(
                    Layer(
                        id = layer.getString("id"),
                        name = layer.optString("name", "Layer $i"),
                        visible = layer.optBoolean("visible", true),
                        locked = layer.optBoolean("locked", false),
                        color = layer.optInt("color", 0xFFE6EDF5.toInt())
                    )
                )
            }
        }.ifEmpty { listOf(CadDocument.defaultLayer()) }

        val entitiesJson = root.optJSONArray("entities") ?: JSONArray()
        val entities = buildList {
            for (i in 0 until entitiesJson.length()) {
                add(jsonToEntity(entitiesJson.getJSONObject(i), version))
            }
        }

        return CadDocument(
            version = version,
            name = root.optString("name", "Untitled"),
            layers = layers,
            activeLayerId = root.optString("activeLayerId", layers.first().id),
            entities = entities
        )
    }

    private fun entityToJson(entity: CadEntity): JSONObject = when (entity) {
        is LineEntity -> JSONObject().apply {
            put("type", "line")
            put("id", entity.id)
            put("layerId", entity.layerId)
            put("a", point(entity.a))
            put("b", point(entity.b))
        }
        is CircleEntity -> JSONObject().apply {
            put("type", "circle")
            put("id", entity.id)
            put("layerId", entity.layerId)
            put("center", point(entity.center))
            put("radius", entity.radius)
        }
        is ArcEntity -> JSONObject().apply {
            put("type", "arc")
            put("id", entity.id)
            put("layerId", entity.layerId)
            put("center", point(entity.center))
            put("radius", entity.radius)
            put("startAngleDeg", entity.startAngleDeg)
            put("endAngleDeg", entity.endAngleDeg)
        }
        is PolylineEntity -> JSONObject().apply {
            put("type", "polyline")
            put("id", entity.id)
            put("layerId", entity.layerId)
            put("closed", entity.closed)
            put("points", JSONArray().apply { entity.points.forEach { put(point(it)) } })
        }
        is DimensionEntity -> JSONObject().apply {
            put("type", "dimension")
            put("id", entity.id)
            put("layerId", entity.layerId)
            put("from", point(entity.from))
            put("to", point(entity.to))
            put("offset", entity.offset)
            put("text", entity.text)
        }
    }

    private fun jsonToEntity(json: JSONObject, version: Int): CadEntity {
        val id = json.optString("id", CadDocument.newEntityId())
        val layerId = json.optString("layerId", CadDocument.defaultLayer().id)
        return when (json.getString("type")) {
            "line" -> LineEntity(id, layerId, readPoint(json.getJSONObject("a")), readPoint(json.getJSONObject("b")))
            "circle" -> CircleEntity(id, layerId, readPoint(json.getJSONObject("center")), json.getDouble("radius"))
            "arc" -> ArcEntity(
                id,
                layerId,
                readPoint(json.getJSONObject("center")),
                json.getDouble("radius"),
                json.optDouble("startAngleDeg", 0.0),
                json.optDouble("endAngleDeg", 90.0)
            )
            "polyline" -> {
                val pointsJson = json.optJSONArray("points") ?: JSONArray()
                val points = buildList {
                    for (i in 0 until pointsJson.length()) {
                        add(readPoint(pointsJson.getJSONObject(i)))
                    }
                }
                PolylineEntity(id, layerId, points, json.optBoolean("closed", false))
            }
            "dimension" -> DimensionEntity(
                id,
                layerId,
                readPoint(json.getJSONObject("from")),
                readPoint(json.getJSONObject("to")),
                json.optDouble("offset", 12.0),
                json.optString("text", "")
            )
            else -> if (version <= 1) {
                LineEntity(id, layerId, Vec2(0.0, 0.0), Vec2(0.0, 0.0))
            } else {
                throw IllegalArgumentException("Unsupported entity type: ${json.getString("type")}")
            }
        }
    }

    private fun point(point: Vec2): JSONObject = JSONObject().apply {
        put("x", point.x)
        put("y", point.y)
    }

    private fun readPoint(json: JSONObject): Vec2 = Vec2(json.getDouble("x"), json.getDouble("y"))
}
