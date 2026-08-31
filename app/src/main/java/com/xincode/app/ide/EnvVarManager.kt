package com.xincode.app.ide

import com.xincode.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class EnvVar(val key: String, val value: String, val scope: String = "all") {
    // scope: all | terminal | build
    fun isValid(): Boolean = key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value.isNotEmpty()
}

object EnvVarManager {
    const val KEY = "ide_env_vars_json"
    const val KEY_BUILD_EXTRA = "ide_env_vars_build_extra"
    const val KEY_TERMINAL_EXTRA = "ide_env_vars_terminal_extra"

    fun toJson(vars: List<EnvVar>): String {
        val arr = JSONArray()
        vars.forEach { v ->
            arr.put(JSONObject().apply {
                put("k", v.key); put("v", v.value); put("s", v.scope)
            })
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<EnvVar> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("k", "").trim()
                val v = o.optString("v", "")
                val s = o.optString("s", "all")
                if (k.isBlank()) null else EnvVar(k, v, s)
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun load(db: AppDatabase): List<EnvVar> {
        val raw = db.settingDao().get(KEY) ?: return emptyList()
        return fromJson(raw)
    }

    suspend fun save(db: AppDatabase, vars: List<EnvVar>) {
        db.settingDao().put(KEY, toJson(vars))
    }

    fun observe(db: AppDatabase): Flow<List<EnvVar>> =
        db.settingDao().observe(KEY).map { fromJson(it ?: "") }

    fun toEnvPrefix(vars: List<EnvVar>, scopeFilter: String? = null): String {
        val filtered = if (scopeFilter == null) vars else vars.filter { it.scope == "all" || it.scope == scopeFilter }
        if (filtered.isEmpty()) return ""
        return filtered.joinToString(" ") { "${it.key}=${shellQuote(it.value)}" }
    }

    fun toExportCommands(vars: List<EnvVar>, scopeFilter: String? = null): String {
        val filtered = if (scopeFilter == null) vars else vars.filter { it.scope == "all" || it.scope == scopeFilter }
        if (filtered.isEmpty()) return ""
        return filtered.joinToString("; ") { "export ${it.key}=${shellQuote(it.value)}" }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun validateKey(key: String): String? {
        if (key.isBlank()) return "变量名不能为空"
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return "仅允许字母数字下划线且不能数字开头"
        if (key in setOf("PATH", "HOME", "TERM", "ANDROID_HOME", "JAVA_HOME")) return "系统保留变量，建议用自定义前缀如 MY_"
        return null
    }
}
