package com.baidu.carplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.R;
import com.baidu.carplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    private List<Song> songs = new ArrayList<>();
    private OnSongClickListener listener;
    private long currentPlayingSongId = -1;
    
    // 多选模式相关
    private boolean isSelectionMode = false;
    private Set<Long> selectedSongIds = new HashSet<>();
    private OnSelectionChangeListener selectionChangeListener;

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
        void onPlayFromHereClick(Song song, int position);
        void onSongLongClick(Song song, int position);
    }
    
    public interface OnSelectionChangeListener {
        void onSelectionChanged(int count);
    }

    public void setOnSongClickListener(OnSongClickListener listener) {
        this.listener = listener;
    }
    
    public void setOnSelectionChangeListener(OnSelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }
    
    public void setSelectionMode(boolean selectionMode) {
        if (this.isSelectionMode != selectionMode) {
            this.isSelectionMode = selectionMode;
            if (!selectionMode) {
                selectedSongIds.clear();
                notifySelectionChanged();
            }
            notifyDataSetChanged();
        }
    }
    
    public boolean isSelectionMode() {
        return isSelectionMode;
    }
    
    public void toggleSelection(long songId) {
        if (selectedSongIds.contains(songId)) {
            selectedSongIds.remove(songId);
        } else {
            selectedSongIds.add(songId);
        }
        notifySelectionChanged();
        notifyDataSetChanged();
    }
    
    public void selectAll() {
        for (Song song : songs) {
            selectedSongIds.add(song.getFsId());
        }
        notifySelectionChanged();
        notifyDataSetChanged();
    }
    
    public List<Song> getSelectedSongs() {
        List<Song> result = new ArrayList<>();
        for (Song song : songs) {
            if (selectedSongIds.contains(song.getFsId())) {
                result.add(song);
            }
        }
        return result;
    }
    
    private void notifySelectionChanged() {
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChanged(selectedSongIds.size());
        }
    }

    public void setSongs(List<Song> songs) {
        this.songs = songs != null ? songs : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void addSong(Song song) {
        songs.add(song);
        notifyItemInserted(songs.size() - 1);
    }

    public void removeSong(int position) {
        if (position >= 0 && position < songs.size()) {
            songs.remove(position);
            notifyItemRemoved(position);
        }
    }

    public void setCurrentPlayingSongId(long songId) {
        if (this.currentPlayingSongId != songId) {
            long oldId = this.currentPlayingSongId;
            this.currentPlayingSongId = songId;
            
            // 刷新受影响的项
            if (songs != null) {
                for (int i = 0; i < songs.size(); i++) {
                    if (songs.get(i).getId() == oldId || songs.get(i).getId() == songId) {
                        notifyItemChanged(i);
                    }
                }
            }
        }
    }

    /**
     * 按路径和歌曲标题排序
     * 先按路径（文件夹）排序，保证同一文件夹的歌曲在一起
     * 然后在同一文件夹内按标题排序
     * @param ascending true为正序，false为倒序
     */
    public void sortByTitle(boolean ascending) {
        Song.sortSongs(songs, ascending);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = songs.get(position);
        holder.bind(song);
        
        // 设置斑马纹背景
        int backgroundColor = (position % 2 == 0)
            ? holder.itemView.getContext().getResources().getColor(R.color.list_item_background)
            : holder.itemView.getContext().getResources().getColor(R.color.list_item_background_alt);
        holder.itemView.setBackgroundColor(backgroundColor);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    class SongViewHolder extends RecyclerView.ViewHolder {
        private TextView songTitle;
        private TextView songPath;
        private ImageButton songMore;
        private CheckBox checkboxSelect;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            songTitle = itemView.findViewById(R.id.song_title);
            songPath = itemView.findViewById(R.id.song_path);
            songMore = itemView.findViewById(R.id.song_more);
            checkboxSelect = itemView.findViewById(R.id.checkbox_select);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                Song song = songs.get(position);
                if (isSelectionMode) {
                    toggleSelection(song.getFsId());
                } else if (listener != null) {
                    listener.onSongClick(song, position);
                }
            });
            
            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onSongLongClick(songs.get(position), position);
                    return true;
                }
                return false;
            });

            songMore.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onPlayFromHereClick(songs.get(position), position);
                }
            });
        }

        public void bind(Song song) {
            songTitle.setText(song.getTitle());
            songPath.setText(song.getPath());
            
            if (isSelectionMode) {
                checkboxSelect.setVisibility(View.VISIBLE);
                songMore.setVisibility(View.GONE);
                checkboxSelect.setChecked(selectedSongIds.contains(song.getFsId()));
            } else {
                checkboxSelect.setVisibility(View.GONE);
                songMore.setVisibility(View.VISIBLE);
            }
            
            // 显示播放状态
            if (song.getId() == currentPlayingSongId) {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_primary));
            } else {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_text_primary));
            }
        }
    }
}