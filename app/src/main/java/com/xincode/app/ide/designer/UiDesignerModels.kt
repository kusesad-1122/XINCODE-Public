package com.xincode.app.ide.designer

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class DesignerWidget(
    val type: String,
    val displayName: String,
    val icon: String,
    val defaultXml: String,
    val category: String
)

object WidgetCatalog {
    val widgets = listOf(
        DesignerWidget("TextView", "文本", "T", "<TextView\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Hello\" />", "基础"),
        DesignerWidget("Button", "按钮", "B", "<Button\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Button\" />", "基础"),
        DesignerWidget("EditText", "输入框", "E", "<EditText\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:hint=\"输入\" />", "基础"),
        DesignerWidget("ImageView", "图片", "I", "<ImageView\n    android:layout_width=\"100dp\"\n    android:layout_height=\"100dp\"\n    android:src=\"@mipmap/ic_launcher\" />", "基础"),
        DesignerWidget("CheckBox", "复选框", "☑", "<CheckBox\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Check\" />", "基础"),
        DesignerWidget("Switch", "开关", "◐", "<Switch\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\" />", "基础"),
        DesignerWidget("ProgressBar", "进度条", "▭", "<ProgressBar\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\" />", "基础"),
        DesignerWidget("RecyclerView", "列表", "☰", "<androidx.recyclerview.widget.RecyclerView\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\" />", "容器"),
        DesignerWidget("CardView", "卡片", "▭", "<androidx.cardview.widget.CardView\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    app:cardCornerRadius=\"8dp\">\n</androidx.cardview.widget.CardView>", "容器"),
        DesignerWidget("LinearLayout", "线性布局", "‖", "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:orientation=\"vertical\">\n</LinearLayout>", "布局"),
        DesignerWidget("ConstraintLayout", "约束布局", "⊞", "<androidx.constraintlayout.widget.ConstraintLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n</androidx.constraintlayout.widget.ConstraintLayout>", "布局"),
        DesignerWidget("FrameLayout", "帧布局", "▣", "<FrameLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n</FrameLayout>", "布局")
    )
    fun byCategory() = widgets.groupBy { it.category }
}

data class LayoutNode(
    var id: String = "",
    var type: String = "TextView",
    var props: MutableMap<String, String> = mutableMapOf(
        "android:layout_width" to "wrap_content",
        "android:layout_height" to "wrap_content",
        "android:text" to "Hello"
    ),
    var children: MutableList<LayoutNode> = mutableListOf()
) {
    fun toXml(indent: String = ""): String {
        val sb = StringBuilder()
        sb.append("$indent<$type\n")
        // id 特殊处理：转义XML属性值
        if (id.isNotBlank()) {
            val escId = id.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("$indent    android:id=\"@+id/$escId\"\n")
        }
        props.forEach { (k,v) ->
            if (k=="android:id") return@forEach
            val escV = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("$indent    $k=\"$escV\"\n")
        }
        if (children.isEmpty()) {
            sb.append("$indent    />\n")
        } else {
            sb.append("$indent    >\n")
            children.forEach { sb.append(it.toXml(indent + "    ")) }
            sb.append("$indent</$type>\n")
        }
        return sb.toString()
    }
}

class UiDesignerState {
    val nodes = mutableStateListOf<LayoutNode>()
    var selectedIndex by mutableStateOf<Int?>(null)
    var projectRoot by mutableStateOf("")
    var layoutName by mutableStateOf("activity_main")
    var resources by mutableStateOf(ResourceSet())
    private var nextIdSeq = 1

    val selectedNode: LayoutNode? get() = selectedIndex?.let { nodes.getOrNull(it) }

    private fun nextUniqueId(): String {
        var candidate: String
        do {
            candidate = "view_$nextIdSeq"
            nextIdSeq++
        } while (nodes.any { it.id == candidate })
        return candidate
    }

    fun addWidget(widget: DesignerWidget) {
        val node = LayoutNode(type = widget.type, id = nextUniqueId())
        // parse default props from widget defaultXml quickly
        Regex("android:([A-Za-z_]+)=\"([^\"]+)\"").findAll(widget.defaultXml).forEach {
            node.props["android:${it.groupValues[1]}"] = it.groupValues[2]
        }
        nodes.add(node)
        selectedIndex = nodes.lastIndex
    }

    fun removeSelected() {
        selectedIndex?.let { nodes.removeAt(it); selectedIndex = null }
    }

    fun moveUp() {
        val idx = selectedIndex ?: return
        if (idx > 0) {
            val item = nodes.removeAt(idx)
            nodes.add(idx-1, item)
            selectedIndex = idx-1
        }
    }
    fun moveDown() {
        val idx = selectedIndex ?: return
        if (idx < nodes.size-1) {
            val item = nodes.removeAt(idx)
            nodes.add(idx+1, item)
            selectedIndex = idx+1
        }
    }

    fun generateXml(): String {
        val header = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        val rootOpen = "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n    android:orientation=\"vertical\"\n    android:padding=\"16dp\">\n"
        val rootClose = "</LinearLayout>\n"
        val body = nodes.joinToString("") { it.toXml("    ") }
        return header + rootOpen + body + rootClose
    }

    fun inflatePreview(): String = generateXml()

    fun updateProp(key: String, value: String) {
        selectedNode?.props?.set(key, value)
    }
}
