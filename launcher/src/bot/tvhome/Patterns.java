package bot.tvhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Loads RLE and Life 1.05 pattern files into a Life universe, centred on the origin. */
final class Patterns {

    private Patterns() {
    }

    static void load(Life life, Reader source) throws IOException {
        StringBuilder text = new StringBuilder();
        BufferedReader in = new BufferedReader(source);
        for (String line; (line = in.readLine()) != null; ) text.append(line).append('\n');
        String s = text.toString();
        java.util.List<long[]> cells = s.startsWith("#Life 1.05") || s.contains("\n#P") || s.startsWith("#P")
                ? life105(s) : rle(s);
        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE;
        for (long[] c : cells) {
            minX = Math.min(minX, c[0]);
            minY = Math.min(minY, c[1]);
            maxX = Math.max(maxX, c[0]);
            maxY = Math.max(maxY, c[1]);
        }
        long cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        life.clear();
        for (long[] c : cells) life.set(c[0] - cx, c[1] - cy);
    }

    static void load(Life life, String rle) {
        try {
            load(life, new java.io.StringReader(rle));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.List<long[]> rle(String s) {
        java.util.List<long[]> cells = new java.util.ArrayList<>();
        long x = 0, y = 0, run = 0;
        boolean header = true;
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (header && line.startsWith("x")) {
                header = false;
                continue;
            }
            header = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c >= '0' && c <= '9') {
                    run = run * 10 + (c - '0');
                    continue;
                }
                long n = run == 0 ? 1 : run;
                run = 0;
                if (c == 'b' || c == '.') {
                    x += n;
                } else if (c == '$') {
                    y += n;
                    x = 0;
                } else if (c == '!') {
                    return cells;
                } else if (!Character.isWhitespace(c)) {
                    // 'o' and any multi-state letter count as alive.
                    for (long k = 0; k < n; k++) cells.add(new long[]{x + k, y});
                    x += n;
                }
            }
        }
        return cells;
    }

    private static java.util.List<long[]> life105(String s) {
        java.util.List<long[]> cells = new java.util.ArrayList<>();
        long bx = 0, y = 0;
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.startsWith("#P")) {
                // "#P x y" places the next block; a bare "#P" (as Golly writes it) means 0 0.
                String[] p = line.substring(2).trim().split("\\s+");
                bx = p.length >= 2 ? Long.parseLong(p[0]) : 0;
                y = p.length >= 2 ? Long.parseLong(p[1]) : 0;
                continue;
            }
            if (line.startsWith("#") || line.isEmpty()) continue;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '*') cells.add(new long[]{bx + i, y});
            }
            y++;
        }
        return cells;
    }
}
