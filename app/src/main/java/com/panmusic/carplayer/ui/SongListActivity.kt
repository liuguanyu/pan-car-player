package com.panmusic.carplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.panmusic.carplayer.R
import com.panmusic.carplayer.cache.PlaylistCache
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.ui.adapter.SongAdapter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 歌曲列表页 — 展示播放列表内的所有歌曲，点击跳转播放器
 */
@AndroidEntryPoint
class SongListActivity : BaseCarActivity() {

    @Inject lateinit var playlistCache: PlaylistCache

    private lateinit var adapter: SongAdapter
    private var songs: List<Song> = emptyList()
    private var playlistId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)

        val cacheKey = intent.getStringExtra("PLAYLIST_CACHE_KEY") ?: ""
        playlistId = intent.getLongExtra("PLAYLIST_ID", 0)
        val rawSongs = playlistCache.getAndRemove(cacheKey) ?: emptyList()
        // 对齐 Java 版：按文件夹路径 + 文件名排序
        val sortedSongs = rawSongs.toMutableList()
        Song.sortSongs(sortedSongs)
        songs = sortedSongs

        adapter = SongAdapter(
            onItemClick = { song, position ->
                val playerCacheKey = "player_playlist_${System.currentTimeMillis()}"
                playlistCache.put(playerCacheKey, songs)
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("PLAYLIST_CACHE_KEY", playerCacheKey)
                        .putExtra("START_INDEX", position)
                )
            }
        )

        val recyclerView: RecyclerView = findViewById(R.id.rv_songs)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        adapter.submitList(songs)

        // 返回按钮
        findViewById<View>(R.id.btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // 添加歌曲按钮
        findViewById<View>(R.id.btn_add_songs).setOnClickListener {
            startActivity(
                Intent(this, FileBrowserActivity::class.java)
                    .putExtra("PLAYLIST_ID", playlistId)
            )
        }
    }
}
