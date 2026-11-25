package com.wolfram.lumen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Which AI answers Jarvis's questions, and how its keys begin. Groq is free (no card needed);
 * Claude and Gemini are paid. Pick one on the phone remote.
 */
enum class Brain(val id: String, val label: String, val prefix: String) {
    CLAUDE("claude", "Claude", "sk-ant-"),
    GEMINI("gemini", "Gemini", "AIza"),
    GROQ("groq", "Groq", "gsk_");

    companion object {
        fun byId(id: String?): Brain? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Jarvis's API keys. They never live in the app or on GitHub: you paste them on the phone remote,
 * and the TV keeps them encrypted with a key that only this TV's secure key store holds.
 */
object JarvisKeys {
    private const val PREFS = "jarvis"
    private const val ALIAS = "plaizieros-jarvis"
    private const val BRAIN = "brain"

    /** Keys never contain spaces: whatever the phone added when pasting (spaces, new lines) goes. */
    fun cleanKey(raw: String): String = raw.filterNot { it.isWhitespace() }

    /** Which AI a key belongs to, from how it begins (null when it isn't one of them). */
    fun detect(key: String): Brain? {
        val k = cleanKey(key)
        return Brain.entries.firstOrNull { k.startsWith(it.prefix) }
    }

    /** Null when [key] looks right for [brain]; otherwise what to tell the person. */
    fun check(brain: Brain, key: String): String? {
        val k = cleanKey(key)
        val found = detect(k)
        return when {
            k.isEmpty() -> "Paste the key first."
            found != null && found != brain -> "That's a ${found.label} key, not a ${brain.label} key."
            found == null -> "A ${brain.label} key starts with ${brain.prefix}. Copy the whole key."
            k.length < 30 -> "That key is too short. Copy the whole key."
            k.length > 200 -> "That's more than a key. Copy only the key."
            else -> null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypts and keeps [key]. Throws if this TV's key store doesn't work (the phone then shows why). */
    fun save(context: Context, brain: Brain, key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(cleanKey(key).toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(brain.id, pack(cipher.iv, sealed)).apply()
    }

    /** The key for [brain], or null when there is none (or it can't be read any more). */
    fun load(context: Context, brain: Brain): String? = runCatching {
        val (iv, sealed) = unpack(prefs(context).getString(brain.id, null)) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(sealed), Charsets.UTF_8)
    }.getOrNull()

    /** Encrypts any secret (connection passwords and keys) with the same TV-only key. */
    internal fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return pack(cipher.iv, cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    internal fun open(stored: String?): String? = runCatching {
        val (iv, sealed) = unpack(stored) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(sealed), Charsets.UTF_8)
    }.getOrNull()

    fun has(context: Context, brain: Brain): Boolean = prefs(context).contains(brain.id)

    fun forget(context: Context, brain: Brain) {
        prefs(context).edit().remove(brain.id).apply()
    }

    fun brain(context: Context): Brain = Brain.byId(prefs(context).getString(BRAIN, null)) ?: Brain.CLAUDE

    fun setBrain(context: Context, brain: Brain) {
        prefs(context).edit().putString(BRAIN, brain.id).apply()
    }

    /** "iv:sealed", both in Base64. */
    internal fun pack(iv: ByteArray, sealed: ByteArray): String =
        Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(sealed)

    internal fun unpack(stored: String?): Pair<ByteArray, ByteArray>? {
        if (stored.isNullOrBlank()) return null
        val parts = stored.split(":")
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.getDecoder().decode(parts[0])
            val sealed = Base64.getDecoder().decode(parts[1])
            if (iv.isEmpty() || sealed.isEmpty()) null else iv to sealed
        }.getOrNull()
    }
}
