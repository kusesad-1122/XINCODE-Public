package com.xincode.app

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView 版「像素办公室」:加载 assets/office/index.html(HTML5 Canvas 引擎),
 * 用 evaluateJavascript 把子智能体状态桥接进网页。比 Compose Canvas 更流畅、易迭代。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficeWebView(
    agents: List<String>,
    liveByName: Map<String, SubAgentSceneState.Worker>,
    brainBusy: Boolean,
    modifier: Modifier = Modifier
) {
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    // 组织状态 JSON:[{name,status,text}]。text=气泡内容(在做什么)
    val payload = remember(agents, liveByName) {
        val arr = JSONArray()
        agents.forEach { name ->
            val w = liveByName[name]
            val st = when (w?.status) {
                SubAgentSceneState.Status.PREPARING -> "preparing"
                SubAgentSceneState.Status.RUNNING -> "running"
                SubAgentSceneState.Status.DONE -> "done"
                SubAgentSceneState.Status.FAILED -> "failed"
                null -> "idle"
            }
            val text = when (w?.status) {
                SubAgentSceneState.Status.RUNNING -> w.activity.ifBlank { w.task }.take(30)
                SubAgentSceneState.Status.PREPARING -> "准备:" + w.task.take(22)
                SubAgentSceneState.Status.DONE -> "✓ 已完成"
                SubAgentSceneState.Status.FAILED -> "✗ 失败"
                else -> ""
            }
            arr.put(JSONObject().put("name", name).put("status", st).put("text", text))
        }
        arr.toString()
    }
    // 主脑气泡:指挥内容(拿正在派的子智能体任务做摘要)
    val brainText = remember(liveByName, brainBusy) {
        if (!brainBusy) "" else {
            val tasks = liveByName.values.filter {
                it.status == SubAgentSceneState.Status.RUNNING || it.status == SubAgentSceneState.Status.PREPARING
            }.take(2).joinToString("；") { "${it.agent}→${it.task.take(16)}" }
            if (tasks.isBlank()) "指挥子智能体中…" else "指挥:$tasks"
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                setBackgroundColor(0xFF141621.toInt())
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        ready = true
                    }
                }
                loadUrl("file:///android_asset/office/index.html")
                web = this
            }
        },
        update = { web = it }
    )

    // 页面就绪后 + 每次状态变化时推送
    LaunchedEffect(ready, payload, brainBusy, brainText) {
        if (!ready) return@LaunchedEffect
        val bt = JSONObject.quote(brainText)
        web?.evaluateJavascript("window.setBrain($brainBusy, $bt); window.setAgents($payload);", null)
    }
}
