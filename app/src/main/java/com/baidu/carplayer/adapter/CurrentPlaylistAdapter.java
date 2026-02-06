package com.baidu.carplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.R;
import com.baidu.carplayer.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前播放列表适配器
 * 支持搜索过滤和惰性加载（通过RecyclerView本身的特性支持）
 */
public class CurrentPlaylistAdapter extends RecyclerView.Adapter<CurrentPlaylistAdapter.ViewHolder> implements Filterable {

    private List<Song> originalSongs = new ArrayList<>();
    private List<Song> filteredSongs = new ArrayList<>();
    private OnSongClickListener listener;
    private long currentPlayingSongId = -1;

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
    }

    public void setOnSongClickListener(OnSongClickListener listener) {
        this.listener = listener;
    }

    public void setSongs(List<Song> songs) {
        this.originalSongs = songs != null ? new ArrayList<>(songs) : new ArrayList<>();
        this.filteredSongs = new ArrayList<>(this.originalSongs);
        notifyDataSetChanged();
    }
    
    public Song getItem(int position) {
        if (position >= 0 && position < filteredSongs.size()) {
            return filteredSongs.get(position);
        }
        return null;
    }
    
    // 获取歌曲在原始列表中的位置
    public int getOriginalPosition(Song song) {
        for (int i = 0; i < originalSongs.size(); i++) {
            if (originalSongs.get(i).getId() == song.getId()) {
                return i;
            }
        }
        return -1;
    }

    public void setCurrentPlayingSongId(long songId) {
        if (this.currentPlayingSongId != songId) {
            this.currentPlayingSongId = songId;
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Song song = filteredSongs.get(position);
        holder.bind(song);
        
        // 设置斑马纹背景
        int backgroundColor = (position % 2 == 0)
            ? holder.itemView.getContext().getResources().getColor(R.color.list_item_background, null)
            : holder.itemView.getContext().getResources().getColor(R.color.list_item_background_alt, null);
        
        // 高亮当前播放歌曲
        if (song.getId() == currentPlayingSongId) {
             backgroundColor = holder.itemView.getContext().getResources().getColor(R.color.car_surface_highlight, null);
        }
        
        holder.itemView.setBackgroundColor(backgroundColor);
    }

    @Override
    public int getItemCount() {
        return filteredSongs.size();
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String charString = constraint.toString();
                List<Song> resultList;
                
                if (charString.isEmpty()) {
                    resultList = originalSongs;
                } else {
                    List<Song> filteredList = new ArrayList<>();
                    String filterPattern = charString.toLowerCase().trim();
                    
                    for (Song song : originalSongs) {
                        if (song.getTitle().toLowerCase().contains(filterPattern) || 
                            (song.getArtist() != null && song.getArtist().toLowerCase().contains(filterPattern))) {
                            filteredList.add(song);
                        }
                    }
                    resultList = filteredList;
                }

                FilterResults filterResults = new FilterResults();
                filterResults.values = resultList;
                filterResults.count = resultList.size();
                return filterResults;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredSongs = (ArrayList<Song>) results.values;
                notifyDataSetChanged();
            }
        };
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView songTitle;
        private ImageButton songMore;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            songTitle = itemView.findViewById(R.id.song_title);
            songMore = itemView.findViewById(R.id.song_more);
            
            // 隐藏更多按钮，在这里不需要
            songMore.setVisibility(View.GONE);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onSongClick(filteredSongs.get(position), position);
                }
            });
        }

        public void bind(Song song) {
            String displayText = song.getTitle();
            if (song.getArtist() != null && !song.getArtist().isEmpty() && !song.getArtist().equals("<unknown>")) {
                displayText += " - " + song.getArtist();
            }
            songTitle.setText(displayText);
            
            // 显示播放状态
            if (song.getId() == currentPlayingSongId) {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_accent));
                songTitle.setTextScaleX(1.05f);
            } else {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_text_primary));
                songTitle.setTextScaleX(1.0f);
            }
        }
    }
}