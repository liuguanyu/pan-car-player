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
import com.baidu.carplayer.adapter.rows.FileRow;
import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.selection.SelectionModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 文件列表适配器
 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private List<FileItem> files = new ArrayList<>();
    // 选择状态与列表位置无关，按 fsId 记录；Adapter 只渲染。
    private final SelectionModel<FileItem> selection = new SelectionModel<>(FileItem::getFsId);
    private static final SelectionModel.Filter<FileItem> SELECTABLE =
            file -> file.getIsdir() == 1 || file.isAudioFile();
    private static final SelectionModel.Filter<FileItem> FILES = file -> file.getIsdir() == 0;
    private static final SelectionModel.Filter<FileItem> FOLDERS = file -> file.getIsdir() == 1;
    private OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(FileItem file);
        void onFileCheckChanged(FileItem file, boolean isChecked);
    }

    public FileAdapter() {
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return files.get(position).getFsId();
    }

    /** 当前位置的不可变行模型。 */
    public FileRow rowAt(int position) {
        FileItem file = files.get(position);
        return FileRow.of(file, selection.isSelected(file));
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setFiles(List<FileItem> files) {
        this.files = files != null ? new ArrayList<>(files) : new ArrayList<>();
        selection.setItems(this.files);
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
        holder.bind(rowAt(position), position);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    public void selectAllAudioFiles(boolean select) {
        // 选择所有文件夹和音频文件
        if (select) {
            selection.selectAll(SELECTABLE);
        } else {
            selection.deselectAll(SELECTABLE);
        }
        notifyDataSetChanged();
    }

    public boolean areAllAudioFilesSelected() {
        return selection.areAllSelected(SELECTABLE);
    }

    public void clearSelection() {
        selection.clear();
        notifyDataSetChanged();
    }

    public List<FileItem> getSelectedFiles() {
        return selection.getSelectedItems();
    }

    public int getSelectedFileCount() {
        return selection.count(FILES);
    }
    
    public int getSelectedFolderCount() {
        return selection.count(FOLDERS);
    }

    /**
     * 按文件名排序
     * 使用 Collator 实现中文友好的排序，保证顺序稳定
     * @param ascending true为正序，false为倒序
     */
    public void sortByName(boolean ascending) {
        java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINESE);
        collator.setStrength(java.text.Collator.PRIMARY);
        Collections.sort(files, new Comparator<FileItem>() {
            @Override
            public int compare(FileItem f1, FileItem f2) {
                // 文件夹始终排在前面
                if (f1.getIsdir() != f2.getIsdir()) {
                    return f1.getIsdir() == 1 ? -1 : 1;
                }
                
                // 同为文件夹或同为文件时，按文件名排序（中文友好）
                int result = collator.compare(f1.getServerFilename(), f2.getServerFilename());
                if (result == 0) {
                    result = f1.getServerFilename().compareTo(f2.getServerFilename());
                }
                if (result == 0) {
                    result = f1.getPath().compareTo(f2.getPath());
                }
                if (result == 0) {
                    result = Long.compare(f1.getFsId(), f2.getFsId());
                }
                return ascending ? result : -result;
            }
        });
        selection.setItems(files);
        notifyDataSetChanged();
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
                    if (rowAt(position).clickAction() == FileRow.Action.OPEN) {
                        listener.onFileClick(file);
                    } else {
                        toggleSelection(file);
                    }
                }
            });
            
            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    FileItem file = files.get(position);
                    if (rowAt(position).longClickAction() == FileRow.Action.TOGGLE) {
                        toggleSelection(file);
                        return true;
                    }
                }
                return false;
            });
        }
        
        private void toggleSelection(FileItem file) {
            selection.toggle(file);
            boolean newStatus = selection.isSelected(file);
            int adapterPosition = getAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                notifyItemChanged(adapterPosition);
            }
            if (listener != null) {
                listener.onFileCheckChanged(file, newStatus);
            }
        }

        public void bind(FileRow row, int position) {
            fileName.setText(row.name);
            
            // 设置斑马纹背景
            itemView.setBackgroundColor(position % 2 == 0 ? 0xFF1E1E1E : 0xFF2A2A2A);
            
            selectedIcon.setVisibility(row.selected ? View.VISIBLE : View.GONE);

            switch (row.kind) {
                case FOLDER:
                    fileIcon.setImageResource(R.drawable.ic_folder_yellow);
                    break;
                case AUDIO:
                    fileIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
                    break;
                default:
                    fileIcon.setImageResource(android.R.drawable.ic_menu_agenda);
                    break;
            }
            if (row.sizeText == null) {
                fileSize.setVisibility(View.GONE);
            } else {
                fileSize.setVisibility(View.VISIBLE);
                fileSize.setText(row.sizeText);
            }
            
            // 不再使用CheckBox，逻辑已移至点击事件和selectedIcon
            fileCheckbox.setVisibility(View.GONE);
        }
    }
}