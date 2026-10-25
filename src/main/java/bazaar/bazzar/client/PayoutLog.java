package bazaar.bazzar.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

final class PayoutLog {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("bazzar").resolve("payouts.json");
    private static final int MAX_ENTRIES = 300;

    static final class Entry {
        String kind;
        String sender;
        double received;
        double paid;
        long timeMs;
        boolean alt;
        String status;
        String note;
    }

    static final class Data {
        List<Entry> entries = new ArrayList<>();
    }

    private static Data cached;

    private PayoutLog() {
    }

    static void add(String kind, String sender, double received, double paid) {
        append(kind, sender, received, paid, false);
    }

    static void addFromAlt(String kind, String sender, double received) {
        append(kind, sender, received, 0.0d, true);
    }

    static Entry addQueued(String kind, String sender, double received, double paid) {
        Entry entry = append(kind, sender, received, paid, false);
        entry.status = "queued";
        write(data());
        return entry;
    }

    static void setStatus(Entry entry, String status, String note) {
        entry.status = status;
        entry.note = note;
        write(data());
    }

    private static Entry append(String kind, String sender, double received, double paid, boolean alt) {
        Data data = data();
        Entry entry = new Entry();
        entry.kind = kind;
        entry.sender = sender;
        entry.received = received;
        entry.paid = paid;
        entry.alt = alt;
        entry.timeMs = System.currentTimeMillis();
        data.entries.add(entry);
        while (data.entries.size() > MAX_ENTRIES) {
            data.entries.remove(0);
        }
        write(data);
        return entry;
    }

    static List<Entry> newestFirst() {
        List<Entry> entries = new ArrayList<>(data().entries);
        java.util.Collections.reverse(entries);
        return entries;
    }

    static void clear() {
        write(new Data());
    }

    private static Data data() {
        if (cached != null) {
            return cached;
        }
        cached = new Data();
        if (Files.exists(PATH)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(PATH), Data.class);
                if (loaded != null && loaded.entries != null) {
                    loaded.entries.removeIf(entry -> entry == null || entry.sender == null || entry.kind == null);
                    cached = loaded;
                }
            } catch (IOException | JsonSyntaxException ignored) {
            }
        }
        return cached;
    }

    private static void write(Data data) {
        cached = data;
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(data));
        } catch (IOException ignored) {
        }
    }
}
