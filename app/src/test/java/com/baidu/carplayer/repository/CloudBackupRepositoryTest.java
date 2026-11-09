package com.baidu.carplayer.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.baidu.carplayer.testing.FakePanService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class CloudBackupRepositoryTest {
    private MockWebServer server;
    private FakePanService pan;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        pan = new FakePanService();
    }

    @After public void tearDown() throws Exception { server.shutdown(); }

    private CloudBackupRepository repo(String token) {
        String base = server.url("/").toString();
        return new CloudBackupRepository(pan.service(), () -> token, new OkHttpClient(), base + "pan/", base + "pcs/");
    }

    private static String form(RecordedRequest r) {
        return URLDecoder.decode(r.getBody().readUtf8(), StandardCharsets.UTF_8);
    }

    @Test public void uploadRunsThreeStepsWithOverwrite() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"errno\":0,\"uploadid\":\"U1\",\"return_type\":1}"));
        server.enqueue(new MockResponse().setBody("{\"errno\":0,\"md5\":\"x\"}"));
        server.enqueue(new MockResponse().setBody("{\"errno\":0,\"fs_id\":1}"));
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        repo("TKN").upload("/备份/a b.json", content);

        RecordedRequest pre = server.takeRequest();
        assertEquals("POST", pre.getMethod());
        assertEquals("/pan/xpan/file", pre.getRequestUrl().encodedPath());
        assertEquals("precreate", pre.getRequestUrl().queryParameter("method"));
        assertEquals("TKN", pre.getRequestUrl().queryParameter("access_token"));
        assertEquals("pan.baidu.com", pre.getHeader("User-Agent"));
        String preForm = form(pre);
        assertTrue(preForm, preForm.contains("path=/备份/a b.json"));
        assertTrue(preForm.contains("size=5"));
        assertTrue(preForm.contains("autoinit=1"));
        assertTrue(preForm.contains("rtype=3"));
        assertTrue(preForm.contains("block_list=[\"5d41402abc4b2a76b9719d911017c592\"]"));

        RecordedRequest up = server.takeRequest();
        assertEquals("/pcs/superfile2", up.getRequestUrl().encodedPath());
        assertEquals("upload", up.getRequestUrl().queryParameter("method"));
        assertEquals("tmpfile", up.getRequestUrl().queryParameter("type"));
        assertEquals("/备份/a b.json", up.getRequestUrl().queryParameter("path"));
        assertEquals("U1", up.getRequestUrl().queryParameter("uploadid"));
        assertEquals("0", up.getRequestUrl().queryParameter("partseq"));
        assertTrue(up.getHeader("Content-Type").startsWith("multipart/form-data"));
        String multipart = up.getBody().readUtf8();
        assertTrue(multipart.contains("name=\"file\""));
        assertTrue(multipart.contains("hello"));

        RecordedRequest create = server.takeRequest();
        assertEquals("create", create.getRequestUrl().queryParameter("method"));
        String createForm = form(create);
        assertTrue(createForm.contains("uploadid=U1"));
        assertTrue(createForm.contains("rtype=3"));
        assertTrue(createForm.contains("block_list=[\"5d41402abc4b2a76b9719d911017c592\"]"));
        assertEquals(3, server.getRequestCount());
    }

    @Test public void instantUploadStopsAfterPrecreate() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"errno\":0,\"return_type\":2}"));
        repo("t").upload("/a.json", new byte[]{1});
        assertEquals(1, server.getRequestCount());
    }

    @Test public void eachStepFailureIsReportedWithoutToken() {
        String[][] scripts = {
                {"{\"errno\":-7,\"errmsg\":\"bad path\"}"},
                {"{\"errno\":0,\"uploadid\":\"U\"}", "{\"errno\":31064,\"error_msg\":\"denied access_token=SECRET\"}"},
                {"{\"errno\":0,\"uploadid\":\"U\"}", "{\"errno\":0}", "{\"errno\":-8}"},
                {"not json"},
        };
        String[] expected = {"precreate 失败: bad path (-7)", "upload 失败", "create 失败: (-8)", "precreate 失败: HTTP 200"};
        for (int i = 0; i < scripts.length; i++) {
            for (String body : scripts[i]) server.enqueue(new MockResponse().setBody(body));
            try {
                repo("SECRET").upload("/a.json", new byte[]{1});
                fail("step " + i);
            } catch (CloudBackupRepository.CloudException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith(expected[i]));
                assertFalse(e.getMessage(), e.getMessage().contains("SECRET"));
            }
        }
    }

    @Test public void uploadIsNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(500));
        try {
            repo("t").upload("/a.json", new byte[]{1});
            fail();
        } catch (CloudBackupRepository.CloudException expected) {
            assertTrue(expected.getMessage().contains("HTTP 500"));
        }
        assertEquals(1, server.getRequestCount());
    }

    @Test public void requiresLogin() {
        try {
            repo(null).upload("/a.json", new byte[]{1});
            fail();
        } catch (CloudBackupRepository.CloudException e) {
            assertEquals(CloudFileRepository.NOT_LOGGED_IN, e.getMessage());
        }
        try {
            repo("").downloadText(1);
            fail();
        } catch (CloudBackupRepository.CloudException e) {
            assertEquals(CloudFileRepository.NOT_LOGGED_IN, e.getMessage());
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void downloadsTextThroughDlink() throws Exception {
        pan.dlink(42, server.url("/file/x.json?sign=1").toString());
        server.enqueue(new MockResponse().setBody("\uFEFF{\"playlists\":[]}"));
        assertEquals("{\"playlists\":[]}", repo("TK").downloadText(42));
        RecordedRequest r = server.takeRequest();
        assertEquals("TK", r.getRequestUrl().queryParameter("access_token"));
        assertEquals("1", r.getRequestUrl().queryParameter("sign"));
        assertEquals("pan.baidu.com", r.getHeader("User-Agent"));
    }

    @Test public void downloadFailures() {
        try {
            repo("t").downloadText(7);   // 无 dlink
            fail();
        } catch (CloudBackupRepository.CloudException e) {
            assertTrue(e.getMessage().startsWith("获取下载链接失败"));
        }
        pan.dlink(8, server.url("/f").toString());
        server.enqueue(new MockResponse().setResponseCode(403));
        try {
            repo("t").downloadText(8);
            fail();
        } catch (CloudBackupRepository.CloudException e) {
            assertEquals("下载失败: HTTP 403", e.getMessage());
        }
    }

    @Test public void joinPaths() {
        assertEquals("/a.json", CloudBackupRepository.join("/", "a.json"));
        assertEquals("/a.json", CloudBackupRepository.join("", "a.json"));
        assertEquals("/x/a.json", CloudBackupRepository.join("/x", "a.json"));
        assertEquals("/x/a.json", CloudBackupRepository.join("/x/", "a.json"));
    }
}
