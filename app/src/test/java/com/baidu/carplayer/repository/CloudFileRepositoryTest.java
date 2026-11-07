package com.baidu.carplayer.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.testing.FakePanService;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CloudFileRepositoryTest {
    @Test
    public void everyRequestUsesLatestToken() throws Exception {
        FakePanService pan = new FakePanService().folder("/m", FakePanService.audio(1, "/m/a.mp3"));
        AtomicReference<String> token = new AtomicReference<>("old");
        CloudFileRepository repo = new CloudFileRepository(pan.service(), token::get);
        repo.listFolder("/m");
        token.set("new"); // 模拟令牌刷新
        repo.scanRoots(Collections.singletonList("/m"));
        assertEquals("old", pan.tokens.get(0));
        assertEquals("new", pan.tokens.get(pan.tokens.size() - 1));
    }

    @Test
    public void missingTokenFailsBeforeNetwork() {
        FakePanService pan = new FakePanService();
        CloudFileRepository repo = new CloudFileRepository(pan.service(), () -> null);
        assertFalse(repo.hasToken());
        try {
            repo.listFolder("/m");
            fail();
        } catch (CloudMusicScanner.ScanException e) {
            assertEquals(CloudFileRepository.NOT_LOGGED_IN, e.getMessage());
        }
        assertTrue(pan.requests.isEmpty());
    }

    @Test
    public void listFolderIsNotRecursiveAndPaginates() throws Exception {
        FileItem[] many = new FileItem[CloudMusicScanner.PAGE_SIZE + 2];
        for (int i = 0; i < many.length; i++) many[i] = FakePanService.audio(i + 1, "/m/" + i + ".mp3");
        FakePanService pan = new FakePanService().folder("/m", many)
                .folder("/m", FakePanService.dir("/m/sub"))
                .folder("/m/sub", FakePanService.audio(9999, "/m/sub/x.mp3"));
        List<FileItem> items = new CloudFileRepository(pan.service(), () -> "t").listFolder("/m");
        assertEquals(many.length + 1, items.size());
        assertFalse(pan.requests.contains("/m/sub@0"));
    }
}
