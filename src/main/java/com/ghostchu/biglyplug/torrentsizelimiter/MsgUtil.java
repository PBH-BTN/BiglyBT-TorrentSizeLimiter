package com.ghostchu.biglyplug.torrentsizelimiter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.text.CharacterIterator;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.text.StringCharacterIterator;

public final class MsgUtil {
    private static final DecimalFormat df = new DecimalFormat("0.00%");
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat stf = new SimpleDateFormat("HH:mm:ss");

    public static String escapeSql(String sql){
        if(sql == null) return null;
        return sql.replace("'", "''");
    }

    public static String humanReadableByteCountBin(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + " B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.1f %ciB", value / 1024.0, ci.current());
    }

    public static String humanReadableByteCountSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current());
    }

    public static DecimalFormat getPercentageFormatter() {
        return df;
    }

    public static SimpleDateFormat getDateFormatter() {
        return sdf;
    }

    public static SimpleDateFormat getTimeFormatter() {
        return stf;
    }

    /**
     * Replace args in raw to args
     *
     * @param raw  text
     * @param args args
     * @return filled text
     */
    @NotNull
    public static String fillArgs(@Nullable String raw, @Nullable String... args) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int start = 0;
        int argIndex = 0;

        while (start < raw.length()) {
            int placeholderIndex = raw.indexOf("{}", start);
            if (placeholderIndex == -1) {
                result.append(raw.substring(start));
                break;
            }
            result.append(raw, start, placeholderIndex);
            if (args != null && argIndex < args.length) {
                result.append(args[argIndex] != null ? args[argIndex] : "");
                argIndex++;
            } else {
                result.append("{}");
            }
            start = placeholderIndex + 2;
        }
        return result.toString();
    }
}
