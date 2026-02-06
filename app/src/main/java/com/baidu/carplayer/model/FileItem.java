package com.baidu.carplayer.model;

import com.google.gson.annotations.SerializedName;

/**
 * 文件项模型类
 */
public class FileItem {
    @SerializedName("server_filename")
    private String name;
    
    @SerializedName("path")
    private String path;
    
    @SerializedName("size")
    private long size;
    
    @SerializedName("isdir")
    private int isDirInt; // API returns 0 or 1
    
    private boolean isDirectory;
    
    @SerializedName("fs_id")
    private long fsId;
    
    @SerializedName("md5")
    private String md5;

    public FileItem() {
    }

    public FileItem(String name, String path, long size, boolean isDirectory, long fsId, String md5) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.isDirectory = isDirectory;
        this.isDirInt = isDirectory ? 1 : 0;
        this.fsId = fsId;
        this.md5 = md5;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public boolean isDirectory() {
        // Prefer isDirectory if manually set, otherwise derive from isDirInt
        return isDirectory || isDirInt == 1;
    }

    public void setDirectory(boolean directory) {
        isDirectory = directory;
        isDirInt = directory ? 1 : 0;
    }

    public long getFsId() {
        return fsId;
    }

    public void setFsId(long fsId) {
        this.fsId = fsId;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    /**
     * 判断是否为音频文件
     */
    public boolean isAudioFile() {
        if (isDirectory) return false;
        String lowerName = name.toLowerCase();
        return lowerName.endsWith(".mp3") || 
               lowerName.endsWith(".wav") || 
               lowerName.endsWith(".flac") || 
               lowerName.endsWith(".m4a") || 
               lowerName.endsWith(".aac") || 
               lowerName.endsWith(".ogg");
    }
    // 兼容旧代码的方法
    public int getIsdir() {
        return isDirInt;
    }

    public void setIsdir(int isdir) {
        this.isDirInt = isdir;
        this.isDirectory = (isdir == 1);
    }

    public String getServerFilename() {
        return name;
    }

    public void setServerFilename(String serverFilename) {
        this.name = serverFilename;
    }
}