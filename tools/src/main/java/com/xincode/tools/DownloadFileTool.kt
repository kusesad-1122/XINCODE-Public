package com.xincode.tools

import android.util.Log
import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从 URL 下载文件落盘。
 *
 * 与 `web_fetch` 的分工:web_fetch 是把网页正文抽成文本喂给模型看,下载的东西
 * 要进上下文;这个是把字节存成文件,**内容完全不进上下文**。图片、压缩包、
 * 数据集这些走 web_fetch 只会把上下文顶爆,而且抽正文对二进制毫无意义。
 *
 * 手机上的两个硬约束:
 *  - **必须流式写**。整包读进内存再落盘,一个 200MB 的文件直接 OOM。
 *  - **必须有大小上限**。模型不知道对面多大,没有上限就等着写满用户的存储。
 */
class DownloadFileTool : Tool {

    companion object {
        private const val TAG = "XincodeDownload"

        /** 单个文件上限。超过就中止并删掉半截文件。 */
        private const val MAX_BYTES = 200L * 1024 * 1024

        /** 流式拷贝的缓冲区。 */
        private const val BUFFER = 64 * 1024
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    override val name = "download_file"
    override val description = "从 URL 下载文件保存到本地。适合图片、压缩包等二进制内容 —— " +
            "文件内容不会进入对话上下文,只返回保存路径和大小。要读网页正文请用 web_fetch。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
                put("type", "string")
                put("description", "要下载的 URL(http/https)")
            })
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "保存路径。相对路径以工作区为基准。省略则用 URL 里的文件名存到工作区根。")
            })
        })
        put("required", JSONArray().apply { put("url") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val url = params["url"]?.trim().orEmpty()
        if (url.isBlank()) return@withContext ToolResult.Error("缺少 url 参数")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.Error("只支持 http/https: $url")
        }

        // 没给路径就从 URL 末段推一个文件名。推不出来(比如 URL 以 / 结尾)时给个兜底名,
        // 不能让它变成空文件名。
        val rawPath = params["path"]?.trim().takeIf { !it.isNullOrBlank() }
            ?: url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "download_${System.currentTimeMillis()}" }

        val safePath = PathResolver.resolve(rawPath)
            ?: return@withContext ToolResult.Error("无法解析路径: $rawPath")
        val outFile = File(safePath)
        outFile.parentFile?.mkdirs()

        try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ToolResult.Error("HTTP ${resp.code}: 下载失败 $url")
                }
                // Content-Length 只是参考:很多服务端不给,给了也可能是错的。
                // 真正的上限判断放在拷贝循环里按实际字节数来。
                val declared = resp.body?.contentLength() ?: -1L
                if (declared > MAX_BYTES) {
                    return@withContext ToolResult.Error(
                        "文件过大(${declared / 1024 / 1024}MB,上限 ${MAX_BYTES / 1024 / 1024}MB)"
                    )
                }

                val body = resp.body ?: return@withContext ToolResult.Error("响应没有内容")
                var total = 0L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) {
                                // 半截文件留在磁盘上比没有更糟 —— 后续步骤会把它当成完整文件用
                                output.close()
                                outFile.delete()
                                return@withContext ToolResult.Error(
                                    "文件超过上限 ${MAX_BYTES / 1024 / 1024}MB,已中止并清除"
                                )
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                Log.i(TAG, "downloaded $url -> $safePath ($total bytes)")
                ToolResult.Success("已下载: $safePath(${formatSize(total)})")
            }
        } catch (e: Exception) {
            runCatching { if (outFile.exists()) outFile.delete() }
            ToolResult.Error("下载出错: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024}MB"
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }
}

/**
 * 等待一小段时间。
 *
 * 用途很窄但确实需要:轮询外部状态时两次检查之间要隔开,不然就是拿满速循环去撞
 * 别人的接口。上限压得很死 —— 手机上让 agent 睡几分钟没有意义,不如让它结束这轮
 * 把控制权交回来。
 */
class SleepTool : Tool {

    override val name = "sleep"
    override val description = "暂停若干秒后继续(最多 30 秒)。用于轮询外部状态时的间隔等待。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("seconds", JSONObject().apply {
                put("type", "number")
                put("description", "等待秒数,0.1 到 30 之间")
            })
        })
        put("required", JSONArray().apply { put("seconds") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val seconds = params["seconds"]?.toDoubleOrNull()
            ?: return ToolResult.Error("seconds 必须是数字")
        val clamped = seconds.coerceIn(0.1, 30.0)
        // kotlinx 的 delay 是可取消的:用户点停止时不会卡在这儿等满
        kotlinx.coroutines.delay((clamped * 1000).toLong())
        return ToolResult.Success("已等待 $clamped 秒")
    }
}
