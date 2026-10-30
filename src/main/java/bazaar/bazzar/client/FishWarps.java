package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.client.Minecraft;

// the fishing spots behind /fish. The ones we know of are built in, and any others the server lists
// under /fish are picked up from the command list it sends on join and remembered in the config.
final class FishWarps {
    static final String COMMAND = "fish";
    private static final int CHECK_INTERVAL_TICKS = 40;
    // a long row of icons would push the farm warp panel past its maximum width
    private static final int MAX_SPOTS = 14;
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("pilchard", "Pilchard");
        KNOWN.put("mackerel", "Mackerel");
        KNOWN.put("seabass", "Sea Bass");
        KNOWN.put("bluefin", "Bluefin");
    }

    private static List<String> discovered = List.of();
    private static int tickCounter = 0;

    private FishWarps() {
    }

    // every fishing spot to show, the known ones first and anything the server added after them
    static List<String> spots() {
        List<String> spots = new ArrayList<>(KNOWN.keySet());
        for (String name : discovered.isEmpty() ? saved() : discovered) {
            if (!spots.contains(name)) {
                spots.add(name);
            }
        }
        return spots.size() > MAX_SPOTS ? spots.subList(0, MAX_SPOTS) : spots;
    }

    static String displayName(String spot) {
        String known = KNOWN.get(spot);
        if (known != null) {
            return known;
        }
        return spot.isEmpty() ? spot : Character.toUpperCase(spot.charAt(0)) + spot.substring(1);
    }

    static String warpCommand(String spot) {
        return COMMAND + " " + spot;
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            return;
        }
        if (++tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        List<String> found = readCommandTree(client);
        if (found.isEmpty() || found.equals(discovered)) {
            return;
        }
        discovered = found;
        List<String> extra = new ArrayList<>();
        for (String name : found) {
            if (!KNOWN.containsKey(name)) {
                extra.add(name);
            }
        }
        if (!extra.equals(saved())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.fishWarps = extra;
            BazzarConfig.save(state);
        }
    }

    // /fish <spot> arrives as one child node per spot, so the list is already on the client
    private static List<String> readCommandTree(Minecraft client) {
        try {
            CommandDispatcher<?> dispatcher = client.player.connection.getCommands();
            if (dispatcher == null) {
                return List.of();
            }
            CommandNode<?> fish = resolve(dispatcher.getRoot().getChild(COMMAND));
            if (fish == null) {
                return List.of();
            }
            List<String> found = new ArrayList<>();
            for (CommandNode<?> child : fish.getChildren()) {
                // only the spelled-out spots; an argument node would hand us its own name instead
                if (!(child instanceof LiteralCommandNode<?>)) {
                    continue;
                }
                String name = normalize(child.getName());
                if (!name.isEmpty() && !found.contains(name)) {
                    found.add(name);
                }
            }
            return found;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    // an alias such as /fishing points at the real node instead of carrying the spots itself
    private static CommandNode<?> resolve(CommandNode<?> node) {
        return node == null || node.getRedirect() == null ? node : node.getRedirect();
    }

    private static List<String> saved() {
        List<String> saved = BazzarConfig.load().fishWarps;
        if (saved == null) {
            return List.of();
        }
        List<String> clean = new ArrayList<>();
        for (String name : saved) {
            String normalized = normalize(name);
            if (!normalized.isEmpty() && !clean.contains(normalized)) {
                clean.add(normalized);
            }
        }
        return clean;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
