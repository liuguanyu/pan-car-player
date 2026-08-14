package com.panmusic.carplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.viewModels
import com.panmusic.carplayer.R
import com.panmusic.carplayer.model.FileItem
import com.panmusic.carplayer.ui.adapter.FileAdapter
import com.panmusic.carplayer.viewmodel.FileBrowserEvent
import com.panmusic.carplayer.viewmodel.FileBrowserViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 文件浏览器 — 浏览百度网盘目录，选择音乐文件/文件夹添加到播放列表
 *
 * 交互逻辑（车机优化）：
 * - 文件夹：点击进入子目录，长按选中
 * - 文件：点击切换选中
 * - 底部栏：显示已选数量，"添加到播放列表"按钮
 * - 返回按钮、面包屑导航
 */
@AndroidEntryPoint
class FileBrowserActivity : BaseCarActivity() {

    private val fileBrowserViewModel: FileBrowserViewModel by viewModels()

    private lateinit var adapter: FileAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var breadcrumbView: TextView
    private lateinit var selectedCountView: TextView
    private lateinit var bottomBar: View
    private var playlistId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        playlistId = intent.getLongExtra("PLAYLIST_ID", 0)

        recyclerView = findViewById(R.id.rv_files)
        emptyView = findViewById(R.id.tv_empty)
        breadcrumbView = findViewById(R.id.tv_breadcrumb)
        selectedCountView = findViewById(R.id.tv_selected_count)
        bottomBar = findViewById(R.id.layout_bottom_bar)

        adapter = FileAdapter(
            onItemClick = { item -> fileBrowserViewModel.toggleSelection(item) },
            onItemLongClick = { item -> fileBrowserViewModel.toggleSelection(item) },
            onNavigateClick = { item -> fileBrowserViewModel.loadDirectory(item.path) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 返回按钮
        findViewById<View>(R.id.btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // 扫描全部
        findViewById<View>(R.id.btn_scan_folder).setOnClickListener {
            fileBrowserViewModel.scanAndAddToPlaylist(playlistId, "/")
        }

        // 添加选中
        findViewById<View>(R.id.btn_add_selected).setOnClickListener {
            fileBrowserViewModel.addSelectedToPlaylist(playlistId)
        }

        observeState()
        observeEvents()

        // 加载根目录
        fileBrowserViewModel.loadDirectory("/")
    }

    private fun observeState() {
        lifecycleScope.launch {
            fileBrowserViewModel.uiState.collectLatest { state ->
                // 空视图
                emptyView.visibility = if (state.items.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE

                // 面包屑
                breadcrumbView.text = "📁 ${state.currentPath}"

                // 列表数据
                adapter.setSelectedItems(state.selectedItems.map { it.fsId }.toSet())
                adapter.submitList(state.items)

                // 底部栏：已选数量
                val selectedCount = state.selectedItems.size
                if (selectedCount > 0) {
                    bottomBar.visibility = View.VISIBLE
                    selectedCountView.text = "已选 $selectedCount 项"
                } else {
                    bottomBar.visibility = View.GONE
                }

                // 扫描中
                findViewById<View>(R.id.progress_bar).visibility =
                    if (state.isScanning) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            fileBrowserViewModel.events.collectLatest { event ->
                when (event) {
                    is FileBrowserEvent.ScanComplete -> {
                        Toast.makeText(this@FileBrowserActivity,
                            "已添加 ${event.count} 首歌曲", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    is FileBrowserEvent.Error -> {
                        Toast.makeText(this@FileBrowserActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
