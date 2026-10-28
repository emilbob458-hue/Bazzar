package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class MobMilestones {
    static final long TARGET = 25_000L;

    static final int MODE_ALL = 0;
    static final int MODE_SELECTED = 1;
    static final int MODE_CURRENT = 2;

    private enum State {
        IDLE,
        WAIT_FOR_MENU,
        WAIT_FOR_PAGE,
        READ_PAGE,
        WAIT_FOR_NEXT,
        CLOSE
    }

    static final class Entry {
        String name;
        long slain;
        boolean complete;

        long remaining() {
            return complete ? 0L : Math.max(0L, TARGET - slain);
        }

        boolean done() {
            return complete || slain >= TARGET;
        }
    }

    static final class Data {
        Map<String, Entry> mobs = new LinkedHashMap<>();
        long scannedMs;
    }

    private static final class Store {
        Map<String, Data> players = new HashMap<>();
    }

    private static final String MILESTONES_COMMAND = "milestones";
    private static final String MOB_MENU_NAME = "mob milestone";
    private static final int SETTLE_TICKS = 4;
    private static final int OPEN_TIMEOUT_TICKS = 100;
    private static final int PAGE_TIMEOUT_TICKS = 30;
    private static final int TIMEOUT_TICKS = 600;
    private static final int MAX_PAGES = 20;
    private static final long KILL_WINDOW_MS = 10_000L;
    private static final long SPLASH_WINDOW_MS = 1_500L;
    private static final double SPLASH_RANGE = 5.0d;
    private static final long SAVE_DELAY_MS = 30_000L;
    private static final Pattern SLAIN_PATTERN = Pattern.compile("([0-9][0-9,]*)\\s*slain", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = Path.of(System.getProperty("user.home"), ".bazzar", "mob_milestones.json");

    private static final Map<Integer, Long> RECENT_HITS = new HashMap<>();
    private static final Set<Integer> COUNTED_DEAD = new HashSet<>();
    private static final Map<String, Entry> collected = new LinkedHashMap<>();

    private static Store store;
    private static State state = State.IDLE;
    private static int waitTicks = 0;
    private static int timeoutTicks = 0;
    private static int pagesRead = 0;
    private static boolean pendingOpen = false;
    private static boolean clickedMobMenu = false;
    private static String lastPageSignature = "";
    private static long lastAttackMs = 0L;
    private static long unsavedSinceMs = 0L;
    private static String currentMobKey = null;

    private MobMilestones() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("milestonescan")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            if (!start(client)) {
                                context.getSource().sendFeedback(BazaarChat.prefix().append(
                                        Component.literal("Can't scan milestones right now.")));
                                return 0;
                            }
                            return 1;
                        })));
    }

    static void registerEvents() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                long now = System.currentTimeMillis();
                RECENT_HITS.put(entity.getId(), now);
                lastAttackMs = now;
                String key = matchMob(entity);
                if (key != null) {
                    currentMobKey = key;
                }
            }
            return InteractionResult.PASS;
        });
    }

    static boolean start(Minecraft client) {
        if (state != State.IDLE || client.player == null || client.player.connection == null) {
            return false;
        }
        if (client.getCurrentServer() == null) {
            return false;
        }
        collected.clear();
        pagesRead = 0;
        waitTicks = 0;
        timeoutTicks = 0;
        clickedMobMenu = false;
        lastPageSignature = "";
        state = State.WAIT_FOR_MENU;
        if (McCompat.screen(client) != null) {
            pendingOpen = true;
            McCompat.setScreen(client, null);
        } else {
            CommandSender.send(client, MILESTONES_COMMAND);
        }
        return true;
    }

    static boolean isScanning() {
        return state != State.IDLE;
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            RECENT_HITS.clear();
            COUNTED_DEAD.clear();
            return;
        }
        countKills(client);
        if (unsavedSinceMs > 0L && System.currentTimeMillis() - unsavedSinceMs >= SAVE_DELAY_MS) {
            save();
        }
        tickScan(client);
    }

    private static void tickScan(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        timeoutTicks++;
        if (timeoutTicks > TIMEOUT_TICKS) {
            finish(client);
            return;
        }
        if (pendingOpen && McCompat.screen(client) == null) {
            pendingOpen = false;
            CommandSender.send(client, MILESTONES_COMMAND);
        }
        AbstractContainerScreen<?> screen = McCompat.screen(client) instanceof AbstractContainerScreen<?> open ? open : null;

        switch (state) {
            case WAIT_FOR_MENU -> {
                if (screen != null && isMobPage(screen)) {
                    waitTicks = 0;
                    state = State.WAIT_FOR_PAGE;
                    return;
                }
                if (++waitTicks > OPEN_TIMEOUT_TICKS) {
                    finish(client);
                    return;
                }
                // the zombie head may take a few ticks to show up after /milestones opens
                if (screen != null && !clickedMobMenu && waitTicks >= SETTLE_TICKS && clickMobMenu(client, screen)) {
                    clickedMobMenu = true;
                    waitTicks = 0;
                }
            }
            case WAIT_FOR_PAGE -> {
                if (screen == null || !isMobPage(screen)) {
                    return;
                }
                if (++waitTicks >= SETTLE_TICKS && hasMobEggs(client, screen)) {
                    state = State.READ_PAGE;
                }
            }
            case READ_PAGE -> {
                if (screen == null || !isMobPage(screen)) {
                    finish(client);
                    return;
                }
                readPage(client, screen);
                pagesRead++;
                lastPageSignature = pageSignature(screen);
                if (pagesRead >= MAX_PAGES || !clickNextPage(client, screen)) {
                    state = State.CLOSE;
                    return;
                }
                waitTicks = 0;
                state = State.WAIT_FOR_NEXT;
            }
            case WAIT_FOR_NEXT -> {
                if (screen != null && isMobPage(screen) && !pageSignature(screen).equals(lastPageSignature)) {
                    waitTicks = 0;
                    state = State.WAIT_FOR_PAGE;
                } else if (++waitTicks > PAGE_TIMEOUT_TICKS) {
                    // the arrow did nothing, so this was the last page
                    state = State.CLOSE;
                }
            }
            case CLOSE -> finish(client);
            default -> {
            }
        }
    }

    private static void finish(Minecraft client) {
        boolean found = !collected.isEmpty();
        if (found) {
            Data data = data();
            data.mobs.clear();
            data.mobs.putAll(collected);
            data.scannedMs = System.currentTimeMillis();
            save();
        }
        if (client.player != null) {
            String message = found
                    ? "Saved milestones for " + collected.size() + " mobs."
                    : "Could not read mob milestones from /" + MILESTONES_COMMAND + ".";
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(message)
                    .withStyle(found ? ChatFormatting.AQUA : ChatFormatting.RED)));
        }
        state = State.IDLE;
        waitTicks = 0;
        timeoutTicks = 0;
        pendingOpen = false;
        collected.clear();
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            McCompat.setScreen(client, null);
        }
    }

    private static void countKills(Minecraft client) {
        long now = System.currentTimeMillis();
        COUNTED_DEAD.removeIf(id -> client.level.getEntity(id) == null);
        Iterator<Map.Entry<Integer, Long>> it = RECENT_HITS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> hit = it.next();
            Entity entity = client.level.getEntity(hit.getKey());
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                countKill(living);
                it.remove();
            } else if (entity == null || now - hit.getValue() > KILL_WINDOW_MS) {
                it.remove();
            }
        }
        if (now - lastAttackMs > SPLASH_WINDOW_MS) {
            return;
        }
        // sweep and splash kills: mobs near you that were just hurt while you were attacking
        for (LivingEntity living : client.level.getEntitiesOfClass(LivingEntity.class,
                client.player.getBoundingBox().inflate(SPLASH_RANGE))) {
            if (!(living instanceof Player) && living.isDeadOrDying() && living.hurtTime > 0) {
                countKill(living);
            }
        }
    }

    private static void countKill(LivingEntity living) {
        if (!COUNTED_DEAD.add(living.getId())) {
            return;
        }
        String key = matchMob(living);
        if (key == null) {
            return;
        }
        Entry entry = data().mobs.get(key);
        entry.slain++;
        currentMobKey = key;
        if (unsavedSinceMs == 0L) {
            unsavedSinceMs = System.currentTimeMillis();
        }
    }

    private static String matchMob(Entity entity) {
        Map<String, Entry> mobs = data().mobs;
        if (mobs.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        if (entity.getCustomName() != null) {
            names.add(entity.getCustomName().getString());
        }
        // villagers report their profession here, which is how librarian etc. are named
        names.add(entity.getName().getString());
        names.add(entity.getType().getDescription().getString());
        String best = null;
        for (String name : names) {
            String candidate = key(name);
            for (String mobKey : mobs.keySet()) {
                if (candidate.contains(mobKey) && (best == null || mobKey.length() > best.length())) {
                    best = mobKey;
                }
            }
        }
        return best;
    }

    private static boolean isMobPage(AbstractContainerScreen<?> screen) {
        return clean(screen.getTitle().getString()).toLowerCase(Locale.ROOT).contains("mob milestones");
    }

    private static boolean clickMobMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            String name = clean(slot.getItem().getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains(MOB_MENU_NAME)) {
                click(client, menu, i);
                return true;
            }
        }
        return false;
    }

    private static boolean clickNextPage(Minecraft client, AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            String name = clean(slot.getItem().getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("next page") || name.equals("next")) {
                click(client, menu, i);
                return true;
            }
        }
        return false;
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slotIndex) {
        if (client.gameMode == null || client.player == null) {
            return;
        }
        client.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0, ClickType.PICKUP, client.player);
    }

    private static boolean hasMobEggs(Minecraft client, AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory) && isMobEgg(slot.getItem())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMobEgg(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spawn_egg");
    }

    private static void readPage(Minecraft client, AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !isMobEgg(slot.getItem())) {
                continue;
            }
            Entry entry = parse(client, slot.getItem());
            if (entry != null) {
                collected.put(key(entry.name), entry);
            }
        }
    }

    private static Entry parse(Minecraft client, ItemStack stack) {
        String name = clean(stack.getHoverName().getString()).trim();
        name = name.replaceAll("(?i)\\s*milestones?$", "").trim();
        if (name.isEmpty()) {
            return null;
        }
        Entry entry = new Entry();
        entry.name = name;
        boolean sawSlain = false;
        try {
            List<Component> tooltip = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level),
                    client.player,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL
            );
            for (Component part : tooltip) {
                if (part == null) {
                    continue;
                }
                String line = clean(part.getString());
                Matcher matcher = SLAIN_PATTERN.matcher(line);
                if (!sawSlain && matcher.find()) {
                    entry.slain = Long.parseLong(matcher.group(1).replace(",", ""));
                    sawSlain = true;
                } else if (line.toLowerCase(Locale.ROOT).contains("complete!")) {
                    entry.complete = true;
                }
            }
        } catch (Exception ignored) {
        }
        return sawSlain ? entry : null;
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

    private static String clean(String raw) {
        String stripped = SbText.stripFormattingCodes(SbText.stripSprites(raw));
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            out.append(SbText.foldChar(stripped.charAt(i)));
        }
        return out.toString();
    }

    static String key(String name) {
        return name == null ? "" : clean(name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static List<Entry> entries() {
        return new ArrayList<>(data().mobs.values());
    }

    static Entry get(String key) {
        return key == null ? null : data().mobs.get(key);
    }

    static Entry currentMob() {
        return get(currentMobKey);
    }

    static long scannedMs() {
        return data().scannedMs;
    }

    static boolean hasData() {
        return !data().mobs.isEmpty();
    }

    private static String playerKey() {
        Minecraft client = Minecraft.getInstance();
        return client.getUser() == null ? "" : client.getUser().getName().toLowerCase(Locale.ROOT);
    }

    private static Data data() {
        if (store == null) {
            store = load();
        }
        return store.players.computeIfAbsent(playerKey(), name -> new Data());
    }

    private static Store load() {
        if (!Files.exists(DATA_PATH)) {
            return new Store();
        }
        try {
            Store loaded = GSON.fromJson(Files.readString(DATA_PATH), Store.class);
            if (loaded == null) {
                return new Store();
            }
            if (loaded.players == null) {
                loaded.players = new HashMap<>();
            }
            for (Data data : loaded.players.values()) {
                if (data.mobs == null) {
                    data.mobs = new LinkedHashMap<>();
                }
            }
            return loaded;
        } catch (IOException | JsonSyntaxException ignored) {
            return new Store();
        }
    }

    static void save() {
        unsavedSinceMs = 0L;
        if (store == null) {
            return;
        }
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(store));
        } catch (IOException ignored) {
        }
    }
}
