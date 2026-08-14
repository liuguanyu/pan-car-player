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
 * 播放列表网格适配器 — ListAdapter + DiffUtil
 */
class PlaylistGridAdapter(
    private val onItemClick: (Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistGridAdapter.ViewHolder>(PlaylistGridDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.tv_playlist_name)
        private val countView: TextView = itemView.findViewById(R.id.tv_song_count)

        fun bind(playlist: Playlist) {
            nameView.text = playlist.name
            countView.text = "${playlist.songCount} 首"

            itemView.setOnClickListener { onItemClick(playlist) }
        }
    }
}

class PlaylistGridDiffCallback : DiffUtil.ItemCallback<Playlist>() {
    override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
        oldItem == newItem
}
