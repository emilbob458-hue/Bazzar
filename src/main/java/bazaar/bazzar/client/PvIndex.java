package bazaar.bazzar.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PvIndex {
    static final class Entry {
        String name;
        String id;
        int count;
    }

    static final class Page {
        List<Entry> items = new ArrayList<>();
        long scannedMs;
    }

    static final class Data {
        int season;
        Map<String, Page> pages = new HashMap<>();
    }

    record SearchHit(String pageKey, String name, int count, long scannedMs) {
    }

    private static final int SEASON = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data cached;
    private static String cachedAccount = "";

    private PvIndex() {
    }

    static Data forAccount(String account) {
        if (account == null || account.isEmpty()) {
            return freshData();
        }
        if (cached != null && account.equals(cachedAccount)) {
            return cached;
        }
        cached = loadFromDisk(account);
        cachedAccount = account;
        return cached;
    }

    static void put(String account, String pageKey, List<Entry> items) {
        if (account == null || account.isEmpty() || pageKey == null || pageKey.isEmpty()) {
            return;
        }
        Data data = forAccount(account);
        Page page = new Page();
        page.items = new ArrayList<>(items);
        page.scannedMs = System.currentTimeMillis();
        data.pages.put(pageKey, page);
        try {
            Files.createDirectories(pathFor(account).getParent());
            Files.writeString(pathFor(account), GSON.toJson(data));
        } catch (IOException ignored) {
        }
    }

    static List<SearchHit> search(String account, String query) {
        Data data = forAccount(account);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, SearchHit> aggregated = new LinkedHashMap<>();
        for (Map.Entry<String, Page> pageEntry : data.pages.entrySet()) {
            Page page = pageEntry.getValue();
            if (page == null || page.items == null) {
                continue;
            }
            for (Entry item : page.items) {
                if (item == null || item.name == null) {
                    continue;
                }
                String lowerName = item.name.toLowerCase(Locale.ROOT);
                if (!needle.isEmpty() && !lowerName.contains(needle)
                        && (item.id == null || !item.id.toLowerCase(Locale.ROOT).contains(needle))) {
                    continue;
                }
                String key = pageEntry.getKey() + "|" + lowerName;
                SearchHit existing = aggregated.get(key);
                if (existing == null) {
                    aggregated.put(key, new SearchHit(pageEntry.getKey(), item.name, item.count, page.scannedMs));
                } else {
                    aggregated.put(key, new SearchHit(pageEntry.getKey(), item.name,
                            existing.count() + item.count, page.scannedMs));
                }
            }
        }
        List<SearchHit> hits = new ArrayList<>(aggregated.values());
        hits.sort(Comparator.comparingInt(SearchHit::count).reversed()
                .thenComparing(SearchHit::name, String.CASE_INSENSITIVE_ORDER));
        return hits;
    }

    static int pageCount(String account) {
        return forAccount(account).pages.size();
    }

    private static Data loadFromDisk(String account) {
        Path path = pathFor(account);
        if (!Files.exists(path)) {
            return freshData();
        }
        try {
            Data data = GSON.fromJson(Files.readString(path), Data.class);
            if (data == null || data.season != SEASON) {
                return freshData();
            }
            if (data.pages == null) {
                data.pages = new HashMap<>();
            }
            data.pages.keySet().removeIf(key -> key == null || !key.matches("\\d+"));
            return data;
        } catch (IOException | JsonSyntaxException ignored) {
            return freshData();
        }
    }

    private static Data freshData() {
        Data data = new Data();
        data.season = SEASON;
        return data;
    }

    private static Path pathFor(String account) {
        return Path.of(System.getProperty("user.home"), ".bazzar", "pvindex_" + account + ".json");
    }
}
