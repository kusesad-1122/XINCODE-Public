package com.xincode.app

import com.xincode.data.AppDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多 Profile:一套独立的配置环境(供应商、功能模型分配、委托模型、全局提示等)。
 *
 * ## 为什么这么设计
 *
 * 最直觉的做法是给 settings 表加一列 profileId,然后把几十处 `get(key)` 全改成
 * `get(profileId, key)`。那样改动横跨整个代码库,一处漏改就是「切了 profile 但某个
 * 设置没跟着变」的幽灵 bug,而且老数据必须整表迁移 —— 迁移写错等于把用户配置全丢。
 *
 * 这里改用【键名前缀】:
 *   默认 profile(id=0)  →  沿用现有键名,一个字节都不动
 *   其它 profile(id=N)  →  键名前面加 `p{N}.`
 *
 * 好处是老用户的数据【完全不需要迁移】:不新建 profile 就还是原来那套键,行为与升级前
 * 逐字节一致。风险从「全库迁移」降到「只影响主动新建 profile 的人」。
 *
 * 代价是:只有走 [key] 包装过的读写才受 profile 影响。所以 [SCOPED_PREFIXES] 明确
 * 列出哪些配置是分 profile 的,其余(主题、字号这类)保持全局共享 —— 换 profile 不该
 * 把界面主题也换掉。
 */
object Profiles {

    /** 当前 profile 存在这个键里。它本身【不分】profile,否则就自我指涉了。 */
    private const val KEY_CURRENT = "profile_current_id"
    private const val KEY_LIST = "profile_list"      // JSON 数组:[{id,name}]
    private const val KEY_TRASH_PREFIX = "profile_trash_"   // 删除备份

    const val DEFAULT_ID = 0L

    data class Profile(val id: Long, val name: String)

    /**
     * 受 profile 隔离的键前缀。不在这个名单里的键全局共享。
     *
     * 分开的:供应商相关、功能模型分配、副模型委托、全局系统提示。
     * 共享的:主题/字号/回车发送这类界面偏好,以及更新检查的节流记录。
     */
    private val SCOPED_PREFIXES = listOf(
        "fn_",           // 功能模型配置
        "aux_",          // 副模型委托
        "global_prompt", // 全局系统提示
        "web_search_",   // 搜索配置
        "collab_",       // 协作模式
    )

    fun isScoped(key: String): Boolean = SCOPED_PREFIXES.any { key.startsWith(it) }

    /** 把逻辑键名映射成实际存储键名。默认 profile 原样返回。 */
    fun key(profileId: Long, logicalKey: String): String =
        if (profileId == DEFAULT_ID || !isScoped(logicalKey)) logicalKey
        else "p$profileId.$logicalKey"

    suspend fun currentId(db: AppDatabase): Long =
        db.settingDao().get(KEY_CURRENT)?.toLongOrNull() ?: DEFAULT_ID

    suspend fun setCurrent(db: AppDatabase, id: Long) {
        db.settingDao().put(KEY_CURRENT, id.toString())
    }

