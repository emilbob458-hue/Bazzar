package bazaar.bazzar.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class SharedFile {
    static final class Data {
        Map<String, Heartbeat> altStatus = new HashMap<>();
        String broadcastCommand = "";
        String broadcastFrom = "";
        long broadcastIssuedMs = 0L;
        Map<String, Long> broadcastAcks = new HashMap<>();
    }

    static final class Heartbeat {
        double shards;
        double cobble;
        double gems;
        long lastSeenMs;
        long lastPayMs;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = Path.of(System.getProperty("user.home"), ".bazzar", "shared.json");
    private static final long CACHE_TTL_MS = 1000L;

    private static Data cached;
    private static long cachedAtMs;

    private SharedFile() {
    }

    static Data load() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        cached = loadFromDisk();
        cachedAtMs = now;
        return cached;
    }

    static synchronized void update(Consumer<Data> mutator) {
        Data fresh = loadFromDisk();
        mutator.accept(fresh);
        String json = GSON.toJson(fresh);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.createDirectories(PATH.getParent());
                Path temp = PATH.resolveSibling("shared-" + ProcessHandle.current().pid() + ".tmp");
                Files.writeString(temp, json);
                try {
                    Files.move(temp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                break;
            } catch (IOException ignored) {
            }
        }
        cached = fresh;
        cachedAtMs = System.currentTimeMillis();
    }

    private static Data loadFromDisk() {
        if (!Files.exists(PATH)) {
            return new Data();
        }
        try {
            Data data = GSON.fromJson(Files.readString(PATH), Data.class);
            return normalize(data);
        } catch (IOException | JsonSyntaxException ignored) {
            return new Data();
        }
    }

    private static Data normalize(Data data) {
        if (data == null) {
            return new Data();
        }
        if (data.altStatus == null) {
            data.altStatus = new HashMap<>();
        }
        if (data.broadcastCommand == null) {
            data.broadcastCommand = "";
        }
        if (data.broadcastFrom == null) {
            data.broadcastFrom = "";
        }
        if (data.broadcastAcks == null) {
            data.broadcastAcks = new HashMap<>();
        }
        return data;
    }
}
