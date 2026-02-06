package com.baidu.carplayer.model;

import java.util.List;

/**
 * 百度网盘文件列表响应
 */
public class FileListResponse {
    private int errno;
    private String request_id;
    private List<FileItem> list;

    public int getErrno() {
        return errno;
    }

    public void setErrno(int errno) {
        this.errno = errno;
    }

    public String getRequest_id() {
        return request_id;
    }

    public void setRequest_id(String request_id) {
        this.request_id = request_id;
    }

    public List<FileItem> getList() {
        return list;
    }

    public void setList(List<FileItem> list) {
        this.list = list;
    }
}