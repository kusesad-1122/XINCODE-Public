package com.xincode.app.ide.designer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.xmlpull.v1.XmlPullParser
import android.util.Xml

data class StringRes(val name: String, val value: String)
data class ColorRes(val name: String, val value: String)
data class DimenRes(val name: String, val value: String)
data class ResourceSet(
    val strings: List<StringRes> = emptyList(),
    val colors: List<ColorRes> = emptyList(),
    val dimens: List<DimenRes> = emptyList(),
    val layouts: List<String> = emptyList(),
    val ids: List<String> = emptyList()
)

object ResourceParser {

    suspend fun parseProjectResources(projectRoot: String): ResourceSet = withContext(Dispatchers.IO) {
        if (projectRoot.isBlank()) return@withContext ResourceSet()
        val resDir = File(projectRoot, "app/src/main/res")
        val base = if (resDir.exists()) resDir else File(projectRoot, "res")
        if (!base.exists()) return@withContext ResourceSet()
        val strings = mutableListOf<StringRes>()
        val colors = mutableListOf<ColorRes>()
        val dimens = mutableListOf<DimenRes>()
        val layouts = mutableListOf<String>()
        val ids = mutableListOf<String>()

        // strings.xml
        File(base, "values/strings.xml").takeIf { it.exists() }?.let { f ->
            try { parseStrings(f, strings) } catch (_: Exception) {}
        }
        File(base, "values/colors.xml").takeIf { it.exists() }?.let { f ->
            try { parseColors(f, colors) } catch (_: Exception) {}
        }
        File(base, "values/dimens.xml").takeIf { it.exists() }?.let { f ->
            try { parseDimens(f, dimens) } catch (_: Exception) {}
        }
        File(base, "values/ids.xml").takeIf { it.exists() }?.let { f ->
            try { parseIds(f, ids) } catch (_: Exception) {}
        }
        // fallback: scan any values/*.xml for strings
        File(base, "values").listFiles()?.forEach { f ->
            if (f.name.endsWith(".xml") && f.name !in setOf("strings.xml","colors.xml","dimens.xml","ids.xml")) {
                try { parseStrings(f, strings) } catch (_: Exception) {}
            }
        }
        File(base, "layout").listFiles()?.forEach { f ->
            if (f.extension == "xml") layouts.add(f.nameWithoutExtension)
        }
        // also collect ids from layouts
        File(base, "layout").listFiles()?.forEach { f ->
            try {
                val txt = f.readText()
                Regex("@\\+id/([A-Za-z0-9_]+)").findAll(txt).forEach { m -> ids.add(m.groupValues[1]) }
            } catch (_: Exception) {}
        }
        ResourceSet(strings.distinctBy { it.name }, colors.distinctBy { it.name }, dimens.distinctBy { it.name }, layouts.distinct(), ids.distinct())
    }

    private fun parseStrings(file: File, out: MutableList<StringRes>) {
        val txt = file.readText()
        Regex("<string[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(txt).forEach {
            out.add(StringRes(it.groupValues[1], it.groupValues[2].trim().replace(Regex("<[^>]+>"), "")))
        }
    }
    private fun parseColors(file: File, out: MutableList<ColorRes>) {
        val txt = file.readText()
        Regex("<color[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</color>").findAll(txt).forEach {
            out.add(ColorRes(it.groupValues[1], it.groupValues[2].trim()))
        }
    }
    private fun parseDimens(file: File, out: MutableList<DimenRes>) {
        val txt = file.readText()
        Regex("<dimen[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</dimen>").findAll(txt).forEach {
            out.add(DimenRes(it.groupValues[1], it.groupValues[2].trim()))
        }
    }
    private fun parseIds(file: File, out: MutableList<String>) {
        val txt = file.readText()
        Regex("<item[^>]*name=\"([^\"]+)\"").findAll(txt).forEach { out.add(it.groupValues[1]) }
    }

    fun suggestResourceValues(input: String, resources: ResourceSet): List<String> {
        val q = input.trim().lowercase()
        val all = mutableListOf<String>()
        resources.strings.forEach { all.add("@string/${it.name}") }
        resources.colors.forEach { all.add("@color/${it.name}") }
        resources.dimens.forEach { all.add("@dimen/${it.name}") }
        resources.layouts.forEach { all.add("@layout/${it}") }
        // also hardcode android:
        all.addAll(listOf("@android:color/white","@android:color/black","@android:color/transparent","?attr/colorPrimary"))
        return if (q.isBlank()) all.take(30) else all.filter { it.lowercase().contains(q) }.take(20)
    }
}
