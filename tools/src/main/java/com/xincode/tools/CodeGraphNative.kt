package com.xincode.tools

import android.util.Log

/**
 * codegraph 内核的 JNI 入口。
 *
 * 内核来自 https://github.com/colbymchenry/codegraph(MIT),用 tree-sitter 做 AST
 * 抽取,支持 20 种语言。上游的 Rust 代码原样引入未做修改,只在旁边加了一层薄 JNI 绑定
 * (见 codegraph-kernel/src/jni_api.rs)。许可与版权声明见 codegraph-kernel/LICENSE.codegraph。
 *
 * ## 它解决什么
 *
 * agent 探索代码库时靠一遍遍 grep/glob/read,每读一个文件都在挤占上下文,而手机上
 * 上下文本来就紧张。有了预建索引,「这个函数在哪定义的、谁调用了它」是一次查询,
 * 不用把文件读进上下文。
 *
 * ## 加载失败不是致命的
 *
 * 库没打进 APK、ABI 不匹配、设备太老 —— 都可能加载失败。那时 [available] 是 false,
 * 相关工具直接不注册,agent 退回原来的 grep 方式。**不能因为这个让应用起不来**。
 */
object CodeGraphNative {

    private const val TAG = "XincodeCodeGraph"

    private data class LoadResult(val available: Boolean, val failure: String = "")

    private val loadResult: LoadResult by lazy {
        try {
            System.loadLibrary("codegraph_kernel")
            Log.i(TAG, "kernel loaded")
            LoadResult(true)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError 属于 Error 不是 Exception,catch(Exception) 接不住
            val reason = "${t::class.java.simpleName}: ${t.message.orEmpty()}"
            Log.w(TAG, "kernel unavailable: $reason")
            LoadResult(false, reason)
        }
    }

    /** 库是否可用。false 时所有调用都会返回 null,调用方应退回 grep。 */
    val available: Boolean get() = loadResult.available

    /** Exact dynamic-linker reason for diagnostics instead of guessing that the ABI is wrong. */
    val failureReason: String get() = loadResult.failure

    /**
     * 抽取单个文件的符号与关系,返回 JSON。
     *
     * @return `{"nodes":[…],"edges":[…],"refs":[…]}`,失败时 `{"error":"…"}`;
     *         库不可用时返回 null。
     */
    fun extract(path: String, content: String, language: String): String? {
        if (!available) return null
        return try {
            extractFile(path, content, language)
        } catch (t: Throwable) {
            Log.w(TAG, "extract failed for $path: ${t.message}")
            null
        }
    }

    /** 内核支持的语言标识列表。 */
    fun languages(): List<String> {
        if (!available) return emptyList()
        return runCatching {
            val json = org.json.JSONArray(supportedLanguages())
            (0 until json.length()).map { json.getString(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * 文件扩展名 → 内核的语言标识。
     *
     * 返回 null 表示这个文件不用送进内核。列表之外的扩展名一律跳过 ——
     * 拿错误的语言去解析只会得到一堆垃圾节点,比没有更糟。
     */
    fun languageOf(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "java" -> "java"
        "py", "pyi" -> "python"
        "go" -> "go"
        "c", "h" -> "c"
        "cc", "cpp", "cxx", "hpp", "hh" -> "cpp"
        "rs" -> "rust"
        "cs" -> "csharp"
        "rb" -> "ruby"
        "php" -> "php"
        "swift" -> "swift"
        "kt", "kts" -> "kotlin"
        "r" -> "r"
        "lua" -> "lua"
        "scala", "sc" -> "scala"
        "dart" -> "dart"
        "ts" -> "typescript"
        "tsx" -> "tsx"
        "js", "mjs", "cjs" -> "javascript"
        "jsx" -> "jsx"
        else -> null
    }

    @JvmStatic
    private external fun extractFile(path: String, content: String, language: String): String

    @JvmStatic
    private external fun supportedLanguages(): String
}
