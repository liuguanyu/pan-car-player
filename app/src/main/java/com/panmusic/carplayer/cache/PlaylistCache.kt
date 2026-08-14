package com.panmusic.carplayer.cache

import com.panmusic.carplayer.model.Song
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨 Activity 传递数据 — 避免 Intent Serializable 序列化大数据
 */
@Singleton
class PlaylistCache @Inject constructor() {
    private val cache = ConcurrentHashMap<String, List<Song>>()

    fun put(key: String, songs: List<Song>) {
        cache[key] = songs
    }

    fun getAndRemove(key: String): List<Song>? {
        return cache.remove(key)
    }

    fun get(key: String): List<Song>? {
        return cache[key]
    }

    fun clear() {
        cache.clear()
    }
}
