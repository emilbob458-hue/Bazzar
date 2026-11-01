package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.JsonOps;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// the fishing spots behind /fish. The ones we know of are built in, and any others the server lists
// under /fish are picked up from the command list it sends on join and remembered in the config.
final class FishWarps {
    static final String COMMAND = "fish";
    private static final int CHECK_INTERVAL_TICKS = 40;
    // a long row of icons would push the farm warp panel past its maximum width
    private static final int MAX_SPOTS = 18;
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("pilchard", "Pilchard");
        KNOWN.put("mackerel", "Mackerel");
        KNOWN.put("seabass", "Sea Bass");
        KNOWN.put("bluefin", "Bluefin");
        KNOWN.put("koi", "Koi");
        KNOWN.put("redseabream", "Red Sea Bream");
        KNOWN.put("unagieel", "Unagi Eel");
        KNOWN.put("dragoncarp", "Dragon Carp");
        KNOWN.put("clownfish", "Clownfish");
        KNOWN.put("lionfish", "Lionfish");
        KNOWN.put("barracuda", "Barracuda");
        KNOWN.put("bluemarlin", "Blue Marlin");
        KNOWN.put("tilapia", "Tilapia");
        KNOWN.put("bluegill", "Bluegill");
        KNOWN.put("rivercatfish", "River Catfish");
        KNOWN.put("tigerfish", "Tigerfish");
    }

    private static final Map<String, String> RENAMED = Map.of("bluefintuna", "bluefin");

    private static final int SCAN_SETTLE_TICKS = 6;
    private static final int SCAN_OPEN_TIMEOUT_TICKS = 100;
    private static final int SCAN_PAGE_TIMEOUT_TICKS = 40;
    private static final int SCAN_MAX_PAGES = 6;
    private static final Pattern NOT_A_SPOT = Pattern.compile(
            "(?i)\\b(next|previous|prev|back|close|exit|page|info|information|help|menu)\\b");

    private enum ScanState {
        IDLE,
        WAIT_OPEN,
        READ,
        WAIT_PAGE
    }

    private static List<String> discovered = List.of();
    private static int tickCounter = 0;
    private static ScanState scanState = ScanState.IDLE;
    private static int scanTicks = 0;
    private static int scanPages = 0;
    private static boolean scanPendingOpen = false;
    private static String scanFingerprint = "";
    private static final Map<String, String[]> scanned = new LinkedHashMap<>();
    private static final Map<String, ItemStack> iconCache = new LinkedHashMap<>();
    private static final int ID_RETRY_TICKS = 600;
    private static final int ID_MAX_TRIES = 3;
    private static ClientPacketListener idConnection = null;
    private static int idTries = 0;
    private static int idTicks = 0;
    private static boolean idsReceived = false;

    private FishWarps() {
    }

    static boolean isScanning() {
        return scanState != ScanState.IDLE;
    }

    static boolean hasScan() {
        return !scanEntries().isEmpty();
    }

    static boolean startScan(Minecraft client) {
        if (scanState != ScanState.IDLE || client.player == null || client.player.connection == null) {
            return false;
        }
        scanned.clear();
        scanPages = 0;
        scanTicks = 0;
        scanState = ScanState.WAIT_OPEN;
        if (McCompat.screen(client) != null) {
            scanPendingOpen = true;
            McCompat.setScreen(client, null);
        } else {
            CommandSender.send(client, COMMAND);
        }
        return true;
    }

    private static void tickScan(Minecraft client) {
        if (scanState == ScanState.IDLE) {
            return;
        }
        if (scanPendingOpen && McCompat.screen(client) == null) {
            scanPendingOpen = false;
            CommandSender.send(client, COMMAND);
        }
        AbstractContainerScreen<?> screen = menuScreen(client);
        scanTicks++;
        switch (scanState) {
            case WAIT_OPEN -> {
                if (screen != null) {
                    scanTicks = 0;
                    scanState = ScanState.READ;
                } else if (scanTicks > SCAN_OPEN_TIMEOUT_TICKS) {
                    finishScan(client);
                }
            }
            case READ -> {
                if (screen == null) {
                    finishScan(client);
                    return;
                }
                if (scanTicks < SCAN_SETTLE_TICKS) {
                    return;
                }
                readMenu(client, screen);
                scanPages++;
                int next = scanPages < SCAN_MAX_PAGES ? nextPageSlot(screen) : -1;
                if (next < 0) {
                    finishScan(client);
                    return;
                }
                scanFingerprint = fingerprint(screen);
                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, next, 0, ClickType.PICKUP, client.player);
                scanTicks = 0;
                scanState = ScanState.WAIT_PAGE;
            }
            case WAIT_PAGE -> {
                if (screen != null && !fingerprint(screen).equals(scanFingerprint)) {
                    scanTicks = 0;
                    scanState = ScanState.READ;
                } else if (scanTicks > SCAN_PAGE_TIMEOUT_TICKS) {
                    finishScan(client);
                }
            }
            default -> {
            }
        }
    }

    private static void readMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        List<String> treeSpots = readCommandTree(client);
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (itemId.contains("glass_pane") || itemId.endsWith(":barrier") || itemId.endsWith(":arrow")) {
                continue;
            }
            String display = SbText.stripFormattingCodes(stack.getHoverName().getString()).replaceAll("[^A-Za-z0-9 '\\-]", " ")
                    .trim().replaceAll("\\s+", " ");
            if (display.isEmpty() || NOT_A_SPOT.matcher(display).find()) {
                continue;
            }
            String key = spotKey(display, treeSpots);
            if (!key.isEmpty() && !scanned.containsKey(key)) {
                scanned.put(key, new String[]{key, display, itemId, iconJson(client, stack)});
            }
        }
    }

    private static String spotKey(String display, List<String> treeSpots) {
        String key = normalize(display);
        for (List<String> names : List.of(treeSpots, new ArrayList<>(KNOWN.keySet()))) {
            if (names.contains(key)) {
                return key;
            }
            String best = "";
            for (String name : names) {
                if (key.contains(name) && name.length() > best.length()) {
                    best = name;
                }
            }
            if (!best.isEmpty()) {
                return best;
            }
        }
        return key.replaceAll("(fishing)?(spot|pond|lake|area)$", "");
    }

    private static void finishScan(Minecraft client) {
        scanState = ScanState.IDLE;
        scanPendingOpen = false;
        if (!scanned.isEmpty()) {
            List<String> entries = new ArrayList<>();
            for (String[] entry : scanned.values()) {
                entries.add(String.join("|", entry));
            }
            BazzarConfig.State state = BazzarConfig.load();
            state.fishWarpScan = entries;
            BazzarConfig.save(state);
        }
        if (client.player != null) {
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(scanned.isEmpty()
                            ? "Couldn't read any fishing spots from /" + COMMAND + "."
                            : "Saved " + scanned.size() + " fishing spots from /" + COMMAND + ".")
                    .withStyle(scanned.isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA)));
        }
        scanned.clear();
        if (menuScreen(client) != null) {
            McCompat.setScreen(client, null);
        }
        if (McCompat.screen(client) == null) {
            McCompat.setScreen(client, new FarmWarpScreen());
        }
    }

    private static AbstractContainerScreen<?> menuScreen(Minecraft client) {
        if (McCompat.screen(client) instanceof InventoryScreen || McCompat.screen(client) instanceof CreativeModeInventoryScreen) {
            return null;
        }
        return McCompat.screen(client) instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    private static int nextPageSlot(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()
                    && SbText.normalizeText(slot.getItem().getHoverName().getString()).contains("next")) {
                return i;
            }
        }
        return -1;
    }

    private static String fingerprint(AbstractContainerScreen<?> screen) {
        StringBuilder out = new StringBuilder().append(screen.getMenu().containerId).append('|');
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory)) {
                out.append(slot.getItem().getHoverName().getString()).append(';');
            }
        }
        return out.toString();
    }

    private static List<String[]> scanEntries() {
        List<String> saved = BazzarConfig.load().fishWarpScan;
        List<String[]> entries = new ArrayList<>();
        if (saved == null) {
            return entries;
        }
        for (String line : saved) {
            String[] parts = line == null ? new String[0] : line.split("\\|", 4);
            if (parts.length >= 3 && !parts[0].isEmpty()) {
                parts[0] = RENAMED.getOrDefault(parts[0], parts[0]);
                entries.add(parts);
            }
        }
        return entries;
    }

    private static String[] scanEntry(String spot) {
        for (String[] entry : scanEntries()) {
            if (entry[0].equals(spot)) {
                return entry;
            }
        }
        return null;
    }

    static ItemStack scannedIcon(String spot) {
        String[] entry = scanEntry(spot);
        if (entry == null) {
            return null;
        }
        if (entry.length > 3 && !entry[3].isEmpty()) {
            ItemStack full = iconFromJson(entry[3]);
            if (full != null) {
                return full;
            }
        }
        Identifier id = Identifier.tryParse(entry[2]);
        if (id == null) {
            return null;
        }
        var ref = BuiltInRegistries.ITEM.get(id);
        if (ref.isEmpty()) {
            return null;
        }
        Item item = ref.get().value();
        return item == Items.AIR ? null : new ItemStack(item);
    }

    private static String iconJson(Minecraft client, ItemStack stack) {
        if (client.level == null) {
            return "";
        }
        try {
            return ItemStack.CODEC
                    .encodeStart(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack.copyWithCount(1))
                    .result().map(JsonElement::toString).orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static ItemStack iconFromJson(String json) {
        ItemStack cached = iconCache.get(json);
        if (cached != null) {
            return cached.copy();
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        try {
            ItemStack stack = ItemStack.CODEC
                    .parse(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json))
                    .result().orElse(null);
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            iconCache.put(json, stack);
            return stack.copy();
        } catch (Exception ignored) {
            return null;
        }
    }

    // every fishing spot to show, the known ones first and anything the server added after them
    static List<String> spots() {
        List<String[]> scan = scanEntries();
        if (!scan.isEmpty()) {
            List<String> spots = new ArrayList<>();
            for (String[] entry : scan) {
                spots.add(entry[0]);
            }
            return spots.size() > MAX_SPOTS ? spots.subList(0, MAX_SPOTS) : spots;
        }
        List<String> spots = new ArrayList<>(KNOWN.keySet());
        for (String name : discovered.isEmpty() ? saved() : discovered) {
            if (!spots.contains(name)) {
                spots.add(name);
            }
        }
        for (String id : serverIds()) {
            String name = normalize(id);
            if (!name.isEmpty() && !spots.contains(name) && matchingSpot(name, spots) == null) {
                spots.add(name);
            }
        }
        return spots.size() > MAX_SPOTS ? spots.subList(0, MAX_SPOTS) : spots;
    }

    private static String matchingSpot(String id, List<String> spots) {
        String best = null;
        for (String spot : spots) {
            if (spot.equals(id)) {
                return spot;
            }
            String name = normalize(displayName(spot));
            if ((spot.contains(id) || name.contains(id)) && (best == null || spot.length() < best.length())) {
                best = spot;
            }
        }
        return best;
    }

    private static String serverId(String spot) {
        String best = null;
        String name = normalize(displayName(spot));
        for (String id : serverIds()) {
            String key = normalize(id);
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals(spot)) {
                return id;
            }
            if ((spot.contains(key) || name.contains(key)) && (best == null || key.length() > normalize(best).length())) {
                best = id;
            }
        }
        return best;
    }

    private static List<String> serverIds() {
        List<String> ids = BazzarConfig.load().fishWarpIds;
        return ids == null ? List.of() : ids;
    }

    private static String commandArg(String spot) {
        String id = serverId(spot);
        if (id != null) {
            return id;
        }
        String name = displayName(spot).trim().toLowerCase(Locale.ROOT);
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    static String displayName(String spot) {
        String known = KNOWN.get(spot);
        if (known != null) {
            return known;
        }
        String[] entry = scanEntry(spot);
        if (entry != null && !entry[1].isEmpty()) {
            return entry[1];
        }
        return spot.isEmpty() ? spot : Character.toUpperCase(spot.charAt(0)) + spot.substring(1);
    }

    static String warpCommand(String spot) {
        return COMMAND + " " + commandArg(spot);
    }

    static String upgradeCommand(String command) {
        if (command == null || !command.startsWith(COMMAND + " ")) {
            return command;
        }
        String arg = normalize(command.substring(COMMAND.length() + 1));
        if (arg.isEmpty()) {
            return command;
        }
        arg = RENAMED.getOrDefault(arg, arg);
        List<String> spots = spots();
        if (spots.contains(arg)) {
            return warpCommand(arg);
        }
        for (String spot : spots) {
            if (normalize(commandArg(spot)).equals(arg)) {
                return warpCommand(spot);
            }
        }
        String spot = matchingSpot(arg, spots);
        return warpCommand(spot == null ? arg : spot);
    }

    private static void tickServerIds(Minecraft client) {
        ClientPacketListener connection = client.player.connection;
        if (connection != idConnection) {
            idConnection = connection;
            idTries = 0;
            idTicks = 0;
            idsReceived = false;
        }
        if (idsReceived || idTries >= ID_MAX_TRIES || McCompat.screen(client) != null) {
            return;
        }
        if (idTries > 0 && ++idTicks < ID_RETRY_TICKS) {
            return;
        }
        try {
            CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
            if (dispatcher == null || dispatcher.getRoot().getChild(COMMAND) == null) {
                return;
            }
            idTries++;
            idTicks = 0;
            String input = COMMAND + " ";
            dispatcher.getCompletionSuggestions(dispatcher.parse(input, connection.getSuggestionsProvider()), input.length())
                    .thenAccept(suggestions -> {
                        List<String> ids = new ArrayList<>();
                        for (Suggestion suggestion : suggestions.getList()) {
                            String id = suggestion.getText().trim();
                            if (!id.isEmpty() && !id.contains(" ") && !ids.contains(id)) {
                                ids.add(id);
                            }
                        }
                        client.execute(() -> saveServerIds(ids));
                    });
        } catch (Exception ignored) {
        }
    }

    private static void saveServerIds(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        idsReceived = true;
        BazzarConfig.State state = BazzarConfig.load();
        if (!ids.equals(state.fishWarpIds)) {
            state.fishWarpIds = ids;
            BazzarConfig.save(state);
        }
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            return;
        }
        tickScan(client);
        tickServerIds(client);
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
