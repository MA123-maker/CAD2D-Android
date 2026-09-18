package com.example.cad2d.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.cad2d.core.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class DrawMode { SELECT, LINE, CIRCLE, ARC, POLYLINE, DIMENSION }
enum class EditCommand { MOVE, COPY, ROTATE, MIRROR, TRIM, EXTEND, OFFSET }

class CadView(context: Context) : View(context) {
    val editor = CadEditor()
    var drawMode: DrawMode = DrawMode.SELECT
    var showGrid: Boolean = true
    var onStatusChanged: ((String) -> Unit)? = null

    private val snapEngine = SnapEngine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 86, 182, 194)
        style = Paint.Style.FILL
    }

    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f

    private var startPoint: Vec2? = null
    private var previewPoint: Vec2? = null
    private var polylinePoints = mutableListOf<Vec2>()
    private var boxSelectionStart: Vec2? = null
    private var boxSelectionEnd: Vec2? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.2f, 8f)
            invalidate()
            return true
        }
    })

    fun applyEdit(command: EditCommand) {
        when (command) {
            EditCommand.MOVE -> editor.moveSelection(Vec2(20.0, 20.0))
            EditCommand.COPY -> editor.copySelection(Vec2(40.0, 40.0))
            EditCommand.ROTATE -> editor.rotateSelection(selectionPivot(), 15.0)
            EditCommand.MIRROR -> editor.mirrorSelection(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
            EditCommand.TRIM -> selectionPivot().let { editor.trimSelection(it) }
            EditCommand.EXTEND -> selectionPivot().let { editor.extendSelection(it + Vec2(40.0, 0.0)) }
            EditCommand.OFFSET -> editor.offsetSelection(15.0)
        }
        onStatusChanged?.invoke("命令: ${command.name}")
        invalidate()
    }

    fun resetView() {
        panX = 0f
        panY = 0f
        zoom = 1f
        startPoint = null
        previewPoint = null
        polylinePoints.clear()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                val snapped = snapToWorld(event.x, event.y)
                startPoint = snapped
                if (drawMode == DrawMode.SELECT) {
                    val selected = editor.selectByPoint(snapped, 8.0 / zoom)
                    if (selected == null) {
                        boxSelectionStart = snapped
                        boxSelectionEnd = snapped
                    }
                    onStatusChanged?.invoke(if (selected == null) "框选中" else "已选择实体")
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawMode == DrawMode.SELECT && boxSelectionStart == null) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }

                val snapped = snapToWorld(event.x, event.y)
                previewPoint = snapped
                if (boxSelectionStart != null) boxSelectionEnd = snapped
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val snapped = snapToWorld(event.x, event.y)
                when (drawMode) {
                    DrawMode.SELECT -> commitSelectionBox()
                    DrawMode.LINE -> commitTwoPointEntity { a, b ->
                        LineEntity(CadDocument.newEntityId(), editor.document.activeLayerId, a, b)
                    }
                    DrawMode.CIRCLE -> commitTwoPointEntity { a, b ->
                        CircleEntity(CadDocument.newEntityId(), editor.document.activeLayerId, a, a.distanceTo(b))
                    }
                    DrawMode.ARC -> commitArc(snapped)
                    DrawMode.POLYLINE -> commitPolylinePoint(snapped)
                    DrawMode.DIMENSION -> commitTwoPointEntity { a, b ->
                        DimensionEntity(
                            CadDocument.newEntityId(),
                            editor.document.activeLayerId,
                            a,
                            b,
                            12.0,
                            "${"%.2f".format(a.distanceTo(b))}"
                        )
                    }
                }
                previewPoint = null
                invalidate()
                return true
            }
        }

        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(18, 24, 32))
        if (showGrid) drawGrid(canvas)

        editor.document.entities.forEach { entity -> drawEntity(canvas, entity, entity.id in editor.selectedIds) }
        drawPreview(canvas)

        if (boxSelectionStart != null && boxSelectionEnd != null) {
            val a = toScreen(boxSelectionStart!!)
            val b = toScreen(boxSelectionEnd!!)
            canvas.drawRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), selectPaint)
        }

        if (polylinePoints.isNotEmpty()) {
            onStatusChanged?.invoke("Polyline 点数: ${polylinePoints.size}, 双击结束")
        }
    }

    private fun drawPreview(canvas: Canvas) {
        val start = startPoint ?: return
        val current = previewPoint ?: return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(134, 239, 172)

        when (drawMode) {
            DrawMode.LINE, DrawMode.DIMENSION -> {
                val a = toScreen(start)
                val b = toScreen(current)
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
            DrawMode.CIRCLE -> {
                val c = toScreen(start)
                canvas.drawCircle(c.x, c.y, (start.distanceTo(current) * zoom).toFloat(), paint)
            }
            DrawMode.ARC -> {
                val center = toScreen(start)
                val radius = (start.distanceTo(current) * zoom).toFloat()
                canvas.drawCircle(center.x, center.y, radius, paint)
            }
            DrawMode.POLYLINE -> {
                val points = polylinePoints + current
                points.zipWithNext().forEach { (a, b) ->
                    val sa = toScreen(a)
                    val sb = toScreen(b)
                    canvas.drawLine(sa.x, sa.y, sb.x, sb.y, paint)
                }
            }
            DrawMode.SELECT -> Unit
        }
    }

    private fun drawEntity(canvas: Canvas, entity: CadEntity, selected: Boolean) {
        val layerVisible = editor.document.layers.firstOrNull { it.id == entity.layerId }?.visible ?: true
        if (!layerVisible) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) 3f else 2f
        paint.color = if (selected) Color.rgb(56, 189, 248) else Color.rgb(230, 237, 245)

        when (entity) {
            is LineEntity -> {
                val a = toScreen(entity.a)
                val b = toScreen(entity.b)
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
            is CircleEntity -> {
                val c = toScreen(entity.center)
                canvas.drawCircle(c.x, c.y, (entity.radius * zoom).toFloat(), paint)
            }
            is ArcEntity -> {
                val c = toScreen(entity.center)
                canvas.drawCircle(c.x, c.y, (entity.radius * zoom).toFloat(), paint)
            }
            is PolylineEntity -> {
                entity.points.zipWithNext().forEach { (a, b) ->
                    val sa = toScreen(a)
                    val sb = toScreen(b)
                    canvas.drawLine(sa.x, sa.y, sb.x, sb.y, paint)
                }
                if (entity.closed && entity.points.size > 2) {
                    val sa = toScreen(entity.points.first())
                    val sb = toScreen(entity.points.last())
                    canvas.drawLine(sa.x, sa.y, sb.x, sb.y, paint)
                }
            }
            is DimensionEntity -> {
                val from = toScreen(entity.from)
                val to = toScreen(entity.to)
                canvas.drawLine(from.x, from.y, to.x, to.y, paint)
                paint.textSize = 24f
                paint.style = Paint.Style.FILL
                val midX = (from.x + to.x) / 2f
                val midY = (from.y + to.y) / 2f - entity.offset.toFloat()
                canvas.drawText(entity.text, midX, midY, paint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(38, 56, 76)
        paint.strokeWidth = 1f
        val step = 20f * zoom
        if (step < 6f) return

        var x = ((panX % step) + step) % step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            x += step
        }

        var y = ((panY % step) + step) % step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += step
        }
    }

    private fun commitArc(point: Vec2) {
        if (startPoint == null) {
            startPoint = point
            return
        }
        if (previewPoint == null) {
            previewPoint = point
            return
        }
        val center = startPoint!!
        val radius = max(1.0, center.distanceTo(previewPoint!!))
        val startAngle = angleOf(center, previewPoint!!)
        val endAngle = angleOf(center, point)
        editor.addEntity(
            ArcEntity(
                id = CadDocument.newEntityId(),
                layerId = editor.document.activeLayerId,
                center = center,
                radius = radius,
                startAngleDeg = startAngle,
                endAngleDeg = endAngle
            )
        )
        startPoint = null
        previewPoint = null
    }

    private fun commitPolylinePoint(point: Vec2) {
        polylinePoints += point
        if (polylinePoints.size >= 2 && point.distanceTo(polylinePoints[polylinePoints.size - 2]) < 1.0) {
            finishPolyline()
            return
        }
        if (polylinePoints.size >= 2 && isDoubleTap(point)) {
            finishPolyline()
        }
    }

    private fun finishPolyline() {
        if (polylinePoints.size >= 2) {
            editor.addEntity(
                PolylineEntity(
                    CadDocument.newEntityId(),
                    editor.document.activeLayerId,
                    polylinePoints.toList(),
                    closed = false
                )
            )
        }
        polylinePoints.clear()
        startPoint = null
    }

    private fun isDoubleTap(point: Vec2): Boolean {
        val previous = polylinePoints.getOrNull(polylinePoints.lastIndex - 1) ?: return false
        return previous.distanceTo(point) < 6.0 / zoom
    }

    private fun commitSelectionBox() {
        val start = boxSelectionStart
        val end = boxSelectionEnd
        if (start != null && end != null && start.distanceTo(end) > 1.0 / zoom) {
            val bounds = Bounds2D(
                min = Vec2(min(start.x, end.x), min(start.y, end.y)),
                max = Vec2(max(start.x, end.x), max(start.y, end.y))
            )
            editor.selectByBounds(bounds)
        }
        boxSelectionStart = null
        boxSelectionEnd = null
    }

    private fun commitTwoPointEntity(factory: (Vec2, Vec2) -> CadEntity) {
        val a = startPoint ?: return
        val b = snapToWorld(lastTouchX, lastTouchY)
        if (a.distanceTo(b) < 1e-4) {
            startPoint = null
            return
        }
        editor.addEntity(factory(a, b))
        startPoint = null
    }

    private fun selectionPivot(): Vec2 {
        val selection = editor.document.entities.filter { it.id in editor.selectedIds }
        val points = selection.flatMap { entity ->
            when (entity) {
                is LineEntity -> listOf(entity.a, entity.b)
                is CircleEntity -> listOf(entity.center)
                is ArcEntity -> listOf(entity.center)
                is PolylineEntity -> entity.points
                is DimensionEntity -> listOf(entity.from, entity.to)
            }
        }
        if (points.isEmpty()) return Vec2(0.0, 0.0)
        val x = points.map { it.x }.average()
        val y = points.map { it.y }.average()
        return Vec2(x, y)
    }

    private fun snapToWorld(screenX: Float, screenY: Float): Vec2 {
        val raw = Vec2(((screenX - panX) / zoom).toDouble(), ((screenY - panY) / zoom).toDouble())
        return snapEngine.snap(editor.document, raw, startPoint).point
    }

    private fun toScreen(world: Vec2): android.graphics.PointF = android.graphics.PointF(
        (world.x * zoom + panX).toFloat(),
        (world.y * zoom + panY).toFloat()
    )
}
