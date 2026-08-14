package com.panmusic.carplayer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun `parse standard LRC`() {
        val r = LrcParser.parse("[ti:测试]\n[ar:歌手]\n[al:专辑]\n[00:00.00]第一行\n[00:05.50]第二行\n[00:10.00]第三行")
        assertEquals("测试", r.title); assertEquals("歌手", r.artist); assertEquals(3, r.lines.size)
        assertEquals(0L, r.lines[0].time); assertEquals(5500L, r.lines[1].time); assertEquals(10000L, r.lines[2].time)
    }
    @Test fun `parse without tags`() {
        val r = LrcParser.parse("[00:01.00]Hello\n[00:03.00]World")
        assertEquals("", r.title); assertEquals(2, r.lines.size)
    }
    @Test fun `parse empty`() { assertEquals(0, LrcParser.parse("").lines.size) }
    @Test fun `parse multiple time tags`() {
        assertEquals(2, LrcParser.parse("[00:01.00][00:02.00]Repeated").lines.size)
    }
    @Test fun `findCurrentLine correct`() {
        val info = LrcParser.parse("[00:00.00]L0\n[00:05.00]L1\n[00:10.00]L2\n[00:15.00]L3")
        assertEquals(-1, LrcParser.findCurrentLine(info.lines, -100))
        assertEquals(0, LrcParser.findCurrentLine(info.lines, 0))
        assertEquals(1, LrcParser.findCurrentLine(info.lines, 5000))
        assertEquals(3, LrcParser.findCurrentLine(info.lines, 20000))
    }
    @Test fun `findCurrentLine empty`() { assertEquals(-1, LrcParser.findCurrentLine(emptyList(), 0)) }
    @Test fun `parse offset tag`() {
        assertEquals(1500L, LrcParser.parse("[offset:500]\n[00:01.00]Delayed").lines[0].time)
    }
}
