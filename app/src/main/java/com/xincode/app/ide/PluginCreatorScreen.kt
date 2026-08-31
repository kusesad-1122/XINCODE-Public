package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.LocalXinColors
import com.xincode.app.XinHeaderAction
import com.xincode.app.XinPageHeader
import com.xincode.app.XinUiFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Mono = XinUiFont

@Composable
fun PluginCreatorScreen(
    workspaceRoot: String,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var projectRoot by remember { mutableStateOf(workspaceRoot.ifBlank { "/sdcard/MyApp" }) }
    var moduleName by remember { mutableStateOf("mylibrary") }
    var packageName by remember { mutableStateOf("com.example.mylibrary") }
    var pluginType by remember { mutableStateOf("library") } // library, plugin, feature
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "插件创建器", subtitle = "在项目中创建子模块 / 插件", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "创建", onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        val cleanName = moduleName.trim()
                        if (cleanName.isEmpty() || cleanName.contains("..") || cleanName.contains("/") || cleanName.contains("\\")) {
                            withContext(Dispatchers.Main) { status = "失败：模块名非法" }
                            return@launch
                        }
                        val modDir = File(projectRoot, cleanName)
                        if (modDir.exists()) {
                            withContext(Dispatchers.Main) { status = "失败：目录已存在 $cleanName" }
                            return@launch
                        }
                        // 防路径穿越：canonical 必须仍在 projectRoot 内
                        if (!modDir.canonicalPath.startsWith(File(projectRoot).canonicalPath + File.separator)) {
                            withContext(Dispatchers.Main) { status = "失败：模块路径越界" }
                            return@launch
                        }
                        modDir.mkdirs()
                        val pkgPath = packageName.trim().replace('.', '/')
                        when (pluginType) {
                            "library" -> {
                                File(modDir, "build.gradle.kts").writeText(libraryGradle(packageName.trim()))
                                File(modDir, "src/main/AndroidManifest.xml").apply { parentFile.mkdirs(); writeText(libraryManifest(packageName.trim())) }
                                File(modDir, "src/main/java/$pkgPath/Hello.kt").apply { parentFile.mkdirs(); writeText(helloKt(packageName.trim())) }
                                // update settings.gradle.kts（精确匹配避免 mylibrary 命中 mylibrary2）
                                val settingsKts = File(projectRoot, "settings.gradle.kts")
                                val settingsGradle = File(projectRoot, "settings.gradle")
                                val settings = when {
                                    settingsKts.exists() -> settingsKts
                                    settingsGradle.exists() -> settingsGradle
                                    else -> settingsKts
                                }
                                if (settings.exists()) {
                                    val txt = settings.readText()
                                    val alreadyIncluded = txt.contains(Regex("""["']:$moduleName["']""")) ||
                                            txt.contains(Regex("""include\s*\(\s*["']:$moduleName["']""")) ||
                                            txt.contains(Regex("""include\s+["']:$moduleName["']"""))
                                    if (!alreadyIncluded) settings.appendText("\ninclude(\":$moduleName\")\n")
                                }
                            }
                            "plugin" -> {
                                File(modDir, "build.gradle.kts").writeText(pluginGradle(packageName.trim()))
                                File(modDir, "src/main/kotlin/$pkgPath/Plugin.kt").apply { parentFile.mkdirs(); writeText(pluginKt(packageName.trim())) }
                            }
                            "feature" -> {
                                File(modDir, "build.gradle.kts").writeText(featureGradle(packageName.trim()))
                                File(modDir, "src/main/AndroidManifest.xml").apply { parentFile.mkdirs(); writeText(libraryManifest(packageName.trim())) }
                            }
                        }
                        withContext(Dispatchers.Main) { status = "已创建模块 :$moduleName ($pluginType) 在 $projectRoot" }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { status = "失败: ${e.message}" }
                    }
                }
            })
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("项目根目录", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    TextField(value = projectRoot, onValueChange = { projectRoot=it }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp, color = xc.ink))
                    TextField(value = moduleName, onValueChange = { moduleName = it.filter { c -> c.isLetterOrDigit() || c=='_' || c=='-' }.lowercase() }, label = { Text("模块名", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono))
                    TextField(value = packageName, onValueChange = { packageName=it }, label = { Text("包名", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("library" to "Library", "plugin" to "Gradle Plugin", "feature" to "Feature").forEach { (id, label) ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if(pluginType==id) xc.green else xc.bgElevated).border(1.dp, if(pluginType==id) xc.green else xc.border, RoundedCornerShape(10.dp)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { pluginType=id }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 11.sp, fontFamily = Mono, color = if(pluginType==id) Color.White else xc.sub)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
                Column {
                    Text("生成预览", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFF7BE0A4))
                    Text(when(pluginType){
                        "library" -> libraryGradle(packageName).take(400)
                        "plugin" -> pluginGradle(packageName).take(400)
                        else -> featureGradle(packageName).take(400)
                    }, fontSize = 9.sp, fontFamily = Mono, color = Color(0xFFD7DAE0), lineHeight = 11.sp)
                    if (status.isNotBlank()) Text(status, fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF7BE0A4), modifier = Modifier.padding(top = 8.dp))
                }
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("说明：创建后需在 settings.gradle.kts 加入 include(\":$moduleName\")，并在 app/build.gradle.kts 添加 implementation(project(\":$moduleName\"))。Gradle 同步可在 Gradle 面板执行。", fontSize = 10.sp, fontFamily = Mono, color = xc.sub, lineHeight = 13.sp)
            }
        }
    }
}

private fun libraryGradle(pkg: String) = """
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "$pkg"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
""".trimIndent()

private fun pluginGradle(pkg: String) = """
plugins {
    `kotlin-dsl`
}
gradlePlugin {
    plugins {
        create("myPlugin") {
            id = "$pkg"
            implementationClass = "$pkg.Plugin"
        }
    }
}
""".trimIndent()

private fun featureGradle(pkg: String) = libraryGradle(pkg) + "\n// feature module: add navigation / hilt dependencies as needed\n"

private fun libraryManifest(pkg: String) = """
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg" />
""".trimIndent()

private fun helloKt(pkg: String) = """
package $pkg
class Hello {
    fun greet() = "Hello from $pkg"
}
""".trimIndent()

private fun pluginKt(pkg: String) = """
package $pkg
import org.gradle.api.Plugin
import org.gradle.api.Project
class Plugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("hello") { it.doLast { println("Hello from $pkg") } }
    }
}
""".trimIndent()
