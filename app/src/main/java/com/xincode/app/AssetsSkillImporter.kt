package com.xincode.app

import android.content.Context
import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity

/**
 * 内置技能包:从 `assets/skills/<名字>/SKILL.md` 导入随 APK 分发的技能。
 *
 * 与 [SkillImporter](工作区自动发现)互补:这里读的是打进 APK 的资产,
 * 全新安装开箱即用,不用等用户往工作区放文件。按名字查重,重复调用无副作用
 * (老用户升级后首次启动也会装上,不依赖首启标志)。
 */
object AssetsSkillImporter {
    private const val TAG = "AssetsSkillImporter"
    private const val ASSET_ROOT = "skills"

    /** 扫描 assets/skills/ 下所有子目录的 SKILL.md,upsert 进 Room。返回导入数量。 */
    suspend fun install(context: Context, database: AppDatabase): Int {
        val dirs = try {
            context.assets.list(ASSET_ROOT) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "assets list failed: ${e.message}")
            emptyArray()
        }
        if (dirs.isEmpty()) return 0

        val dao = database.skillDao()
        var count = 0
        for (dir in dirs) {
            val path = "$ASSET_ROOT/$dir/SKILL.md"
            val text = try {
                context.assets.open(path).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                continue // 目录里没有 SKILL.md 就跳过,不报错
            }
            val (name, desc, content) = SkillImporter.parse(text, dir)
            val existing = dao.getByName(name)
            dao.upsert(
                SkillEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    description = desc,
                    content = content,
                    source = "bundled", // 随包技能只读,后台复盘不可改
                    // 保留既有生命周期:插件商店里卸载(归档)的技能,重导入不复活。
                    state = existing?.state ?: "active",
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            count++
        }
        if (count > 0) Log.i(TAG, "imported $count bundled skills from assets")
        return count
    }
}
