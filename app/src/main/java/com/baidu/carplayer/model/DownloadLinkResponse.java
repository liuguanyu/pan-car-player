package com.baidu.carplayer.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * 百度网盘文件下载链接响应
 */
public class DownloadLinkResponse {
    @SerializedName("errno")
    private int errno;
    
    @SerializedName("errmsg")
    private String errmsg;
    
    @SerializedName("request_id")
    private String requestId;
    
    @SerializedName("list")
    private List<FileInfo> list;
    
    public int getErrno() {
        return errno;
    }
    
    public void setErrno(int errno) {
        this.errno = errno;
    }
    
    public String getErrmsg() {
        return errmsg;
    }
    
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public List<FileInfo> getList() {
        return list;
    }
    
    public void setList(List<FileInfo> list) {
        this.list = list;
    }
    
    /**
     * 文件信息内部类
     */
    public static class FileInfo {
        @SerializedName("fs_id")
        private long fsId;
        
        @SerializedName("path")
        private String path;
        
        @SerializedName("filename")
        private String filename;
        
        @SerializedName("size")
        private long size;
        
        @SerializedName("dlink")
        private String dlink;
        
        public long getFsId() {
            return fsId;
        }
        
        public void setFsId(long fsId) {
            this.fsId = fsId;
        }
        
        public String getPath() {
            return path;
        }
        
        public void setPath(String path) {
            this.path = path;
        }
        
        public String getFilename() {
            return filename;
        }
        
        public void setFilename(String filename) {
            this.filename = filename;
        }
        
        public long getSize() {
            return size;
        }
        
        public void setSize(long size) {
            this.size = size;
        }
        
        public String getDlink() {
            return dlink;
        }
        
        public void setDlink(String dlink) {
            this.dlink = dlink;
        }
    }
}