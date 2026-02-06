package com.baidu.carplayer.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC歌词解析器
 */
public class LrcParser {
    private static final Pattern PATTERN_LINE = Pattern.compile("\\[(\\d+):(\\d+\\.\\d+)](.*)");
    private static final Pattern PATTERN_TIME = Pattern.compile("\\[(\\d+):(\\d+\\.\\d+)]");

    public static class LrcEntry implements Comparable<LrcEntry> {
        private long time;
        private String text;

        public LrcEntry(long time, String text) {
            this.time = time;
            this.text = text;
        }

        public long getTime() {
            return time;
        }

        public String getText() {
            return text;
        }

        @Override
        public int compareTo(LrcEntry other) {
            return Long.compare(this.time, other.time);
        }
    }

    /**
     * 解析LRC歌词内容
     * @param lrcContent LRC歌词内容
     * @return 歌词条目列表
     */
    public static List<LrcEntry> parseLrc(String lrcContent) {
        if (lrcContent == null || lrcContent.isEmpty()) {
            return null;
        }

        List<LrcEntry> entries = new ArrayList<>();
        String[] lines = lrcContent.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher matcher = PATTERN_LINE.matcher(line);
            if (matcher.matches()) {
                try {
                    int minute = Integer.parseInt(matcher.group(1));
                    float second = Float.parseFloat(matcher.group(2));
                    String text = matcher.group(3);

                    long time = (long) (minute * 60 * 1000 + second * 1000);
                    entries.add(new LrcEntry(time, text));
                } catch (NumberFormatException e) {
                    // 忽略解析错误的行
                }
            }
        }

        // 按时间排序
        Collections.sort(entries);
        return entries;
    }

    /**
     * 从多个时间标签中解析歌词
     * @param lrcContent LRC歌词内容
     * @return 歌词条目列表
     */
    public static List<LrcEntry> parseLrcWithMultipleTimeTags(String lrcContent) {
        if (lrcContent == null || lrcContent.isEmpty()) {
            return null;
        }

        List<LrcEntry> entries = new ArrayList<>();
        String[] lines = lrcContent.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 查找所有时间标签
            Matcher timeMatcher = PATTERN_TIME.matcher(line);
            List<Long> times = new ArrayList<>();
            
            while (timeMatcher.find()) {
                try {
                    int minute = Integer.parseInt(timeMatcher.group(1));
                    float second = Float.parseFloat(timeMatcher.group(2));
                    long time = (long) (minute * 60 * 1000 + second * 1000);
                    times.add(time);
                } catch (NumberFormatException e) {
                    // 忽略解析错误的时间标签
                }
            }

            // 获取歌词文本（最后一个时间标签后的内容）
            int lastBracketIndex = line.lastIndexOf(']');
            if (lastBracketIndex != -1 && lastBracketIndex < line.length() - 1) {
                String text = line.substring(lastBracketIndex + 1).trim();
                
                // 为每个时间标签创建一个歌词条目
                for (Long time : times) {
                    entries.add(new LrcEntry(time, text));
                }
            }
        }

        // 按时间排序
        Collections.sort(entries);
        return entries;
    }
}