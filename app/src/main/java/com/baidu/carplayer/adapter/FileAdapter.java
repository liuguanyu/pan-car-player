package com.baidu.carplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.R;
import com.baidu.carplayer.model.FileItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件列表适配器
 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private List<FileItem> files = new ArrayList<>();
    private Map<Long, Boolean> selectedFiles = new HashMap<>();
    private OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(FileItem file);
        void onFileCheckChanged(FileItem file, boolean isChecked);
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setFiles(List<FileItem> files) {
        this.files = files;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileItem file = files.get(position);
        holder.bind(file);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    public void selectAllAudioFiles(boolean select) {
        for (FileItem file : files) {
            // 选择所有文件夹和音频文件
            if (file.getIsdir() == 1 || file.isAudioFile()) {
                selectedFiles.put(file.getFsId(), select);
            }
        }
        notifyDataSetChanged();
    }

    public boolean areAllAudioFilesSelected() {
        for (FileItem file : files) {
            // 检查所有文件夹和音频文件是否都被选中
            if (file.getIsdir() == 1 || file.isAudioFile()) {
                if (!selectedFiles.containsKey(file.getFsId()) || !selectedFiles.get(file.getFsId())) {
                    return false;
                }
            }
        }
        return true;
    }

    public void clearSelection() {
        selectedFiles.clear();
        notifyDataSetChanged();
    }

    public List<FileItem> getSelectedFiles() {
        List<FileItem> selected = new ArrayList<>();
        for (FileItem file : files) {
            if (selectedFiles.containsKey(file.getFsId()) && selectedFiles.get(file.getFsId())) {
                selected.add(file);
            }
        }
        return selected;
    }

    public int getSelectedFileCount() {
        int count = 0;
        for (FileItem file : files) {
            if (file.getIsdir() == 0 && selectedFiles.containsKey(file.getFsId()) && selectedFiles.get(file.getFsId())) {
                count++;
            }
        }
        return count;
    }
    
    public int getSelectedFolderCount() {
        int count = 0;
        for (FileItem file : files) {
            if (file.getIsdir() == 1 && selectedFiles.containsKey(file.getFsId()) && selectedFiles.get(file.getFsId())) {
                count++;
            }
        }
        return count;
    }

    class FileViewHolder extends RecyclerView.ViewHolder {
        private CheckBox fileCheckbox;
        private ImageView fileIcon;
        private ImageView selectedIcon;
        private TextView fileName;
        private TextView fileSize;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            fileCheckbox = itemView.findViewById(R.id.file_checkbox);
            fileIcon = itemView.findViewById(R.id.file_icon);
            selectedIcon = itemView.findViewById(R.id.selected_icon);
            fileName = itemView.findViewById(R.id.file_name);
            fileSize = itemView.findViewById(R.id.file_size);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    FileItem file = files.get(position);
                    if (file.getIsdir() == 1) {
                        // 文件夹点击进入
                        listener.onFileClick(file);
                    } else {
                        // 文件点击切换选中状态
                        toggleSelection(file);
                    }
                }
            });
            
            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    FileItem file = files.get(position);
                    if (file.getIsdir() == 1) {
                        // 文件夹长按切换选中状态
                        toggleSelection(file);
                        return true;
                    }
                }
                return false;
            });
        }
        
        private void toggleSelection(FileItem file) {
            boolean isSelected = selectedFiles.containsKey(file.getFsId()) && selectedFiles.get(file.getFsId());
            boolean newStatus = !isSelected;
            selectedFiles.put(file.getFsId(), newStatus);
            notifyItemChanged(getAdapterPosition());
            if (listener != null) {
                listener.onFileCheckChanged(file, newStatus);
            }
        }

        public void bind(FileItem file) {
            fileName.setText(file.getServerFilename());
            
            boolean isSelected = selectedFiles.containsKey(file.getFsId()) && selectedFiles.get(file.getFsId());
            
            // 设置选中状态图标
            if (isSelected) {
                selectedIcon.setVisibility(View.VISIBLE);
            } else {
                selectedIcon.setVisibility(View.GONE);
            }

            // 设置图标
            if (file.getIsdir() == 1) {
                // 目录图标 - 使用黄色文件夹
                fileIcon.setImageResource(R.drawable.ic_folder_yellow);
                // 文件夹不显示大小信息
                fileSize.setVisibility(View.GONE);
            } else if (file.isAudioFile()) {
                // 音频文件图标
                fileIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
                // 显示文件大小
                fileSize.setVisibility(View.VISIBLE);
                fileSize.setText(formatFileSize(file.getSize()));
            } else {
                // 其他文件图标
                fileIcon.setImageResource(android.R.drawable.ic_menu_agenda);
                // 显示文件大小
                fileSize.setVisibility(View.VISIBLE);
                fileSize.setText(formatFileSize(file.getSize()));
            }
            
            // 不再使用CheckBox，逻辑已移至点击事件和selectedIcon
            fileCheckbox.setVisibility(View.GONE);
        }

        private String formatFileSize(long size) {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format("%.1f KB", size / 1024.0);
            } else if (size < 1024 * 1024 * 1024) {
                return String.format("%.1f MB", size / (1024.0 * 1024));
            } else {
                return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
            }
        }
    }
}