    suspend fun list(db: AppDatabase): List<Profile> {
        val raw = db.settingDao().get(KEY_LIST)
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Profile(o.getLong("id"), o.optString("name"))
            }
        }.getOrDefault(emptyList())
        // 默认 profile 永远在列表里,不依赖存储 —— 存储被清掉也不该让用户没得选
        return listOf(Profile(DEFAULT_ID, "默认")) + parsed.filter { it.id != DEFAULT_ID }
    }

    private suspend fun saveList(db: AppDatabase, profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.filter { it.id != DEFAULT_ID }.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name))
        }
        db.settingDao().put(KEY_LIST, arr.toString())
    }

    /** 新建一个空 profile。返回新 id。 */
    suspend fun create(db: AppDatabase, name: String): Long {
        val existing = list(db)
        // id 取当前最大值 +1,不复用已删除的 id —— 复用会让残留的旧键被新 profile 读到
        val newId = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        saveList(db, existing + Profile(newId, name.trim().ifBlank { "配置 $newId" }))
        return newId
    }

    suspend fun rename(db: AppDatabase, id: Long, name: String) {
        if (id == DEFAULT_ID) return   // 默认那个不给改名,它是个固定锚点
        saveList(db, list(db).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /**
     * 删除 profile,连同它的所有键。
     * 默认 profile 不可删 —— 删了之后所有未加前缀的键就没有归属了。
     *
     * 删除【前先备份】。hermes-studio 在剥离 profile 凭证前会存一份 .env.bak,
     * 这个习惯值得学:配置是用户花时间攒出来的,一次误点不该让它彻底消失。
     * 备份存在 settings 里(键 profile_trash_<id>),用 [restore] 可以整套恢复。
     */
    suspend fun delete(db: AppDatabase, id: Long) {
        if (id == DEFAULT_ID) return
        val dao = db.settingDao()
        val name = list(db).firstOrNull { it.id == id }?.name.orEmpty()
        // 先备份再删。顺序反了的话中途失败就真没了。
        runCatching {
            val backup = JSONObject().put("name", name)
            val kv = JSONObject()
            dao.getByPrefix("p$id.").forEach { kv.put(it.key.removePrefix("p$id."), it.value) }
            backup.put("settings", kv)
            backup.put("deletedAt", System.currentTimeMillis())
            dao.put("$KEY_TRASH_PREFIX$id", backup.toString())
        }
        saveList(db, list(db).filter { it.id != id })
        runCatching { dao.deleteByPrefix("p$id.") }
        if (currentId(db) == id) setCurrent(db, DEFAULT_ID)
    }

    /** 被删除的 profile(可恢复)。 */
    data class Trashed(val id: Long, val name: String, val deletedAt: Long)

    suspend fun listTrashed(db: AppDatabase): List<Trashed> =
        runCatching {
            db.settingDao().getByPrefix(KEY_TRASH_PREFIX).mapNotNull { e ->
                val id = e.key.removePrefix(KEY_TRASH_PREFIX).toLongOrNull() ?: return@mapNotNull null
                val o = runCatching { JSONObject(e.value) }.getOrNull() ?: return@mapNotNull null
                Trashed(id, o.optString("name", "配置 $id"), o.optLong("deletedAt", 0))
            }.sortedByDescending { it.deletedAt }
        }.getOrDefault(emptyList())

    /** 恢复一个被删除的 profile。返回恢复后的 id(与原 id 相同)。 */
    suspend fun restore(db: AppDatabase, id: Long): Boolean {
        val dao = db.settingDao()
        val raw = dao.get("$KEY_TRASH_PREFIX$id") ?: return false
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val name = o.optString("name", "配置 $id")
        val kv = o.optJSONObject("settings") ?: JSONObject()
        // 名单里已经有同 id 就不重复添加(避免重复点恢复产生两条)
        val current = list(db)
        if (current.none { it.id == id }) saveList(db, current + Profile(id, name))
        kv.keys().forEach { k -> runCatching { dao.put("p$id.$k", kv.optString(k, "")) } }
        runCatching { dao.deleteByPrefix("$KEY_TRASH_PREFIX$id") }
        return true
    }

    /** 永久清空回收站。 */
    suspend fun emptyTrash(db: AppDatabase) {
        runCatching { db.settingDao().deleteByPrefix(KEY_TRASH_PREFIX) }
    }

    /** 克隆:把源 profile 的所有受隔离键复制一份到新 profile 下。 */
    suspend fun clone(db: AppDatabase, sourceId: Long, name: String): Long {
        val newId = create(db, name)
        val dao = db.settingDao()
        for (prefix in SCOPED_PREFIXES) {
            val srcPrefix = if (sourceId == DEFAULT_ID) prefix else "p$sourceId.$prefix"
            runCatching {
                dao.getByPrefix(srcPrefix).forEach { entry ->
                    // 去掉源前缀拿回逻辑键,再套上新 profile 的前缀
                    val logical = if (sourceId == DEFAULT_ID) entry.key
                    else entry.key.removePrefix("p$sourceId.")
                    dao.put("p$newId.$logical", entry.value)
                }
            }
        }
        return newId
    }

    /** 导出成 JSON 文本(不含任何密钥 —— 见下方说明)。 */
    suspend fun export(db: AppDatabase, id: Long): String {
        val dao = db.settingDao()
        val obj = JSONObject()
        obj.put("schema", "xincode.profile.v1")
        obj.put("name", list(db).firstOrNull { it.id == id }?.name ?: "配置")
        val settings = JSONObject()
        for (prefix in SCOPED_PREFIXES) {
            val realPrefix = if (id == DEFAULT_ID) prefix else "p$id.$prefix"
            runCatching {
                dao.getByPrefix(realPrefix).forEach { entry ->
                    val logical = if (id == DEFAULT_ID) entry.key else entry.key.removePrefix("p$id.")
                    // API Key 一律不导出。这份 JSON 会被分享出去,里面绝不能带凭证 ——
                    // 导入方自己填 Key,配置结构照样能复用。
                    if (logical.endsWith("_api_key")) return@forEach
                    settings.put(logical, entry.value)
                }
            }
        }
        obj.put("settings", settings)
        return obj.toString(2)
    }

    /** 从导出的 JSON 建一个新 profile。返回新 id;格式不对返回 null。 */
    suspend fun import(db: AppDatabase, json: String): Long? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (obj.optString("schema") != "xincode.profile.v1") return null
        val name = obj.optString("name", "导入的配置")
        val settings = obj.optJSONObject("settings") ?: JSONObject()
        val newId = create(db, name)
        val dao = db.settingDao()
        settings.keys().forEach { k ->
            runCatching { dao.put("p$newId.$k", settings.optString(k, "")) }
        }
        return newId
    }
}
