package com.panmusic.carplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.panmusic.carplayer.R
import com.panmusic.carplayer.cache.PlaylistCache
import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.ui.adapter.PlaylistAdapter
import com.panmusic.carplayer.viewmodel.PlaylistViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放列表管理主页
 */
@AndroidEntryPoint
class MainActivity : BaseCarActivity() {

    private val playlistViewModel: PlaylistViewModel by viewModels()

    @Inject lateinit var playlistCache: PlaylistCache

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PlaylistAdapter
    private lateinit var emptyView: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var playlists: List<Playlist> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.rv_playlists)
        emptyView = findViewById(R.id.tv_empty)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        adapter = PlaylistAdapter(
            onItemClick = { playlist -> onPlaylistClick(playlist) },
            onAddSongsClick = { playlist -> onAddSongsClick(playlist) },
            onRenameClick = { playlist -> showRenameDialog(playlist) },
            onDeleteClick = { playlist -> showDeleteConfirmDialog(playlist) },
            onItemLongClick = { playlist -> showPlaylistOptionsDialog(playlist) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 刷新按钮
        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            playlistViewModel.refresh()
        }

        // 下拉刷新
        swipeRefresh.setOnRefreshListener {
            playlistViewModel.refresh()
        }

        findViewById<View>(R.id.btn_create_playlist).setOnClickListener {
            showCreatePlaylistDialog()
        }

        observeState()
        observeEvents()
    }

    private fun observeState() {
        lifecycleScope.launch {
            playlistViewModel.uiState.collectLatest { state ->
                playlists = state.playlists
                adapter.submitList(state.playlists)
                emptyView.visibility = if (state.playlists.isEmpty()) View.VISIBLE else View.GONE
                swipeRefresh.isRefreshing = state.isRefreshing
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            playlistViewModel.events.collectLatest { event ->
                when (event) {
                    is com.panmusic.carplayer.viewmodel.PlaylistUiEvent.PlaylistCreated -> {
                        Toast.makeText(this@MainActivity, "播放列表已创建", Toast.LENGTH_SHORT).show()
                        // 创建后直接跳转文件浏览器添加歌曲
                        startActivity(
                            Intent(this@MainActivity, FileBrowserActivity::class.java)
                                .putExtra("PLAYLIST_ID", event.playlistId)
                        )
                    }
                    is com.panmusic.carplayer.viewmodel.PlaylistUiEvent.PlaylistRenamed -> {
                        Toast.makeText(this@MainActivity, "已重命名", Toast.LENGTH_SHORT).show()
                    }
                    is com.panmusic.carplayer.viewmodel.PlaylistUiEvent.PlaylistDeleted -> {
                        Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    is com.panmusic.carplayer.viewmodel.PlaylistUiEvent.SongsLoaded -> {
                        val cacheKey = "playlist_${event.playlistId}"
                        playlistCache.put(cacheKey, event.songs)
                        startActivity(
                            Intent(this@MainActivity, SongListActivity::class.java)
                                .putExtra("PLAYLIST_ID", event.playlistId)
                                .putExtra("PLAYLIST_CACHE_KEY", cacheKey)
                        )
                    }
                    is com.panmusic.carplayer.viewmodel.PlaylistUiEvent.ShowError -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                    is com.panmusic.carplayer.viewmodel.PlaylistUiEvent.ShowMessage -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * 播放列表长按选项：刷新 / 重命名 / 删除
     */
    private fun showPlaylistOptionsDialog(playlist: Playlist) {
        val options = arrayOf("刷新", "重命名", "删除")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this@MainActivity, "正在刷新...", Toast.LENGTH_SHORT).show()
                        playlistViewModel.refreshPlaylist(playlist.id)
                    }
                    1 -> showRenameDialog(playlist)
                    2 -> showDeleteConfirmDialog(playlist)
                }
            }
            .show()
    }

    private fun onPlaylistClick(playlist: Playlist) {
        playlistViewModel.loadSongs(playlist.id)
    }

    private fun onAddSongsClick(playlist: Playlist) {
        startActivity(
            Intent(this, FileBrowserActivity::class.java)
                .putExtra("PLAYLIST_ID", playlist.id)
        )
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.main_playlist_name_hint)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_create_playlist))
            .setView(input)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    playlistViewModel.createPlaylist(name)
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showRenameDialog(playlist: Playlist) {
        val input = EditText(this)
        input.setText(playlist.name)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.common_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    playlistViewModel.renamePlaylist(playlist, name)
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showDeleteConfirmDialog(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.common_delete))
            .setMessage("确定删除播放列表「${playlist.name}」？歌曲将一并删除。")
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                playlistViewModel.deletePlaylist(playlist.id)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }
}
