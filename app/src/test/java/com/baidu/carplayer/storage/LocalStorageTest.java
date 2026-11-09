package com.baidu.carplayer.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.baidu.carplayer.browser.BrowserMode;
import com.baidu.carplayer.browser.FileListing;
import com.baidu.carplayer.model.FileItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class LocalStorageTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static List<String> names(List<FileItem> items) {
        List<String> out = new ArrayList<>();
        for (FileItem i : items) out.add(i.getServerFilename());
        return out;
    }

    @Test public void listsFilteredAndSorted() throws Exception {
        File root = tmp.getRoot();
        new File(root, "b-dir").mkdir();
        new File(root, "A-dir").mkdir();
        new File(root, "z.JSON").createNewFile();
        new File(root, "a.json").createNewFile();
        new File(root, "song.mp3").createNewFile();
        new File(root, ".hidden.json").createNewFile();
        LocalDirectoryLister lister = new LocalDirectoryLister(root);
        List<FileItem> all = lister.list(root);

        assertEquals(List.of("A-dir", "b-dir", "a.json", "z.JSON"),
                names(FileListing.filterAndSort(all, BrowserMode.PICK_JSON, true)));
        assertEquals(List.of("A-dir", "b-dir"),
                names(FileListing.filterAndSort(all, BrowserMode.PICK_DIR, true)));
        assertEquals(List.of("A-dir", "b-dir", "song.mp3"),
                names(FileListing.filterAndSort(all, BrowserMode.ADD_SONGS, true)));
        // 倒序时文件夹仍在前
        assertEquals(List.of("b-dir", "A-dir", "z.JSON", "a.json"),
                names(FileListing.filterAndSort(all, BrowserMode.PICK_JSON, false)));
    }

    @Test public void lockedToRoot() throws Exception {
        File outer = tmp.newFolder("outer");
        File root = new File(outer, "root");
        File child = new File(root, "child");
        assertTrue(child.mkdirs());
        new File(outer, "rootless").mkdir();   // 前缀相同的兄弟目录不算根内
        LocalDirectoryLister lister = new LocalDirectoryLister(root);

        assertEquals(root.getCanonicalFile(), lister.parentOf(child));
        assertNull(lister.parentOf(root));
        assertFalse(lister.isWithinRoot(new File(outer, "rootless")));
        assertFalse(lister.isWithinRoot(new File(child, "../../")));
        try {
            lister.list(outer);
            fail();
        } catch (IOException e) {
            assertEquals(LocalDirectoryLister.UNREADABLE, e.getMessage());
        }
        try {
            lister.list(new File(root, "missing"));
            fail();
        } catch (IOException e) {
            assertEquals(LocalDirectoryLister.UNREADABLE, e.getMessage());
        }
    }

    @Test public void atomicWriteOverwritesAndReads() throws Exception {
        File dir = tmp.getRoot();
        File f = LocalBackupWriter.write(dir, "a.json", "old".getBytes(StandardCharsets.UTF_8));
        LocalBackupWriter.write(dir, "a.json", "\uFEFF新内容".getBytes(StandardCharsets.UTF_8));
        assertEquals("新内容", LocalBackupWriter.readText(f, 1024));
        assertArrayEquals(new String[]{"a.json"}, dir.list());   // 无残留临时文件
        try {
            LocalBackupWriter.readText(f, 2);
            fail();
        } catch (IOException e) {
            assertEquals("文件过大", e.getMessage());
        }
    }

    @Test public void failedWriteLeavesNoTempFile() throws Exception {
        File dir = tmp.getRoot();
        File blocker = new File(dir, "x.json");
        assertTrue(blocker.mkdir());                      // 目标是目录 → rename 失败
        new File(blocker, "keep").createNewFile();
        try {
            LocalBackupWriter.write(dir, "x.json", new byte[]{1});
            fail();
        } catch (IOException expected) {
            // ok
        }
        assertFalse(new File(dir, ".x.json.tmp").exists());
        try {
            LocalBackupWriter.write(new File(dir, "nope"), "a.json", new byte[]{1});
            fail();
        } catch (IOException e) {
            assertEquals("目录不存在", e.getMessage());
        }
        assertTrue(Files.isDirectory(blocker.toPath()));
    }

    @Test public void adbCommand() {
        assertEquals("adb shell appops set --user 10 com.baidu.carplayer MANAGE_EXTERNAL_STORAGE allow",
                StorageAccess.adbGrantCommand(10, "com.baidu.carplayer"));
    }

    @Test public void browserModeParsing() {
        assertEquals(BrowserMode.ADD_SONGS, BrowserMode.fromName(null));
        assertEquals(BrowserMode.ADD_SONGS, BrowserMode.fromName("bogus"));
        assertEquals(BrowserMode.PICK_DIR, BrowserMode.fromName("PICK_DIR"));
        assertFalse(BrowserMode.ADD_SONGS.isPicker());
        assertTrue(BrowserMode.PICK_JSON.isPicker());
    }
}
