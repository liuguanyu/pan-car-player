package com.panmusic.carplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.panmusic.carplayer.R
import com.panmusic.carplayer.model.Song

/**
 * 当前播放列表适配器 — 支持搜索过滤 + 高亮当前播放歌曲。
 */
class CurrentPlaylistAdapter(
    private val onItemClick: (Song, Int) -> Unit
) : RecyclerView.Adapter<CurrentPlaylistAdapter.ViewHolder>(), Filterable {

    private var originalSongs: List<Song> = emptyList()
    private var filteredSongs: List<Song> = emptyList()
    private var currentPlayingSongId: Long = -1

    fun setSongs(songs: List<Song>) {
        originalSongs = songs
        filteredSongs = songs
        notifyDataSetChanged()
    }

    fun setCurrentPlayingSongId(songId: Long) {
        if (currentPlayingSongId == songId) return
        val oldId = currentPlayingSongId
        currentPlayingSongId = songId
        for (i in filteredSongs.indices) {
            val id = filteredSongs[i].id
            if (id == oldId || id == songId) {
                notifyItemChanged(i)
            }
        }
    }

    fun getOriginalPosition(song: Song): Int = originalSongs.indexOfFirst { it.id == song.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_current_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = filteredSongs[position]
        holder.bind(song, song.id == currentPlayingSongId)
        holder.itemView.setOnClickListener {
            onItemClick(song, getOriginalPosition(song))
        }
    }

    override fun getItemCount(): Int = filteredSongs.size

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence): FilterResults {
            val pattern = constraint.toString().lowercase().trim()
            val result = if (pattern.isEmpty()) {
                originalSongs
            } else {
                originalSongs.filter { song ->
                    song.serverFilename.lowercase().contains(pattern)
                }
            }
            return FilterResults().apply { values = result }
        }

        override fun publishResults(constraint: CharSequence, results: FilterResults) {
            @Suppress("UNCHECKED_CAST")
            filteredSongs = results.values as List<Song>
            notifyDataSetChanged()
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.tv_song_name)
        private val accentColor = itemView.context.getColor(R.color.accent)
        private val primaryColor = itemView.context.getColor(R.color.textPrimary)

        fun bind(song: Song, isPlaying: Boolean) {
            nameView.text = song.serverFilename
            if (isPlaying) {
                nameView.setTextColor(accentColor)
                nameView.textScaleX = 1.05f
            } else {
                nameView.setTextColor(primaryColor)
                nameView.textScaleX = 1.0f
            }
        }
    }
}
