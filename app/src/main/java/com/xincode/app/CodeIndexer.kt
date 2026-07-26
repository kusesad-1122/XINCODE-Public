package com.xincode.app

import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.CodeEdgeEntity
import com.xincode.data.CodeFileEntity
import com.xincode.data.CodeSymbolEntity
import com.xincode.tools.CodeGraphNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 扫工作区、抽符号、落库。
 *
 * ## 手机上必须守住的几条
 *
 * - **增量**。全量重扫一个中等工程要几十秒,每次都重来没人受得了。按「大小 + 修改时间」
 *   判断文件变没变,没变就跳过。用这两个值而不是内容哈希:算哈希要读全部内容,
 *   几千个文件下来又慢又费电,而这个组合已经足够可靠。
 * - **跳过不该看的目录**。`.git`、`node_modules`、`build` 这些占了绝大多数文件,
 *   而里面的东西对「理解这个项目」毫无帮助 —— 不跳的话时间全花在这儿了。
 * - **限大小**。生成的代码、压缩过的 JS 动辄几 MB 一行,tree-sitter 解析这种文件
 *   会吃掉大量内存。
 * - **可取消**。用户切走或者点停止,扫描要能立刻停,不能占着 CPU 继续跑。
 */
object CodeIndexer {

    private const val TAG = "XincodeCodeIndex"

    /** 单文件大小上限。超过就跳过 —— 多半是生成的或压缩过的,解析价值低、代价高。 */
    private const val MAX_FILE_BYTES = 1024 * 1024

    /** 一次扫描最多处理多少文件,防止指向了一个巨大目录时卡死。 */
    private const val MAX_FILES = 5000

    /** 不进索引的目录名。这些地方的文件数量常常是源码的几十倍。 */
    private val SKIP_DIRS = setOf(
        ".git", ".svn", ".hg", "node_modules", "build", "out", "dist", "target",
        ".gradle", ".idea", "vendor", "__pycache__", ".venv", "venv",
        "Pods", ".next", ".nuxt", "coverage", ".cache"
    )

    data class Progress(val scanned: Int, val indexed: Int, val skipped: Int, val symbols: Int)

    /**
     * 索引一个目录。
     *
     * @param force true 时忽略指纹全部重扫(用于「重建索引」)。
     * @param onProgress 每处理若干文件回调一次,供 UI 显示进度。
     */
    suspend fun index(
        database: AppDatabase,
        root: String,
        force: Boolean = false,
        onProgress: ((Progress) -> Unit)? = null
    ): Progress = withContext(Dispatchers.IO) {
        val dao = database.codeIndexDao()
        val rootDir = File(root)
        if (!rootDir.isDirectory) {
            Log.w(TAG, "not a directory: $root")
            return@withContext Progress(0, 0, 0, 0)
        }
        if (!CodeGraphNative.available) {
            Log.w(TAG, "kernel unavailable, index skipped")
            return@withContext Progress(0, 0, 0, 0)
        }

        if (force) {
            dao.clearSymbols(root); dao.clearEdges(root); dao.clearFiles(root)
        }

        var scanned = 0
        var indexed = 0
        var skipped = 0
        var symbols = 0

        val stack = ArrayDeque<File>()
        stack += rootDir

        while (stack.isNotEmpty() && scanned < MAX_FILES) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeFirst()
            val children = dir.listFiles() ?: continue

            for (f in children) {
                currentCoroutineContext().ensureActive()
                if (f.isDirectory) {
                    // 隐藏目录一律跳过 —— 除了 .git 之外还有一堆工具的缓存目录,
                    // 一个个列不完,按「点开头」一刀切更省事也更安全
                    if (f.name in SKIP_DIRS || f.name.startsWith(".")) continue
                    stack += f
                    continue
                }
                if (scanned >= MAX_FILES) break

                val lang = CodeGraphNative.languageOf(f.name) ?: continue
                scanned++

                if (f.length() > MAX_FILE_BYTES) { skipped++; continue }

                val fingerprint = "${f.length()}-${f.lastModified()}"
                if (!force && dao.fileRecord(f.absolutePath)?.fingerprint == fingerprint) {
                    skipped++
                    continue
                }

                val n = indexOne(database, root, f, lang, fingerprint)
                if (n >= 0) { indexed++; symbols += n } else skipped++

                if (indexed % 20 == 0) onProgress?.invoke(Progress(scanned, indexed, skipped, symbols))
            }
        }

        val p = Progress(scanned, indexed, skipped, symbols)
        onProgress?.invoke(p)
        Log.i(TAG, "index done: $p")
        p
    }

    /** 索引单个文件。返回抽到的符号数;失败返回 -1。 */
    private suspend fun indexOne(
        database: AppDatabase,
        root: String,
        file: File,
        language: String,
        fingerprint: String
    ): Int {
        val dao = database.codeIndexDao()
        val content = runCatching { file.readText() }.getOrElse { return -1 }
        val json = CodeGraphNative.extract(file.absolutePath, content, language) ?: return -1

        val obj = runCatching { JSONObject(json) }.getOrElse { return -1 }
        if (obj.has("error")) {
            Log.d(TAG, "extract error ${file.name}: ${obj.optString("error")}")
            return -1
        }

        // 重新索引前先清掉这个文件的旧数据,否则改过名的符号会以两份形式留下来
        dao.deleteSymbolsOf(file.absolutePath)
        dao.deleteEdgesOf(file.absolutePath)

        val syms = mutableListOf<CodeSymbolEntity>()
        obj.optJSONArray("nodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                val name = n.optString("name")
                if (name.isBlank()) continue
                syms += CodeSymbolEntity(
                    root = root,
                    filePath = file.absolutePath,
                    name = name,
                    qualifiedName = n.optString("qualifiedName"),
                    kind = n.optString("kind"),
                    startLine = n.optInt("startLine"),
                    endLine = n.optInt("endLine"),
                    signature = n.optString("signature")
                )
            }
        }

        val edges = mutableListOf<CodeEdgeEntity>()
        obj.optJSONArray("edges")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                edges += CodeEdgeEntity(
                    root = root,
                    filePath = file.absolutePath,
                    kind = e.optString("kind"),
                    fromName = e.optString("from"),
                    toName = e.optString("to"),
                    line = e.optInt("line")
                )
            }
        }
        // refs 是「提到了这个名字但还没确定是谁」。也当成边存下来 ——
        // 跨文件解析需要看到全量索引,单文件抽取时定不了,但存下来至少能回答
        // 「哪些地方提到过它」,这已经比 grep 准(grep 连注释和字符串都算)。
        obj.optJSONArray("refs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val name = r.optString("name")
                if (name.isBlank()) continue
                edges += CodeEdgeEntity(
                    root = root,
                    filePath = file.absolutePath,
                    kind = "references",
                    fromName = r.optString("fromId"),
                    toName = name,
                    line = r.optInt("line")
                )
            }
        }

        if (syms.isNotEmpty()) dao.insertSymbols(syms)
        if (edges.isNotEmpty()) dao.insertEdges(edges)
        dao.upsertFile(
            CodeFileEntity(
                filePath = file.absolutePath, root = root,
                fingerprint = fingerprint, symbolCount = syms.size
            )
        )
        return syms.size
    }
}
