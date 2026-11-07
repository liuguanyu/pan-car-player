package com.baidu.carplayer.repository;

import static com.baidu.carplayer.testing.FakePanService.audio;
import static com.baidu.carplayer.testing.FakePanService.dir;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.testing.FakePanService;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CloudMusicScannerTest {
    @Test
    public void recursesFoldersFiltersAudioAndDeduplicates() throws Exception {
        FakePanService pan = new FakePanService()
                .folder("/m", audio(1, "/m/a.mp3"), new FileItem("x.txt", "/m/x.txt", 1, false, 9, null), dir("/m/sub"))
                .folder("/m/sub", audio(2, "/m/sub/b.flac"));
        List<FileItem> files = new CloudMusicScanner(pan.service())
                .scan("t", Arrays.asList(dir("/m"), audio(1, "/m/a.mp3")));
        assertEquals(2, files.size());
    }

    @Test
    public void followsPagination() throws Exception {
        FakePanService pan = new FakePanService();
        FileItem[] items = new FileItem[CloudMusicScanner.PAGE_SIZE + 5];
        for (int i = 0; i < items.length; i++) items[i] = audio(i + 1, "/big/" + i + ".mp3");
        pan.folder("/big", items);
        List<FileItem> files = new CloudMusicScanner(pan.service())
                .scanRoots("t", Collections.singletonList("/big"));
        assertEquals(items.length, files.size());
        assertEquals(Arrays.asList("/big@0", "/big@" + CloudMusicScanner.PAGE_SIZE), pan.requests);
    }

    @Test
    public void anyFailedFolderAbortsWholeScan() {
        assertScanFails(new FakePanService().folder("/m", dir("/m/sub")).failHttp("/m/sub"));
        assertScanFails(new FakePanService().folder("/m", dir("/m/sub")).failIo("/m/sub"));
        assertScanFails(new FakePanService().folder("/m", dir("/m/sub")).errno("/m/sub", -7));
    }

    private static void assertScanFails(FakePanService pan) {
        try {
            new CloudMusicScanner(pan.service()).scan("t", new ArrayList<>(Collections.singletonList(dir("/m"))));
            fail("expected ScanException");
        } catch (CloudMusicScanner.ScanException e) {
            assertTrue(e.getMessage().contains("/m/sub"));
        }
    }
}
