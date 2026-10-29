package bazaar.bazzar.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class FarmMobScanner {
    private enum Phase {
        REWIND,
        READ,
        RETURN
    }

    private enum State {
        IDLE,
        WAIT_FOR_OPEN,
        WAIT_FOR_ITEMS,
        WAIT_FOR_PAGE,
        CLOSE
    }

    static final class MobInfo {
        String crop;
        String mob;
        String drop;
        String mobJson;
        String dropJson;
        String bossDrop;
        String bossDropJson;
        List<String> tooltip;
        transient Component mobLine;
        transient Component dropLine;
        transient Component bossDropLine;
        transient boolean decoded;

        Component mobText() {
            decode();
            return mobLine;
        }

        Component dropText() {
            decode();
            return dropLine;
        }

        Component bossDropText() {
            decode();
            return bossDropLine;
        }

        private void decode() {
            if (decoded) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (client.level == null) {
                return;
            }
            decoded = true;
            mobLine = fromJson(client, mobJson);
            dropLine = fromJson(client, dropJson);
            bossDropLine = fromJson(client, bossDropJson);
        }
    }

    static final class Data {
        Map<String, MobInfo> farms = new LinkedHashMap<>();
        long scannedMs;
    }

    private static final String WARP_COMMAND = "warpfarm";
    private static final String MENU_TITLE = "warp to crop";
    private static final int SETTLE_TICKS = 4;
    private static final int TIMEOUT_TICKS = 400;
    private static final int PAGE_TIMEOUT_TICKS = 40;
    private static final int MAX_PAGES = 8;
    private static final Pattern PAGE_PATTERN = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");
    private static final Pattern MOB_PATTERN = Pattern.compile("^mob\\s*\\((.*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOSS_DROP_PATTERN = Pattern.compile("^drops?\\s+the\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAT_PATTERN = Pattern.compile("^([A-Za-z][A-Za-z0-9 '\\-]*?)\\s*:");
    private static final Set<String> NOT_DROPS = Set.of("health", "blood", "xp", "exp", "value");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = Path.of(System.getProperty("user.home"), ".bazzar", "farm_mobs.json");

    private static Data cached;
    private static State state = State.IDLE;
    private static Phase phase = Phase.REWIND;
    private static int waitTicks = 0;
    private static int timeoutTicks = 0;
    private static int pagesRead = 0;
    private static String lastPageSignature = "";
    private static boolean pendingOpen = false;
    private static boolean reopenWarpScreen = false;
    private static boolean autoTried = false;
    private static final Map<String, MobInfo> collected = new LinkedHashMap<>();
    // the Infernal Citadel farms aren't in /warpfarm, so their info is copied from the farm warp menu
    private static final Map<String, MobInfo> BUILT_IN = new LinkedHashMap<>();

    static {
        builtIn("Crimson Nylium", "Hoglin", 0xFF5555, "Nether Wart", 0xAA0000, null);
        builtIn("Shroomlight", "Strider", 0xFFAA00, "Lava Bucket", 0xFFAA00, null);
        builtIn("Warped Wart", "Zoglin", 0x55FFFF, "Rotten Hide", 0xC27A3A, null);
        builtIn("Nether Quartz", "Zombified Piglin", 0xFFFFFF, "Gold Nugget", 0xFFFF55, "Michael Spawner");
        builtIn("Gilded Blackstone", "Piglin", 0xFFAA00, "Nether Brick", 0xAA0000, null);
        builtIn("Redstone", "Ghast", 0xFF5555, "Ghast Tear", 0xFFFFFF, null);
        builtIn("Raw Gold", "Piglin Brute", 0xFFAA00, "Netherite Scrap", 0x8B6E5A, null);
        builtIn("Netherite", "Wither Skeleton", 0xAAAAAA, "Withered Coal", 0x555555, null);
    }

    private FarmMobScanner() {
    }

    private static void builtIn(String crop, String mob, int mobColor, String drop, int dropColor, String bossDrop) {
        MobInfo info = new MobInfo();
        info.crop = crop;
        info.mob = mob;
        info.drop = drop;
        info.bossDrop = bossDrop;
        info.mobLine = Component.literal(mob).withColor(mobColor);
        info.dropLine = Component.literal(drop).withColor(dropColor);
        info.bossDropLine = bossDrop == null ? null : Component.literal(bossDrop).withColor(0xFFAA00);
        info.decoded = true;
        BUILT_IN.put(key(crop), info);
    }

    static List<String> builtInMobs() {
        List<String> mobs = new ArrayList<>();
        for (MobInfo info : BUILT_IN.values()) {
            mobs.add(info.mob);
        }
        return mobs;
    }

    static String mobForDrop(String drop) {
        String wanted = key(drop);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Map<String, MobInfo> farms : List.of(data().farms, BUILT_IN)) {
            for (MobInfo info : farms.values()) {
                if (wanted.equals(key(info.drop))) {
                    return info.mob;
                }
            }
        }
        return null;
    }

    static String cropForMob(String mob) {
        String wanted = key(mob);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Map<String, MobInfo> farms : List.of(data().farms, BUILT_IN)) {
            for (MobInfo info : farms.values()) {
                if (wanted.equals(key(info.mob))) {
                    return info.crop;
                }
            }
        }
        return null;
    }

    static MobInfo get(String cropName) {
        String wanted = key(cropName);
        Map<String, MobInfo> farms = data().farms;
        MobInfo exact = farms.get(wanted);
        if (exact != null || wanted.isEmpty()) {
            return exact;
        }
        MobInfo builtIn = BUILT_IN.get(wanted);
        if (builtIn != null) {
            return builtIn;
        }
        for (Map.Entry<String, MobInfo> entry : farms.entrySet()) {
            if (entry.getKey().startsWith(wanted) || wanted.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean hasData() {
        return !data().farms.isEmpty();
    }

    static boolean isScanning() {
        return state != State.IDLE;
    }

    static boolean shouldAutoScan() {
        return !autoTried && !hasData() && !isScanning();
    }

    static boolean start(Minecraft client, boolean reopenAfter) {
        if (state != State.IDLE || client.player == null || client.player.connection == null) {
            return false;
        }
        if (client.getCurrentServer() == null) {
            return false;
        }
        autoTried = true;
        collected.clear();
        pagesRead = 0;
        lastPageSignature = "";
        waitTicks = 0;
        timeoutTicks = 0;
        reopenWarpScreen = reopenAfter;
        phase = Phase.REWIND;
        state = State.WAIT_FOR_OPEN;
        if (McCompat.screen(client) != null) {
            pendingOpen = true;
            McCompat.setScreen(client, null);
        } else {
            CommandSender.send(client, WARP_COMMAND);
        }
        return true;
    }

    static void tick(Minecraft client) {
        if (state == State.IDLE || client.player == null) {
            return;
        }
        timeoutTicks++;
        if (timeoutTicks > TIMEOUT_TICKS) {
            finish(client);
            return;
        }
        if (pendingOpen && McCompat.screen(client) == null) {
            pendingOpen = false;
            CommandSender.send(client, WARP_COMMAND);
        }

        switch (state) {
            case WAIT_FOR_OPEN -> {
                if (menuScreen(client) != null) {
                    waitTicks = 0;
                    state = State.WAIT_FOR_ITEMS;
                }
            }
            case WAIT_FOR_ITEMS -> {
                AbstractContainerScreen<?> screen = menuScreen(client);
                if (screen == null) {
                    return;
                }
                waitTicks++;
                if (waitTicks < SETTLE_TICKS || !hasFarmItems(client, screen)) {
                    return;
                }
                lastPageSignature = pageSignature(screen);
                if (phase != Phase.READ) {
                    if (!isFirstPage(screen) && clickPreviousPage(client, screen)) {
                        waitTicks = 0;
                        state = State.WAIT_FOR_PAGE;
                        return;
                    }
                    if (phase == Phase.RETURN) {
                        state = State.CLOSE;
                        return;
                    }
                    phase = Phase.READ;
                }
                readPage(client, screen);
                pagesRead++;
                if (pagesRead >= MAX_PAGES || isLastPage(screen) || !clickNextPage(client, screen)) {
                    phase = Phase.RETURN;
                } else {
                    waitTicks = 0;
                    state = State.WAIT_FOR_PAGE;
                }
            }
            case WAIT_FOR_PAGE -> {
                AbstractContainerScreen<?> screen = menuScreen(client);
                if (screen != null && !pageSignature(screen).equals(lastPageSignature)) {
                    waitTicks = 0;
                    state = State.WAIT_FOR_ITEMS;
                } else if (++waitTicks > PAGE_TIMEOUT_TICKS) {
                    if (phase == Phase.REWIND && screen != null) {
                        phase = Phase.READ;
                        waitTicks = 0;
                        state = State.WAIT_FOR_ITEMS;
                    } else {
                        state = State.CLOSE;
                    }
                }
            }
            case CLOSE -> finish(client);
            default -> {
            }
        }
    }

    private static void finish(Minecraft client) {
        boolean reopen = reopenWarpScreen;
        if (!collected.isEmpty()) {
            Data data = new Data();
            data.farms.putAll(collected);
            data.scannedMs = System.currentTimeMillis();
            save(data);
        }
        if (client.player != null) {
            String message = collected.isEmpty()
                    ? "Could not read farm mobs from /" + WARP_COMMAND + "."
                    : "Saved mobs for " + collected.size() + " farms.";
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(message)
                    .withStyle(collected.isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA)));
        }
        state = State.IDLE;
        waitTicks = 0;
        timeoutTicks = 0;
        pendingOpen = false;
        reopenWarpScreen = false;
        collected.clear();
        if (menuScreen(client) != null) {
            McCompat.setScreen(client, null);
        }
        if (reopen && McCompat.screen(client) == null) {
            McCompat.setScreen(client, new FarmWarpScreen());
        }
    }

    private static AbstractContainerScreen<?> menuScreen(Minecraft client) {
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?> screen
                && clean(screen.getTitle().getString()).toLowerCase(Locale.ROOT).contains(MENU_TITLE)) {
            return screen;
        }
        return null;
    }

    private static boolean hasFarmItems(Minecraft client, AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()
                    && parse(client, slot.getItem()) != null) {
                return true;
            }
        }
        return false;
    }

    private static void readPage(Minecraft client, AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            MobInfo info = parse(client, slot.getItem());
            if (info != null) {
                info.mobJson = toJson(client, info.mobLine);
                info.dropJson = toJson(client, info.dropLine);
                info.bossDropJson = toJson(client, info.bossDropLine);
                info.mobLine = null;
                info.dropLine = null;
                info.bossDropLine = null;
                collected.put(key(info.crop), info);
            }
        }
    }

    private static MobInfo parse(Minecraft client, ItemStack stack) {
        List<Component> lines = tooltipComponents(client, stack);
        String mob = null;
        String drop = null;
        Component mobLine = null;
        Component dropLine = null;
        boolean sawStat = false;
        for (Component component : lines) {
            String raw = component.getString();
            String stripped = stripLeadingSymbols(clean(raw));
            if (mob == null) {
                Matcher matcher = MOB_PATTERN.matcher(stripped);
                if (matcher.find()) {
                    mob = stripLeadingSymbols(matcher.group(1).replaceAll("[^A-Za-z0-9 '\\-]", " ")).trim().replaceAll("\\s+", " ");
                    int open = raw.indexOf('(');
                    int close = raw.lastIndexOf(')');
                    if (open >= 0 && close > open) {
                        mobLine = sliceTrimmed(component, raw, open + 1, close);
                    }
                }
                continue;
            }
            if (stripped.isEmpty()) {
                if (sawStat) {
                    break;
                }
                continue;
            }
            Matcher matcher = STAT_PATTERN.matcher(stripped);
            if (!matcher.find()) {
                continue;
            }
            sawStat = true;
            String label = matcher.group(1).trim();
            String lower = label.toLowerCase(Locale.ROOT);
            if (lower.equals("value")) {
                break;
            }
            if (!NOT_DROPS.contains(lower)) {
                drop = label;
                int start = 0;
                while (start < raw.length() && (Character.isWhitespace(raw.charAt(start)) || "|│┃¦".indexOf(raw.charAt(start)) >= 0)) {
                    start++;
                }
                int end = raw.indexOf(':', Math.max(start, raw.indexOf(label)));
                if (end > start) {
                    dropLine = sliceTrimmed(component, raw, start, end);
                }
                break;
            }
        }
        if (mob == null || mob.isEmpty()) {
            return null;
        }
        String bossDrop = null;
        Component bossDropLine = null;
        for (Component component : lines) {
            String raw = component.getString();
            String stripped = stripLeadingSymbols(clean(raw));
            Matcher dropMatcher = BOSS_DROP_PATTERN.matcher(stripped);
            if (bossDrop == null && dropMatcher.find()) {
                bossDrop = stripLeadingSymbols(dropMatcher.group(1)).trim();
                int the = raw.toLowerCase(Locale.ROOT).indexOf("the ");
                if (the >= 0) {
                    bossDropLine = sliceTrimmed(component, raw, the + 4, raw.length());
                }
            }
        }
        String crop = clean(stack.getHoverName().getString()).trim();
        if (crop.isEmpty()) {
            return null;
        }
        MobInfo info = new MobInfo();
        info.crop = crop;
        info.mob = mob;
        info.drop = drop;
        info.mobLine = mobLine;
        info.dropLine = dropLine;
        info.bossDrop = bossDrop == null || bossDrop.isEmpty() ? null : bossDrop;
        info.bossDropLine = bossDropLine;
        if (drop == null) {
            info.tooltip = new ArrayList<>();
            for (Component line : lines) {
                info.tooltip.add(clean(line.getString()));
            }
        }
        return info;
    }

    private static Component sliceTrimmed(Component line, String raw, int from, int to) {
        while (from < to && Character.isWhitespace(raw.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(raw.charAt(to - 1))) {
            to--;
        }
        if (from >= to) {
            return null;
        }
        MutableComponent out = Component.empty();
        appendSlice(line, Style.EMPTY, from, to, new int[]{0}, out);
        return out;
    }

    private static void appendSlice(Component node, Style parent, int from, int to, int[] pos, MutableComponent out) {
        Style style = node.getStyle().applyTo(parent);
        ComponentContents contents = node.getContents();
        int start = pos[0];
        if (contents instanceof PlainTextContents plain) {
            String text = plain.text();
            int a = Math.max(from, start);
            int b = Math.min(to, start + text.length());
            if (a < b) {
                out.append(Component.literal(text.substring(a - start, b - start)).withStyle(style));
            }
            pos[0] = start + text.length();
        } else {
            MutableComponent atom = MutableComponent.create(contents);
            int length = atom.getString().length();
            if (start >= from && start + length <= to && (length > 0 || start < to)) {
                out.append(atom.withStyle(style));
            }
            pos[0] = start + length;
        }
        for (Component sibling : node.getSiblings()) {
            appendSlice(sibling, style, from, to, pos, out);
        }
    }

    private static String toJson(Minecraft client, Component component) {
        if (component == null || client.level == null) {
            return null;
        }
        try {
            return ComponentSerialization.CODEC
                    .encodeStart(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), component)
                    .result().map(JsonElement::toString).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Component fromJson(Minecraft client, String json) {
        if (json == null || json.isBlank() || client.level == null) {
            return null;
        }
        try {
            return ComponentSerialization.CODEC
                    .parse(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json))
                    .result().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isLastPage(AbstractContainerScreen<?> screen) {
        Matcher matcher = PAGE_PATTERN.matcher(clean(screen.getTitle().getString()));
        return matcher.find() && Integer.parseInt(matcher.group(1)) >= Integer.parseInt(matcher.group(2));
    }

    private static boolean isFirstPage(AbstractContainerScreen<?> screen) {
        Matcher matcher = PAGE_PATTERN.matcher(clean(screen.getTitle().getString()));
        return matcher.find() && Integer.parseInt(matcher.group(1)) <= 1;
    }

    private static boolean clickPreviousPage(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        Matcher page = PAGE_PATTERN.matcher(clean(screen.getTitle().getString()));
        boolean pastFirst = page.find() && Integer.parseInt(page.group(1)) > 1;
        AbstractContainerMenu menu = screen.getMenu();
        int arrowIndex = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (slot.container instanceof Inventory || stack.isEmpty() || parse(client, stack) != null) {
                continue;
            }
            String name = clean(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("previous") || name.contains("prev page")) {
                arrowIndex = i;
                break;
            }
            if (pastFirst && arrowIndex < 0 && !name.contains("next") && (stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW))) {
                arrowIndex = i;
            }
        }
        if (arrowIndex < 0) {
            return false;
        }
        client.gameMode.handleInventoryMouseClick(menu.containerId, arrowIndex, 0, ClickType.PICKUP, client.player);
        return true;
    }

    private static boolean clickNextPage(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int arrowIndex = -1;
        int lastIndex = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory) {
                continue;
            }
            lastIndex = i;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || parse(client, stack) != null) {
                continue;
            }
            String name = clean(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("previous") || name.contains("back")) {
                continue;
            }
            if (name.contains("next") || stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW)) {
                arrowIndex = i;
            }
        }
        if (arrowIndex < 0 && lastIndex >= 0) {
            ItemStack corner = menu.slots.get(lastIndex).getItem();
            if (!corner.isEmpty() && parse(client, corner) == null) {
                arrowIndex = lastIndex;
            }
        }
        if (arrowIndex < 0) {
            return false;
        }
        client.gameMode.handleInventoryMouseClick(menu.containerId, arrowIndex, 0, ClickType.PICKUP, client.player);
        return true;
    }

    private static String pageSignature(AbstractContainerScreen<?> screen) {
        StringBuilder signature = new StringBuilder(screen.getTitle().getString());
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) {
                signature.append('|').append(slot.getItem().getHoverName().getString());
            }
        }
        return signature.toString();
    }

    private static List<Component> tooltipComponents(Minecraft client, ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        try {
            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level),
                    client.player,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL
            );
            for (Component part : tooltip) {
                if (part != null) {
                    lines.add(part);
                }
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    private static String clean(String raw) {
        String stripped = SbText.stripFormattingCodes(SbText.stripSprites(raw));
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            out.append(SbText.foldChar(stripped.charAt(i)));
        }
        return out.toString();
    }

    private static String stripLeadingSymbols(String value) {
        String trimmed = value.trim();
        while (!trimmed.isEmpty() && !Character.isLetterOrDigit(trimmed.charAt(0))) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static String key(String cropName) {
        return cropName == null ? "" : cropName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Data data() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    private static Data load() {
        if (!Files.exists(DATA_PATH)) {
            return new Data();
        }
        try {
            Data data = GSON.fromJson(Files.readString(DATA_PATH), Data.class);
            if (data == null) {
                return new Data();
            }
            if (data.farms == null) {
                data.farms = new LinkedHashMap<>();
            }
            return data;
        } catch (IOException | JsonSyntaxException ignored) {
            return new Data();
        }
    }

    private static void save(Data data) {
        cached = data;
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(data));
        } catch (IOException ignored) {
        }
    }
}
