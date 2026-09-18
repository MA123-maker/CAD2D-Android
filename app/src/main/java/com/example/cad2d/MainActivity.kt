package com.example.cad2d

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.*
import com.example.cad2d.core.*
import com.example.cad2d.data.CadFileRepository
import com.example.cad2d.ui.CadView

class MainActivity : Activity() {
    private lateinit var cadView: CadView
    private lateinit var repository: CadFileRepository
    private lateinit var statusText: TextView
    private lateinit var layerSpinner: Spinner
    private lateinit var modeSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CadFileRepository(filesDir.resolve("cad-projects"))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 24, 32))
        }

        val toolbar = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.rgb(30, 40, 52))
            isHorizontalScrollBarEnabled = false
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        cadView = CadView(this).apply {
            onStatusChanged = { statusText.text = it }
        }

        modeSpinner = Spinner(this)
        val modes = DrawMode.entries.map { it.name }
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                cadView.drawMode = DrawMode.entries[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        layerSpinner = Spinner(this)

        fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 11f
            setOnClickListener { action() }
        }

        controls.addView(modeSpinner)
        controls.addView(layerSpinner)
        controls.addView(smallButton("新图层") {
            val layer = cadView.editor.addLayer("Layer ${cadView.editor.document.layers.size}")
            refreshLayers(layer.id)
            cadView.invalidate()
        })
        controls.addView(smallButton("撤销") { cadView.editor.undo(); cadView.invalidate() })
        controls.addView(smallButton("重做") { cadView.editor.redo(); cadView.invalidate() })
        controls.addView(smallButton("删除") { cadView.editor.deleteSelection(); cadView.invalidate() })
        controls.addView(smallButton("移动") { cadView.applyEdit(EditCommand.MOVE) })
        controls.addView(smallButton("复制") { cadView.applyEdit(EditCommand.COPY) })
        controls.addView(smallButton("旋转") { cadView.applyEdit(EditCommand.ROTATE) })
        controls.addView(smallButton("镜像") { cadView.applyEdit(EditCommand.MIRROR) })
        controls.addView(smallButton("修剪") { cadView.applyEdit(EditCommand.TRIM) })
        controls.addView(smallButton("延伸") { cadView.applyEdit(EditCommand.EXTEND) })
        controls.addView(smallButton("偏移") { cadView.applyEdit(EditCommand.OFFSET) })
        controls.addView(smallButton("网格") { cadView.showGrid = !cadView.showGrid; cadView.invalidate() })
        controls.addView(smallButton("新建") {
            cadView.editor.replaceDocument(repository.newDocument("Untitled"))
            cadView.resetView()
            refreshLayers(cadView.editor.document.activeLayerId)
            cadView.invalidate()
        })
        controls.addView(smallButton("保存") { promptSave(false) })
        controls.addView(smallButton("另存") { promptSave(true) })
        controls.addView(smallButton("打开") { promptOpen() })

        toolbar.addView(controls)

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(170, 230, 255))
            setPadding(16, 10, 16, 10)
            text = "离线 CAD 就绪"
        }

        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(cadView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        refreshLayers(cadView.editor.document.activeLayerId)
    }

    private fun refreshLayers(activeId: String) {
        val layers = cadView.editor.document.layers
        layerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, layers.map { it.name })
        val index = layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        layerSpinner.setSelection(index)
        layerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val target = layers.getOrNull(position) ?: return
                cadView.editor.setActiveLayer(target.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun promptSave(forceName: Boolean) {
        val editor = EditText(this).apply {
            hint = "文件名"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(if (forceName) "另存为" else "保存")
            .setView(editor)
            .setPositiveButton("确定") { _, _ ->
                val name = editor.text.toString().ifBlank { cadView.editor.document.name }
                repository.save(cadView.editor.document.copy(name = name), name)
                statusText.text = "已保存: $name"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptOpen() {
        val items = repository.listDocuments()
        if (items.isEmpty()) {
            statusText.text = "没有可打开的离线文件"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("打开文件")
            .setItems(items.toTypedArray()) { _, which ->
                val name = items[which]
                cadView.editor.replaceDocument(repository.open(name))
                cadView.resetView()
                refreshLayers(cadView.editor.document.activeLayerId)
                cadView.invalidate()
                statusText.text = "已打开: $name"
            }
            .show()
    }
}
