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
import com.baidu.carplayer.adapter.rows.SongRow;
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
        void onSongClick(long songId);
    }

    public CurrentPlaylistAdapter() {
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return filteredSongs.get(position).getFsId();
    }

    /** 当前位置的不可变行模型。 */
    public SongRow rowAt(int position) {
        return SongRow.forQueue(filteredSongs.get(position), currentPlayingSongId);
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
    
    public void setCurrentPlayingSongId(long songId) {
        if (this.currentPlayingSongId != songId) {
            long oldId = this.currentPlayingSongId;
            this.currentPlayingSongId = songId;
            
            // 刷新受影响的项
            if (filteredSongs != null) {
                for (int i = 0; i < filteredSongs.size(); i++) {
                    if (filteredSongs.get(i).getId() == oldId || filteredSongs.get(i).getId() == songId) {
                        notifyItemChanged(i);
                    }
                }
            }
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
        SongRow row = rowAt(position);
        holder.bind(row);
        
        // 设置斑马纹背景
        int backgroundColor = (position % 2 == 0)
            ? holder.itemView.getContext().getResources().getColor(R.color.list_item_background, null)
            : holder.itemView.getContext().getResources().getColor(R.color.list_item_background_alt, null);
        
        // 高亮当前播放歌曲
        if (row.playing) {
             backgroundColor = holder.itemView.getContext().getResources().getColor(R.color.car_surface_highlight, null);
        }
        
        holder.itemView.setBackgroundColor(backgroundColor);
    }

    @Override
    public int getItemCount() {
        return filteredSongs.size();
    }

    /**
     * 纯函数：按标题或歌手过滤（忽略大小写和首尾空白）。
     * 点击回调使用歌曲 ID，因此过滤后的位置不会影响播放哪一首。
     */
    public static List<Song> filterSongs(List<Song> source, CharSequence constraint) {
        String pattern = constraint == null ? "" : constraint.toString().toLowerCase().trim();
        if (pattern.isEmpty()) {
            return new ArrayList<>(source);
        }
        List<Song> result = new ArrayList<>();
        for (Song song : source) {
            String title = song.getTitle();
            // "<unknown>" 是百度接口的未知歌手占位符，不作为可搜索内容。
            String artist = song.getArtist();
            boolean hasArtist = artist != null && !artist.isEmpty() && !artist.equals("<unknown>");
            if ((title != null && title.toLowerCase().contains(pattern))
                    || (hasArtist && artist.toLowerCase().contains(pattern))) {
                result.add(song);
            }
        }
        return result;
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                List<Song> resultList = filterSongs(originalSongs, constraint);

                FilterResults filterResults = new FilterResults();
                filterResults.values = resultList;
                filterResults.count = resultList.size();
                return filterResults;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredSongs = results.values == null
                        ? new ArrayList<>()
                        : new ArrayList<>((List<Song>) results.values);
                notifyDataSetChanged();
            }
        };
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView songTitle;
        private TextView songPath;
        private ImageButton songMore;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            songTitle = itemView.findViewById(R.id.song_title);
            songPath = itemView.findViewById(R.id.song_path);
            songMore = itemView.findViewById(R.id.song_more);
            
            // 隐藏更多按钮，在这里不需要
            songMore.setVisibility(View.GONE);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onSongClick(filteredSongs.get(position).getId());
                }
            });
        }

        public void bind(SongRow row) {
            songTitle.setText(row.title);
            
            // 设置歌曲路径
            if (songPath != null) {
                songPath.setText(row.subtitle);
            }
            
            // 显示播放状态
            if (row.playing) {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_accent));
                songTitle.setTextScaleX(1.05f);
                if (songPath != null) {
                    songPath.setTextColor(itemView.getContext().getColor(R.color.car_accent));
                }
            } else {
                songTitle.setTextColor(itemView.getContext().getColor(R.color.car_text_primary));
                songTitle.setTextScaleX(1.0f);
                if (songPath != null) {
                    songPath.setTextColor(itemView.getContext().getColor(R.color.car_text_secondary));
                }
            }
        }
    }
}