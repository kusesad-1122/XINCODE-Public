package com.xincode.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores and retrieves encrypted values using Android Keystore.
 * On API 28+ devices with StrongBox-capable hardware, hardware-backed keys are used.
 * Falls back to software implementation if StrongBox is unavailable.
 */
class KeystoreProvider(private val alias: String = "xincode_master_key") {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    init {
        ensureKey()
    }

    private fun ensureKey() {
        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true) // API 28+
                .build()
            try {
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } catch (e: Exception) {
                // StrongBox unavailable — fall back to software
                val fallbackSpec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(fallbackSpec)
                keyGenerator.generateKey()
            }
        }
    }

    /**
     * 并发串行化锁 + 解密缓存。
     *
     * 根因:Android Keystore 上对同一把(尤其硬件/StrongBox 支持的)密钥做加解密操作**不是线程安全**的——
     * 并发的 cipher.init/doFinal 会抛 "Keystore operation failed"。XINCODE 里 wolfpack_run 并行拉起多个
     * 子 agent、以及后台复盘分身 / cron worker 都共享同一个 KeystoreProvider,同一瞬间并发解密 API key 就会撞车。
     *
     * 解法:(1) 用 [lock] 串行化所有 keystore 操作;(2) 用 [decryptCache] 缓存"密文→明文",
     * 同一个 API key 的重复解密直接命中缓存,既省 keystore 调用又几乎消除撞车窗口。
     */
    private val lock = Any()
    private val decryptCache = HashMap<String, String>()

    fun encrypt(plaintext: String): ByteArray = synchronized(lock) {
        val secretKey = keyStore.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        iv + ciphertext
    }

    fun decrypt(encrypted: ByteArray): String {
        val cacheKey = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        synchronized(lock) {
            decryptCache[cacheKey]?.let { return it }
            // 串行 + 一次瞬态重试(避免偶发的 keystore 抖动直接失败)。
            var lastErr: Exception? = null
            repeat(2) {
                try {
                    val secretKey = keyStore.getKey(alias, null) as SecretKey
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val iv = encrypted.copyOfRange(0, 12)
                    val ciphertext = encrypted.copyOfRange(12, encrypted.size)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                    val plain = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
                    decryptCache[cacheKey] = plain
                    return plain
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: IllegalStateException("Keystore decrypt failed")
        }
    }
}