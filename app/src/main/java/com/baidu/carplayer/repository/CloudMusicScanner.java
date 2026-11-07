package com.baidu.carplayer.repository;

import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.network.BaiduPanService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit2.Response;

/**
 * 网盘音乐扫描：同步、分页、递归地收集音频文件，生成完整快照。
 * 任一目录或分页失败都会抛出 {@link ScanException}，调用方据此保留原有数据，
 * 绝不能用不完整的快照覆盖本地列表。必须在后台线程调用。
 */
public final class CloudMusicScanner {
    public static final int PAGE_SIZE = 1000;

    /** 扫描未完成（网络、HTTP 或业务错误）。 */
    public static final class ScanException extends Exception {
        public ScanException(String message) {
            super(message);
        }

        public ScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final BaiduPanService service;

    public CloudMusicScanner(BaiduPanService service) {
        this.service = service;
    }

    /**
     * 扫描选中的条目：文件夹递归展开，音频文件直接收录。
     * 结果按 fsId 去重并保持发现顺序。
     */
    public List<FileItem> scan(String accessToken, Collection<FileItem> selection) throws ScanException {
        Map<Long, FileItem> result = new LinkedHashMap<>();
        Set<String> scanned = new LinkedHashSet<>();
        for (FileItem item : selection) {
            if (item.getIsdir() == 1) {
                scanFolder(accessToken, item.getPath(), result, scanned);
            } else if (item.isAudioFile()) {
                result.put(item.getFsId(), item);
            }
        }
        return new ArrayList<>(result.values());
    }

    /** 扫描若干根目录。 */
    public List<FileItem> scanRoots(String accessToken, Collection<String> roots) throws ScanException {
        Map<Long, FileItem> result = new LinkedHashMap<>();
        Set<String> scanned = new LinkedHashSet<>();
        for (String root : roots) {
            scanFolder(accessToken, root, result, scanned);
        }
        return new ArrayList<>(result.values());
    }

    /** 列出单个目录的直接子项（不递归），分页拉全。 */
    public List<FileItem> listFolder(String accessToken, String path) throws ScanException {
        List<FileItem> items = new ArrayList<>();
        int start = 0;
        while (true) {
            List<FileItem> list = fetchPage(accessToken, path, start).getList();
            if (list == null || list.isEmpty()) {
                return items;
            }
            items.addAll(list);
            if (list.size() < PAGE_SIZE) {
                return items;
            }
            start += PAGE_SIZE;
        }
    }

    private void scanFolder(String accessToken, String path, Map<Long, FileItem> result,
                            Set<String> scanned) throws ScanException {
        if (path == null || !scanned.add(path)) {
            return;
        }
        int start = 0;
        while (true) {
            FileListResponse body = fetchPage(accessToken, path, start);
            List<FileItem> list = body.getList();
            if (list == null || list.isEmpty()) {
                return;
            }
            for (FileItem item : list) {
                if (item.getIsdir() == 1) {
                    scanFolder(accessToken, item.getPath(), result, scanned);
                } else if (item.isAudioFile()) {
                    result.put(item.getFsId(), item);
                }
            }
            if (list.size() < PAGE_SIZE) {
                return;
            }
            start += PAGE_SIZE;
        }
    }

    private FileListResponse fetchPage(String accessToken, String path, int start) throws ScanException {
        Response<FileListResponse> response;
        try {
            response = service.getFileList("list", accessToken, path, "name", 0, start, PAGE_SIZE, 1, 0, 0)
                    .execute();
        } catch (IOException e) {
            throw new ScanException("扫描目录失败: " + path + "（网络错误）", e);
        }
        if (!response.isSuccessful() || response.body() == null) {
            throw new ScanException("扫描目录失败: " + path + " (HTTP " + response.code() + ")");
        }
        FileListResponse body = response.body();
        if (body.getErrno() != 0) {
            throw new ScanException("扫描目录失败: " + path + " (errno " + body.getErrno() + ")");
        }
        return body;
    }
}
