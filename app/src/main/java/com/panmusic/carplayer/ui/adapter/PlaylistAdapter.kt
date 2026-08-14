package com.panmusic.carplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.panmusic.carplayer.R
import com.panmusic.carplayer.model.Playlist

/**
 * 播放列表适配器 — ListAdapter + DiffUtil，卡片式车机大屏风格
 */
class PlaylistAdapter(
    private val onItemClick: (Playlist) -> Unit,
    private val onAddSongsClick: (Playlist) -> Unit,
    private val onRenameClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit,
    private val onItemLongClick: (Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.ViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.tv_playlist_name)
        private val countView: TextView = itemView.findViewById(R.id.tv_song_count)
        private val btnAdd: TextView = itemView.findViewById(R.id.btn_add_songs)
        private val btnRename: TextView = itemView.findViewById(R.id.btn_rename)
        private val btnDelete: TextView = itemView.findViewById(R.id.btn_delete)

        fun bind(playlist: Playlist) {
            nameView.text = playlist.name
            countView.text = "${playlist.songCount}"

            // 点击卡片主体 → 进入歌曲列表
            itemView.setOnClickListener { onItemClick(playlist) }
            // 长按 → 选项菜单
            itemView.setOnLongClickListener {
                onItemLongClick(playlist)
                true
            }
            // 操作按钮
            btnAdd.setOnClickListener { onAddSongsClick(playlist) }
            btnRename.setOnClickListener { onRenameClick(playlist) }
            btnDelete.setOnClickListener { onDeleteClick(playlist) }
        }
    }
}

class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
    override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
        oldItem == newItem
}
