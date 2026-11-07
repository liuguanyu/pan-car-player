package com.baidu.carplayer.testing;

import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.network.BaiduPanService;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 内存网盘：只实现 getFileList，可注入目录内容与失败。 */
public final class FakePanService {
    private final Map<String, List<FileItem>> folders = new HashMap<>();
    private final Set<String> httpFailures = new HashSet<>();
    private final Set<String> ioFailures = new HashSet<>();
    private final Map<String, Integer> errnos = new HashMap<>();
    public final List<String> requests = new ArrayList<>();
    /** search 接口：目录 -> 文件 (server_filename -> fs_id) */
    private final Map<String, Map<String, Long>> searchIndex = new HashMap<>();
    /** filemetas 接口：fs_id -> dlink */
    private final Map<Long, String> dlinks = new HashMap<>();
    /** 每次请求实际使用的令牌 */
    public final List<String> tokens = new ArrayList<>();

    public FakePanService searchable(String dir, String fileName, long fsId) {
        searchIndex.computeIfAbsent(dir, k -> new HashMap<>()).put(fileName, fsId);
        return this;
    }

    public FakePanService dlink(long fsId, String url) {
        dlinks.put(fsId, url);
        return this;
    }

    public FakePanService folder(String path, FileItem... items) {
        folders.computeIfAbsent(path, k -> new ArrayList<>()).addAll(java.util.Arrays.asList(items));
        return this;
    }

    public FakePanService failHttp(String path) { httpFailures.add(path); return this; }
    public FakePanService failIo(String path) { ioFailures.add(path); return this; }
    public FakePanService errno(String path, int errno) { errnos.put(path, errno); return this; }

    public static FileItem audio(long fsId, String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new FileItem(name, path, 100L, false, fsId, "md5");
    }

    public static FileItem dir(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new FileItem(name, path, 0L, true, path.hashCode(), null);
    }

    public BaiduPanService service() {
        return (BaiduPanService) Proxy.newProxyInstance(BaiduPanService.class.getClassLoader(),
                new Class<?>[]{BaiduPanService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("search")) {
                        tokens.add((String) args[1]);
                        requests.add("search:" + args[3] + "/" + args[2]);
                        Map<String, Object> body = new HashMap<>();
                        body.put("errno", 0.0);
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (Map.Entry<String, Long> e : searchIndex
                                .getOrDefault((String) args[3], new HashMap<>()).entrySet()) {
                            Map<String, Object> item = new HashMap<>();
                            item.put("server_filename", e.getKey());
                            item.put("fs_id", (double) e.getValue());
                            list.add(item);
                        }
                        body.put("list", list);
                        return new StaticCall<>(body);
                    }
                    if (method.getName().equals("getFileDownloadLink")) {
                        tokens.add((String) args[1]);
                        long fsId = Long.parseLong(((String) args[2]).replaceAll("[\\[\\]]", ""));
                        requests.add("meta:" + fsId);
                        String json = dlinks.containsKey(fsId)
                                ? "{\"errno\":0,\"list\":[{\"fs_id\":" + fsId + ",\"dlink\":\"" + dlinks.get(fsId) + "\"}]}"
                                : "{\"errno\":-9,\"list\":[]}";
                        return new StaticCall<>(new com.google.gson.Gson()
                                .fromJson(json, com.baidu.carplayer.model.DownloadLinkResponse.class));
                    }
                    if (!method.getName().equals("getFileList")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    tokens.add((String) args[1]);
                    String dir = (String) args[2];
                    int start = (Integer) args[5];
                    int limit = (Integer) args[6];
                    requests.add(dir + "@" + start);
                    return new FakeCall(dir, start, limit);
                });
    }

    private static final class StaticCall<T> implements Call<T> {
        private final T body;

        StaticCall(T body) {
            this.body = body;
        }

        @Override public Response<T> execute() { return Response.success(body); }
        @Override public void enqueue(Callback<T> callback) { callback.onResponse(this, Response.success(body)); }
        @Override public boolean isExecuted() { return false; }
        @Override public void cancel() { }
        @Override public boolean isCanceled() { return false; }
        @Override public Call<T> clone() { return new StaticCall<>(body); }
        @Override public Request request() { return new Request.Builder().url("https://fake/").build(); }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }

    private final class FakeCall implements Call<FileListResponse> {
        private final String dir;
        private final int start;
        private final int limit;

        FakeCall(String dir, int start, int limit) {
            this.dir = dir;
            this.start = start;
            this.limit = limit;
        }

        @Override
        public Response<FileListResponse> execute() throws IOException {
            if (ioFailures.contains(dir)) throw new IOException("offline");
            if (httpFailures.contains(dir)) {
                return Response.error(500, ResponseBody.create("err", MediaType.get("text/plain")));
            }
            FileListResponse body = new FileListResponse();
            if (errnos.containsKey(dir)) {
                body.setErrno(errnos.get(dir));
                return Response.success(body);
            }
            List<FileItem> all = folders.getOrDefault(dir, new ArrayList<>());
            int end = Math.min(all.size(), start + limit);
            body.setList(start < end ? new ArrayList<>(all.subList(start, end)) : new ArrayList<>());
            return Response.success(body);
        }

        @Override public void enqueue(Callback<FileListResponse> callback) { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return false; }
        @Override public void cancel() { }
        @Override public boolean isCanceled() { return false; }
        @Override public Call<FileListResponse> clone() { return new FakeCall(dir, start, limit); }
        @Override public Request request() { return new Request.Builder().url("https://fake/" ).build(); }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }
}
