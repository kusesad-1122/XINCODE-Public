package com.xincode.tools

import okhttp3.Cache
import java.io.File

/**
 * Shared OkHttp disk cache provider.
 * All HTTP clients share one cache directory to avoid redundant fetches.
 */
object HttpCacheProvider {
    private const val CACHE_SIZE = 10 * 1024 * 1024L // 10MB
    private const val CACHE_DIR = "okhttp_cache"

    @Volatile
    private var cache: Cache? = null

    /** Initialize with application cache directory. Call once from XincodeApplication.onCreate(). */
    fun init(cacheDir: File) {
        if (cache != null) return
        val dir = File(cacheDir, CACHE_DIR)
        dir.mkdirs()
        cache = Cache(dir, CACHE_SIZE)
    }

    /** Get the shared cache instance. Must be called after init(). */
    fun get(): Cache? = cache
}
