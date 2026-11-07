package com.baidu.carplayer.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsSessionTest {
    @Test
    public void sameSongDoesNotRequestAgain() {
        LyricsSession session = new LyricsSession();
        assertNotNull(session.onSongChanged(1L));
        assertNull(session.onSongChanged(1L));
    }

    @Test
    public void lateResultForPreviousSongIsRejected() {
        LyricsSession session = new LyricsSession();
        LyricsSession.Ticket first = session.onSongChanged(1L);
        LyricsSession.Ticket second = session.onSongChanged(2L);
        assertFalse(session.accepts(first));
        assertTrue(session.accepts(second));
    }

    @Test
    public void switchingBackToSongCreatesFreshTicket() {
        LyricsSession session = new LyricsSession();
        LyricsSession.Ticket a1 = session.onSongChanged(1L);
        session.onSongChanged(2L);
        LyricsSession.Ticket a2 = session.onSongChanged(1L);
        assertFalse(session.accepts(a1));
        assertTrue(session.accepts(a2));
    }

    @Test
    public void invalidateAllowsReloadAndRejectsPending() {
        LyricsSession session = new LyricsSession();
        LyricsSession.Ticket pending = session.onSongChanged(1L);
        session.invalidate();
        assertFalse(session.accepts(pending));
        assertNotNull(session.onSongChanged(1L));
    }

    @Test
    public void noSongClearsWithoutTicket() {
        LyricsSession session = new LyricsSession();
        LyricsSession.Ticket pending = session.onSongChanged(1L);
        assertNull(session.onSongChanged(-1L));
        assertFalse(session.accepts(pending));
    }

    @Test
    public void closedSessionRejectsEverything() {
        LyricsSession session = new LyricsSession();
        LyricsSession.Ticket pending = session.onSongChanged(1L);
        session.close();
        assertFalse(session.accepts(pending));
        assertNull(session.onSongChanged(2L));
    }
}
