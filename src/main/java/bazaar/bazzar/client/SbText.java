package bazaar.bazzar.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SbText {
    static final double MAX_SANE_AMOUNT = 1e66;
    // the server writes quadrillion as Q, so that is what the mod reads and prints; Qa means the same.
    // Every suffix the server uses lives here, up to vigintillion, and the pay commands format from it too.
    static final String[] SUFFIXES = {
            "k", "m", "b", "t", "q", "qi", "sx", "sp", "oc", "no",
            "dc", "udc", "ddc", "tdc", "qdc", "qidc", "sxdc", "spdc", "ocdc", "nodc", "vg"
    };
    private static final String[] SUFFIX_LABELS = {
            "k", "M", "B", "T", "Q", "Qi", "Sx", "Sp", "Oc", "No",
            "Dc", "UDc", "DDc", "TDc", "QDc", "QiDc", "SxDc", "SpDc", "OcDc", "NoDc", "Vg"
    };
    static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*(qadc|qdc|qidc|sxdc|spdc|ocdc|nodc|udc|ddc|tdc|dc|qa|qi|sx|sp|oc|no|vg|k|m|b|t|q)?(?![a-z])");
    static final Pattern TIMER_AMOUNT_PATTERN = Pattern.compile("(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*\\(\\d{1,2}:\\d{2}\\)");
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(hours?|minutes?|seconds?)\\b");
    private static final Pattern SPRITE_PATTERN = Pattern.compile("\\[(?:[a-z0-9_.:/-]+@[a-z0-9_.:/-]+|[a-z0-9_.:-]+/[a-z0-9_./-]+)\\]");

    private SbText() {
    }

    static String stripSprites(String raw) {
        return raw == null ? null : SPRITE_PATTERN.matcher(raw).replaceAll("");
    }

    static String normalizeText(String raw) {
        String stripped = stripFormattingCodes(raw);
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            out.append(foldChar(stripped.charAt(i)));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    static String stripFormattingCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    static char foldChar(char c) {
        if (c >= 0xFF01 && c <= 0xFF5E) {
            return (char) (c - 0xFEE0);
        }
        return switch (c) {
            case 'ᴀ' -> 'a';
            case 'ʙ' -> 'b';
            case 'ᴄ' -> 'c';
            case 'ᴅ' -> 'd';
            case 'ᴇ' -> 'e';
            case 'ꜰ' -> 'f';
            case 'ɢ' -> 'g';
            case 'ʜ' -> 'h';
            case 'ɪ' -> 'i';
            case 'ᴊ' -> 'j';
            case 'ᴋ' -> 'k';
            case 'ʟ' -> 'l';
            case 'ᴍ' -> 'm';
            case 'ɴ' -> 'n';
            case 'ᴏ' -> 'o';
            case 'ᴘ' -> 'p';
            case 'ʀ' -> 'r';
            case 'ꜱ' -> 's';
            case 'ᴛ' -> 't';
            case 'ᴜ' -> 'u';
            case 'ᴠ' -> 'v';
            case 'ᴡ' -> 'w';
            case 'ʏ' -> 'y';
            case 'ᴢ' -> 'z';
            default -> c;
        };
    }

    static double firstAmount(String text) {
        if (text == null || text.isEmpty()) {
            return -1d;
        }
        Matcher matcher = AMOUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            int end = matcher.end();
            if (end < text.length() && text.charAt(end) == '%') {
                continue;
            }
            double value = parseNumber(matcher.group(1));
            if (value < 0d) {
                continue;
            }
            double multiplier = suffixMultiplier(matcher.group(2));
            if (multiplier < 0d) {
                continue;
            }
            value *= multiplier;
            if (value <= MAX_SANE_AMOUNT) {
                return value;
            }
        }
        return -1d;
    }

    static double amountAfterLabel(String normalizedLine, String label) {
        if (normalizedLine == null || label == null) {
            return -1d;
        }
        int idx = normalizedLine.indexOf(label);
        if (idx < 0) {
            return -1d;
        }
        return firstAmount(normalizedLine.substring(idx + label.length()));
    }

    static double amountAfterMarker(String raw, String marker) {
        if (raw == null || marker == null || marker.isEmpty()) {
            return -1d;
        }
        int idx = raw.indexOf(marker);
        if (idx < 0) {
            return -1d;
        }
        int close = raw.indexOf(']', idx);
        if (close < 0) {
            return -1d;
        }
        String rest = raw.substring(close + 1);
        int next = rest.indexOf('[');
        if (next >= 0) {
            rest = rest.substring(0, next);
        }
        return firstAmount(normalizeText(rest));
    }

    static double segmentAmount(String line, int index) {
        if (line == null || index < 0) {
            return -1d;
        }
        String[] parts = line.split("[|·•]");
        if (index >= parts.length) {
            return -1d;
        }
        return firstAmount(parts[index]);
    }

    static double timerAmount(String normalizedLine) {
        if (normalizedLine == null) {
            return -1d;
        }
        Matcher matcher = TIMER_AMOUNT_PATTERN.matcher(normalizedLine);
        if (!matcher.find()) {
            return -1d;
        }
        double value = parseNumber(matcher.group(1));
        return value >= 0d && value <= MAX_SANE_AMOUNT ? value : -1d;
    }

    static long parseDurationMs(String text) {
        if (text == null || text.isEmpty()) {
            return -1L;
        }
        Matcher matcher = DURATION_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        long total = 0L;
        boolean any = false;
        while (matcher.find()) {
            double value = parseNumber(matcher.group(1));
            if (value < 0d) {
                continue;
            }
            any = true;
            String unit = matcher.group(2);
            if (unit.startsWith("hour")) {
                total += (long) (value * 3_600_000d);
            } else if (unit.startsWith("minute")) {
                total += (long) (value * 60_000d);
            } else {
                total += (long) (value * 1_000d);
            }
        }
        return any ? total : -1L;
    }

    static double parseNumber(String digits) {
        if (digits == null || digits.isEmpty()) {
            return -1d;
        }
        try {
            return Double.parseDouble(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1d;
        }
    }

    static String formatCompact(double value) {
        double abs = Math.abs(value);
        if (abs < 10_000d) {
            if (value == Math.floor(value)) {
                return Long.toString((long) value);
            }
            return trimTrailing(String.format(Locale.ROOT, "%.2f", value));
        }
        double scaled = value;
        int tier = -1;
        // 999.995 and not 1000: dividing 1e33 down drifts to 999.99..., and rounding it would print "1000No"
        while (Math.abs(scaled) >= 999.995d && tier < SUFFIXES.length - 1) {
            scaled /= 1_000d;
            tier++;
        }
        return trimTrailing(String.format(Locale.ROOT, "%.2f", scaled)) + SUFFIX_LABELS[tier];
    }

    // 1e3 for k, 1e6 for m and so on; 1 for no suffix at all, -1 for something this doesn't know
    static double suffixMultiplier(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return 1d;
        }
        String wanted = switch (suffix.toLowerCase(Locale.ROOT)) {
            case "qa" -> "q";
            case "qadc" -> "qdc";
            default -> suffix.toLowerCase(Locale.ROOT);
        };
        for (int i = 0; i < SUFFIXES.length; i++) {
            if (SUFFIXES[i].equals(wanted)) {
                return Math.pow(1_000d, i + 1);
            }
        }
        return -1d;
    }

    // the same tiers as formatCompact, in the lowercase form the server's own pay commands take
    static String formatLower(double value) {
        double scaled = value;
        int tier = -1;
        // 999.995 and not 1000: dividing 1e33 down drifts to 999.99..., and rounding it would print "1000No"
        while (Math.abs(scaled) >= 999.995d && tier < SUFFIXES.length - 1) {
            scaled /= 1_000d;
            tier++;
        }
        return String.format(Locale.ROOT, "%.2f", scaled) + (tier < 0 ? "" : SUFFIXES[tier]);
    }

    private static String trimTrailing(String text) {
        while (text.endsWith("0") && text.contains(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
