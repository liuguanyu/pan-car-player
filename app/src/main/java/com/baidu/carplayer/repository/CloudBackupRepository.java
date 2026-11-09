package com.baidu.carplayer.repository;

import androidx.annotation.Nullable;

import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.network.ApiConstants;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.LogRedactor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Call;

/**
 * 网盘上的小文本文件读写（播放列表备份）。同步 API，调用方负责后台线程。
 *
 * <p>上传参考 pan-player-cmd：precreate → superfile2(upload, partseq=0) → create，
 * 单分片、rtype=3 覆盖同名文件。上传是非幂等 POST，使用不带重试拦截器的客户端。
 */
public final class CloudBackupRepository {
    public static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;
    private static final String USER_AGENT = "pan.baidu.com";
    private static final MediaType OCTET = MediaType.get("application/octet-stream");

    public static final class CloudException extends Exception {
        public CloudException(String message) { super(LogRedactor.redact(message)); }
    }

    private final BaiduPanService service;
    private final BaiduDownloadUrlRepository.TokenProvider tokenProvider;
    private final OkHttpClient http;
    private final String panBase;
    private final String pcsBase;

    public CloudBackupRepository(BaiduPanService service, BaiduDownloadUrlRepository.TokenProvider tokenProvider) {
        this(service, tokenProvider, defaultClient(), ApiConstants.PAN_API_BASE_URL, ApiConstants.PCS_BASE_URL);
    }

    /** 测试可注入客户端与基址。 */
    public CloudBackupRepository(BaiduPanService service, BaiduDownloadUrlRepository.TokenProvider tokenProvider,
                                 OkHttpClient http, String panBase, String pcsBase) {
        this.service = service;
        this.tokenProvider = tokenProvider;
        this.http = http;
        this.panBase = panBase;
        this.pcsBase = pcsBase;
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(ApiConstants.CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(ApiConstants.READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(ApiConstants.WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean hasToken() {
        String t = tokenProvider.getAccessToken();
        return t != null && !t.isEmpty();
    }

    /** 下载网盘文件为 UTF-8 文本（去 BOM），超过 10MB 拒绝。 */
    public String downloadText(long fsId) throws CloudException {
        String token = requireToken();
        String dlink;
        try {
            Call<DownloadLinkResponse> call = service.getFileDownloadLink("filemetas", token, "[" + fsId + "]", 1);
            retrofit2.Response<DownloadLinkResponse> meta = call.execute();
            DownloadLinkResponse body = meta.isSuccessful() ? meta.body() : null;
            if (body == null || body.getErrno() != 0 || body.getList() == null || body.getList().isEmpty()
                    || body.getList().get(0).getDlink() == null) {
                throw new CloudException("获取下载链接失败" + (body != null ? " (" + body.getErrno() + ")" : ""));
            }
            dlink = BaiduDownloadUrlRepository.appendAccessToken(body.getList().get(0).getDlink(), token);
        } catch (IOException e) {
            throw new CloudException("获取下载链接失败: " + e.getMessage());
        }
        Request request = new Request.Builder().url(dlink).header("User-Agent", USER_AGENT).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new CloudException("下载失败: HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new CloudException("下载失败: 内容为空");
            byte[] bytes = readLimited(body.byteStream(), MAX_DOWNLOAD_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            throw new CloudException("下载失败: " + e.getMessage());
        }
    }

    /** 上传到 remotePath（绝对路径），同名覆盖。 */
    public void upload(String remotePath, byte[] content) throws CloudException {
        String token = requireToken();
        String size = String.valueOf(content.length);
        String blockList = "[\"" + md5Hex(content) + "\"]";

        JsonObject pre = postForm("precreate", token, new FormBody.Builder()
                .add("path", remotePath).add("size", size).add("isdir", "0")
                .add("autoinit", "1").add("block_list", blockList).add("rtype", "3").build());
        if (intField(pre, "return_type") == 2) return;   // 秒传
        String uploadId = stringField(pre, "uploadid");
        if (uploadId == null || uploadId.isEmpty()) throw new CloudException("precreate 未返回 uploadid");

        HttpUrl uploadUrl = HttpUrl.get(pcsBase + "superfile2").newBuilder()
                .addQueryParameter("method", "upload")
                .addQueryParameter("access_token", token)
                .addQueryParameter("type", "tmpfile")
                .addQueryParameter("path", remotePath)
                .addQueryParameter("uploadid", uploadId)
                .addQueryParameter("partseq", "0")
                .build();
        RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName(remotePath), RequestBody.create(content, OCTET))
                .build();
        execute("upload", new Request.Builder().url(uploadUrl).post(multipart));

        postForm("create", token, new FormBody.Builder()
                .add("path", remotePath).add("size", size).add("isdir", "0")
                .add("uploadid", uploadId).add("block_list", blockList).add("rtype", "3").build());
    }

    private JsonObject postForm(String method, String token, FormBody form) throws CloudException {
        HttpUrl url = HttpUrl.get(panBase + ApiConstants.ENDPOINT_FILE).newBuilder()
                .addQueryParameter("method", method)
                .addQueryParameter("access_token", token)
                .build();
        return execute(method, new Request.Builder().url(url).post(form));
    }

    private JsonObject execute(String step, Request.Builder builder) throws CloudException {
        Request request = builder.header("User-Agent", USER_AGENT).build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            JsonObject json;
            try {
                JsonElement e = JsonParser.parseString(text);
                json = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (RuntimeException parse) {
                json = null;
            }
            if (json == null) {
                throw new CloudException(step + " 失败: HTTP " + response.code());
            }
            int errno = intField(json, "errno");
            if (errno != 0) {
                String msg = stringField(json, "errmsg");
                if (msg == null) msg = stringField(json, "error_msg");
                throw new CloudException(step + " 失败: " + (msg != null ? msg + " " : "") + "(" + errno + ")");
            }
            return json;
        } catch (IOException e) {
            throw new CloudException(step + " 失败: " + e.getMessage());
        }
    }

    private String requireToken() throws CloudException {
        String token = tokenProvider.getAccessToken();
        if (token == null || token.isEmpty()) throw new CloudException(CloudFileRepository.NOT_LOGGED_IN);
        return token;
    }

    static byte[] readLimited(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) throw new IOException("文件过大");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String md5Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int intField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Nullable
    private static String stringField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 拼接网盘目录与文件名。 */
    public static String join(String dir, String name) {
        if (dir == null || dir.isEmpty() || dir.equals("/")) return "/" + name;
        return (dir.endsWith("/") ? dir : dir + "/") + name;
    }
}
