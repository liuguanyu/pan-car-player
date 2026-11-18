package com.baidu.carplayer.adapter;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.R;
import com.baidu.carplayer.model.Playlist;

import java.util.ArrayList;
import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {

    private List<Playlist> playlists = new ArrayList<>();
    private OnPlaylistClickListener listener;
    private OnPlaylistDragStartListener dragStartListener;
    private int selectedPosition = -1;

    public interface OnPlaylistClickListener {
        void onPlaylistClick(Playlist playlist, int position);
        void onPlaylistMoreClick(Playlist playlist, int position);
        void onPlaylistLongClick(Playlist playlist, int position);
    }

    /** 由宿主（ItemTouchHelper）接管拖动手势。 */
    public interface OnPlaylistDragStartListener {
        void onPlaylistDragStart(RecyclerView.ViewHolder holder);
    }

    public void setOnPlaylistClickListener(OnPlaylistClickListener listener) {
        this.listener = listener;
    }

    public void setOnPlaylistDragStartListener(OnPlaylistDragStartListener dragStartListener) {
        this.dragStartListener = dragStartListener;
    }

    /** 当前展示顺序的快照，供拖动结束后持久化。 */
    public List<Playlist> getPlaylists() {
        return new ArrayList<>(playlists);
    }

    /** 拖动过程中移动一行（只做视图层面的移动，松手后再写库）。 */
    public void moveItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= playlists.size() || to >= playlists.size()) {
            return;
        }
        playlists.add(to, playlists.remove(from));
        notifyItemMoved(from, to);
    }

    /** 顺序变化完成后重绘可见行（背景斑马纹、选中高亮依赖 position）。 */
    public void notifyOrderChanged() {
        if (!playlists.isEmpty()) {
            notifyItemRangeChanged(0, playlists.size());
        }
    }

    public void setPlaylists(List<Playlist> playlists) {
        this.playlists = playlists != null ? playlists : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void addPlaylist(Playlist playlist) {
        playlists.add(playlist);
        notifyItemInserted(playlists.size() - 1);
    }

    public void removePlaylist(int position) {
        if (position >= 0 && position < playlists.size()) {
            playlists.remove(position);
            notifyItemRemoved(position);
            if (selectedPosition == position) {
                selectedPosition = -1;
            } else if (selectedPosition > position) {
                selectedPosition--;
            }
        }
    }

    public void setSelectedPosition(int position) {
        int previousPosition = selectedPosition;
        selectedPosition = position;
        if (previousPosition >= 0) {
            notifyItemChanged(previousPosition);
        }
        if (position >= 0) {
            notifyItemChanged(position);
        }
    }

    @NonNull
    @Override
    public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
        Playlist playlist = playlists.get(position);
        holder.bind(playlist, position);
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    class PlaylistViewHolder extends RecyclerView.ViewHolder {
        private TextView playlistIcon;
        private TextView playlistName;
        private TextView playlistCount;
        private ImageButton playlistMore;
        private ImageView playlistDragHandle;

        public PlaylistViewHolder(@NonNull View itemView) {
            super(itemView);
            playlistIcon = itemView.findViewById(R.id.playlist_icon);
            playlistName = itemView.findViewById(R.id.playlist_name);
            playlistCount = itemView.findViewById(R.id.playlist_count);
            playlistMore = itemView.findViewById(R.id.playlist_more);
            playlistDragHandle = itemView.findViewById(R.id.playlist_drag_handle);

            // 只有从拖动手柄按下才开始拖动，长按整行仍然是打开列表菜单。
            playlistDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && dragStartListener != null) {
                    dragStartListener.onPlaylistDragStart(this);
                }
                return false;
            });

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onPlaylistClick(playlists.get(position), position);
                }
            });
            
            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onPlaylistLongClick(playlists.get(position), position);
                    return true;
                }
                return false;
            });

            playlistMore.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onPlaylistMoreClick(playlists.get(position), position);
                }
            });
        }

        public void bind(Playlist playlist, int position) {
            playlistName.setText(playlist.getName());
            playlistCount.setText(String.format("%d 首歌曲", playlist.getSongCount()));

            // 设置播放列表名称的首字作为图标
            String name = playlist.getName();
            if (name != null && !name.isEmpty()) {
                String firstChar = name.substring(0, 1);
                playlistIcon.setText(firstChar);
            } else {
                playlistIcon.setText("?");
            }

            // 高亮选中的播放列表
            if (position == selectedPosition) {
                itemView.setBackgroundColor(itemView.getContext().getColor(R.color.list_item_selected));
            } else {
                // 斑马纹效果
                if (position % 2 == 0) {
                    itemView.setBackgroundColor(0xFF1E1E1E); // 深色
                } else {
                    itemView.setBackgroundColor(0xFF2A2A2A); // 稍浅色
                }
            }
        }
    }
}