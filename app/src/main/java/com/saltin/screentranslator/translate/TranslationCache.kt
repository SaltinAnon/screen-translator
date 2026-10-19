package com.saltin.screentranslator.translate

import android.util.LruCache

/**
 * 译文 LRU 缓存。
 *
 * 屏幕内容大量重复（同一段文字会连续几十帧出现在同屏），
 * 命中缓存可以直接省掉一次 OCR 之后的翻译调用——这是把感知延迟压到 ~1 秒的关键。
 */
class TranslationCache(maxEntries: Int = 512) {

    private val lru = LruCache<String, String>(maxEntries)

    fun get(text: String, source: String, target: String): String? =
        lru.get(key(text, source, target))

    fun put(text: String, source: String, target: String, translated: String) {
        if (text.isBlank() || translated.isBlank()) return
        lru.put(key(text, source, target), translated)
    }

    fun clear() = lru.evictAll()

    fun size(): Int = lru.size()

    private fun key(text: String, source: String, target: String): String =
        "$source>$target|${text.length}|${text.hashCode()}"
}
