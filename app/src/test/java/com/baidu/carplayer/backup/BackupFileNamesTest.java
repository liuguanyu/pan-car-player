package com.baidu.carplayer.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;

public class BackupFileNamesTest {
    @Test public void defaultNameUsesLocalDate() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 9, 23, 59, 0);
        assertEquals("playlists-backup-2026-11-09.json", BackupFileNames.defaultName(c.getTime()));
    }

    @Test public void normalizes() {
        assertEquals("car-lists.json", BackupFileNames.normalize("  car-lists "));
        assertEquals("a.JSON", BackupFileNames.normalize("a.JSON"));
        assertEquals("备份.json", BackupFileNames.normalize("备份"));
        assertNull(BackupFileNames.normalize(null));
        assertNull(BackupFileNames.normalize("   "));
        assertNull(BackupFileNames.normalize("a/b"));
        assertNull(BackupFileNames.normalize("a\\b"));
        assertNull(BackupFileNames.normalize(".."));
        assertNull(BackupFileNames.normalize("a\nb"));
    }
}
