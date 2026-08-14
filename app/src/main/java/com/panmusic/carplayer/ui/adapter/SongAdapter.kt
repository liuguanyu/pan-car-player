package com.panmusic.carplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.panmusic.carplayer.R
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.util.toFileSizeString

/**
 * 歌曲列表适配器 — ListAdapter + DiffUtil，车机大屏风格
 */
class SongAdapter(
    private val onItemClick: (Song, Int) -> Unit
) : ListAdapter<Song, SongAdapter.ViewHolder>(SongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val indexView: TextView = itemView.findViewById(R.id.tv_song_index)
        private val nameView: TextView = itemView.findViewById(R.id.tv_song_name)
        private val infoView: TextView = itemView.findViewById(R.id.tv_song_info)

        fun bind(song: Song, position: Int) {
            indexView.text = "${position + 1}"
            nameView.text = song.serverFilename
            infoView.text = song.size.toFileSizeString()

            itemView.setOnClickListener { onItemClick(song, position) }
        }
    }
}

class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
    override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean =
        oldItem == newItem
}
