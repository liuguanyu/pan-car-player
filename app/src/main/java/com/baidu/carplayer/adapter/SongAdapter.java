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
import com.baidu.carplayer.adapter.rows.SongRow;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.selection.SelectionModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    private List<Song> songs = new ArrayList<>();
    private OnSongClickListener listener;
    private long currentPlayingSongId = -1;
    
    // 多选状态由页面持有的 SelectionModel 管理；Adapter 只负责渲染和转发点击。
    private SelectionModel<Song> selection = new SelectionModel<>(Song::getFsId);
    private OnSelectionChangeListener selectionChangeListener;
    private final SelectionModel.Listener selectionListener = model -> {
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChanged(model.size());
        }
        notifyDataSetChanged();
    };

    public SongAdapter() {
        selection.addListener(selectionListener);
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return songs.get(position).getFsId();
    }

    /** 当前位置的不可变行模型。 */
    public SongRow rowAt(int position) {
        Song song = songs.get(position);
        return SongRow.forLibrary(song, selection.isSelectionMode(), selection.isSelected(song), currentPlayingSongId);
    }

    /** 由页面注入共享的选择状态（例如跨重建保留）。 */
    public void setSelectionModel(SelectionModel<Song> model) {
        selection.removeListener(selectionListener);
        selection = model;
        selection.setItems(songs);
        selection.addListener(selectionListener);
        notifyDataSetChanged();
    }

    public SelectionModel<Song> getSelectionModel() {
        return selection;
    }

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
        selection.setSelectionMode(selectionMode);
    }
    
    public boolean isSelectionMode() {
        return selection.isSelectionMode();
    }
    
    public void toggleSelection(long songId) {
        selection.toggleId(songId);
    }
    
    public void selectAll() {
        selection.selectAll(null);
    }
    
    public List<Song> getSelectedSongs() {
        return selection.getSelectedItems();
    }

    public void setSongs(List<Song> songs) {
        this.songs = songs != null ? new ArrayList<>(songs) : new ArrayList<>();
        selection.setItems(this.songs);
        notifyDataSetChanged();
    }

    public void addSong(Song song) {
        songs.add(song);
        selection.setItems(songs);
        notifyItemInserted(songs.size() - 1);
    }

    public void removeSong(int position) {
        if (position >= 0 && position < songs.size()) {
            songs.remove(position);
            notifyItemRemoved(position);
            selection.setItems(songs);
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
        selection.setItems(songs);
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
        holder.bind(rowAt(position));
        
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
                if (selection.isSelectionMode()) {
                    selection.toggle(song);
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

        public void bind(SongRow row) {
            songTitle.setText(row.title);
            songPath.setText(row.subtitle);
            
            if (row.selectionMode) {
                checkboxSelect.setVisibility(View.VISIBLE);
                songMore.setVisibility(View.GONE);
                checkboxSelect.setChecked(row.selected);
            } else {
                checkboxSelect.setVisibility(View.GONE);
                songMore.setVisibility(View.VISIBLE);
            }
            
            // 显示播放状态
            if (row.playing) {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_primary));
            } else {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_text_primary));
            }
        }
    }
}