package com.xincode.app

import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity
import com.xincode.tools.PathResolver
import java.io.File

/**
 * gap-26 SKILL.md 目录约定 / 技能导入(对标 grok 的技能目录发现)。
 *
 * 约定:在工作区的 `skills/<名字>/SKILL.md` 或 `.xincode/skills/<名字>/SKILL.md` 放技能文件,
 * 可选 YAML frontmatter 声明 name/description,正文即技能指令。App 启动时自动发现并 upsert 到 Room,
 * 从而进入 gap-11 的系统提示技能清单,模型可用 invoke_skill 调用。
 */
object SkillImporter {
    private const val TAG = "SkillImporter"

    /** 解析一个 SKILL.md 文本为 (name, description, content)。fallbackName 用于无 frontmatter 时。 */
    fun parse(markdown: String, fallbackName: String): Triple<String, String, String> {
        var name = fallbackName
        var description = ""
        var body = markdown
        val trimmed = markdown.trimStart()
        if (trimmed.startsWith("---")) {
            val end = trimmed.indexOf("\n---", 3)
            if (end > 0) {
                val front = trimmed.substring(3, end)
                body = trimmed.substring(end + 4).trimStart('\n')
                for (line in front.lines()) {
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim().trim('"', '\'')
                    when (k) {
                        "name" -> if (v.isNotBlank()) name = v
                        "description", "desc" -> description = v
                    }
                }
            }
        }
        // 无 frontmatter description 时,取正文首个非空行做描述。
        if (description.isBlank()) {
            description = body.lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim()?.take(200) ?: ""
        }
        return Triple(name, description, body)
    }

    /** 扫描工作区约定目录,发现所有 SKILL.md,upsert 到 Room。返回导入数量。 */
    suspend fun autoDiscover(database: AppDatabase): Int {
        val roots = listOf(
            File(PathResolver.WORKSPACE_ROOT, "skills"),
            File(PathResolver.WORKSPACE_ROOT, ".xincode/skills")
        )
        var count = 0
        val dao = database.skillDao()
        for (root in roots) {
            if (!root.isDirectory) continue
            val files = root.walkTopDown().filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            for (f in files) {
                try {
                    val dirName = f.parentFile?.name ?: "skill"
                    val (name, desc, content) = parse(f.readText(), dirName)
                    val existing = dao.getByName(name)
                    dao.upsert(
                        SkillEntity(
                            id = existing?.id ?: 0,
                            name = name,
                            description = desc,
                            content = content,
                            source = "bundled",  // Hermes-①:SKILL.md 导入的技能只读,后台复盘不可改
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "import skill failed for ${f.path}: ${e.message}")
                }
            }
        }
        if (count > 0) Log.i(TAG, "Auto-discovered $count skills from SKILL.md")
        return count
    }
}
