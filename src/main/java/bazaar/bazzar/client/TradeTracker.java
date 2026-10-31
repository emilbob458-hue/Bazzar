package bazaar.bazzar.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TradeTracker {
    static final long RECENT_TRADE_MS = 120_000L;
    static final long ACTIVE_TRADE_MAX_MS = 600_000L;
    private static final Pattern OUTGOING_ACCEPTED =
            Pattern.compile("^\\[Trade\\]\\s+Your trade request to (\\w{1,16}) was accepted");
    private static final Pattern INCOMING_REQUEST =
            Pattern.compile("^\\[Trade\\]\\s+The player (\\w{1,16}) wants to trade with you");
    private static final Pattern INCOMING_ACCEPTED =
            Pattern.compile("^\\[Trade\\]\\s+You have accepted the trade request");
    private static final Pattern FINISHED =
            Pattern.compile("^\\[Trade\\]\\s+The trade was finished");
    private static final Pattern ENDED_WITHOUT_TRADE =
            Pattern.compile("^\\[Trade\\]\\s+The trade was cancel+ed");

    private static final Map<String, Long> RECENT = new HashMap<>();
    private static String lastRequester;
    private static String activePartner;
    private static long activeSinceMs;

    private TradeTracker() {
    }

    static void onChatLine(String message) {
        onChatLine(message, System.currentTimeMillis());
    }

    static synchronized void onChatLine(String message, long now) {
        if (message == null) {
            return;
        }
        String clean = message.trim();
        if (!clean.startsWith("[Trade]")) {
            return;
        }
        Matcher matcher = OUTGOING_ACCEPTED.matcher(clean);
        if (matcher.find()) {
            startTrade(matcher.group(1), now);
            return;
        }
        matcher = INCOMING_REQUEST.matcher(clean);
        if (matcher.find()) {
            lastRequester = matcher.group(1);
            return;
        }
        if (INCOMING_ACCEPTED.matcher(clean).find()) {
            if (lastRequester != null) {
                startTrade(lastRequester, now);
            }
            return;
        }
        if (FINISHED.matcher(clean).find()) {
            if (activePartner != null) {
                RECENT.put(key(activePartner), now);
            }
            activePartner = null;
            return;
        }
        if (ENDED_WITHOUT_TRADE.matcher(clean).find()) {
            activePartner = null;
        }
    }

    static boolean recentlyTraded(String name) {
        return recentlyTraded(name, System.currentTimeMillis());
    }

    static synchronized boolean recentlyTraded(String name, long now) {
        if (name == null) {
            return false;
        }
        String key = key(name);
        if (activePartner != null && now - activeSinceMs < ACTIVE_TRADE_MAX_MS && key(activePartner).equals(key)) {
            return true;
        }
        RECENT.values().removeIf(at -> now - at >= RECENT_TRADE_MS);
        return RECENT.containsKey(key);
    }

    static synchronized void reset() {
        RECENT.clear();
        lastRequester = null;
        activePartner = null;
        activeSinceMs = 0L;
    }

    private static void startTrade(String partner, long now) {
        activePartner = partner;
        activeSinceMs = now;
        lastRequester = null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
