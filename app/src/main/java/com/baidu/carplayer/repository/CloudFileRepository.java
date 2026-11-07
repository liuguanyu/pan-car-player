package com.baidu.carplayer.repository;

import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.network.BaiduPanService;

import java.util.Collection;
import java.util.List;

/**
 * 网盘文件访问边界（同步 API，调用方负责放到后台线程）。
 * 每次调用都从 {@link BaiduDownloadUrlRepository.TokenProvider} 取最新令牌，
 * 页面不再缓存 access_token，令牌刷新后自动生效。
 */
public final class CloudFileRepository {
    static final String NOT_LOGGED_IN = "未登录，请先登录";

    private final BaiduPanService service;
    private final BaiduDownloadUrlRepository.TokenProvider tokenProvider;

    public CloudFileRepository(BaiduPanService service, BaiduDownloadUrlRepository.TokenProvider tokenProvider) {
        this.service = service;
        this.tokenProvider = tokenProvider;
    }

    public boolean hasToken() {
        String token = tokenProvider.getAccessToken();
        return token != null && !token.isEmpty();
    }

    /** 列出单个目录（分页拉全）；任一页失败即抛出。 */
    public List<FileItem> listFolder(String path) throws CloudMusicScanner.ScanException {
        return scanner().listFolder(requireToken(), path);
    }

    /** 展开选中条目中的全部音频文件。 */
    public List<FileItem> scanSelection(Collection<FileItem> selection) throws CloudMusicScanner.ScanException {
        return scanner().scan(requireToken(), selection);
    }

    /** 递归扫描若干根目录。 */
    public List<FileItem> scanRoots(Collection<String> roots) throws CloudMusicScanner.ScanException {
        return scanner().scanRoots(requireToken(), roots);
    }

    private CloudMusicScanner scanner() {
        return new CloudMusicScanner(service);
    }

    private String requireToken() throws CloudMusicScanner.ScanException {
        String token = tokenProvider.getAccessToken();
        if (token == null || token.isEmpty()) {
            throw new CloudMusicScanner.ScanException(NOT_LOGGED_IN);
        }
        return token;
    }
}
