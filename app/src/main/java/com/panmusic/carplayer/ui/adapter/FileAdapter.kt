package com.panmusic.carplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.panmusic.carplayer.R
import com.panmusic.carplayer.model.FileItem
import com.panmusic.carplayer.util.toFileSizeString

/**
 * 文件浏览器适配器 — 车机大屏优化，文件夹可选中可导航
 *
 * 交互逻辑：
 * - 点击文件名区域 → 文件夹：进入子目录；文件：切换选中
 * - 点击导航箭头 → 文件夹：进入子目录
 * - 点击 checkbox → 切换选中状态
 * - 长按 → 切换选中状态
 */
class FileAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit,
    private val onNavigateClick: (FileItem) -> Unit
) : ListAdapter<FileItem, FileAdapter.ViewHolder>(FileDiffCallback()) {

    private var selectedFsIds: Set<Long> = emptySet()

    fun setSelectedItems(ids: Set<Long>) {
        selectedFsIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), selectedFsIds.contains(getItem(position).fsId))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.iv_icon)
        private val nameView: TextView = itemView.findViewById(R.id.tv_file_name)
        private val infoView: TextView = itemView.findViewById(R.id.tv_file_info)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_select)
        private val navArrow: ImageView = itemView.findViewById(R.id.iv_navigate)

        fun bind(item: FileItem, isSelected: Boolean) {
            val isDir = item.isDir == 1

            // 图标：文件夹 vs 文件
            if (isDir) {
                iconView.setImageResource(R.drawable.ic_folder)
                iconView.visibility = View.VISIBLE
            } else {
                val ext = item.serverFilename.substringAfterLast('.', "").lowercase()
                if (ext in setOf("mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "ape")) {
                    iconView.setImageResource(R.drawable.ic_music_note)
                    iconView.visibility = View.VISIBLE
                } else {
                    iconView.visibility = View.GONE
                }
            }

            // 文件名
            nameView.text = item.serverFilename

            // 文件大小（仅文件显示）
            if (isDir) {
                infoView.visibility = View.GONE
            } else {
                infoView.text = item.size.toFileSizeString()
                infoView.visibility = View.VISIBLE
            }

            // 导航箭头（仅文件夹显示）
            navArrow.visibility = if (isDir) View.VISIBLE else View.GONE

            // 选中状态
            checkBox.isChecked = isSelected

            // 点击文件名区域 → 文件夹：导航；文件：切换选中
            nameView.setOnClickListener {
                if (isDir) {
                    onNavigateClick(item)
                } else {
                    onItemClick(item)
                }
            }

            // 点击图标区域 → 文件夹：导航；文件：切换选中
            iconView.setOnClickListener {
                if (isDir) {
                    onNavigateClick(item)
                } else {
                    onItemClick(item)
                }
            }

            // 点击导航箭头 → 进入子目录
            navArrow.setOnClickListener {
                onNavigateClick(item)
            }

            // 点击卡片主体空白区 → 文件夹导航，文件切换选中
            itemView.setOnClickListener {
                if (isDir) {
                    onNavigateClick(item)
                } else {
                    onItemClick(item)
                }
            }

            // 长按 → 切换选中（文件和文件夹均支持）
            itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}

class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
        oldItem.fsId == newItem.fsId

    override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
        oldItem == newItem
}
