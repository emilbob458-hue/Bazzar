package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

// the treasure of the crop you're farming: the crop comes off the action bar ("+20 Nether Quartz"), its
// level and odds from /treasures -> that crop's world, and drops keep the count up to date between scans
final class TreasureTracker {
    private static final String COMMAND = "treasures";
    private static final long FARMING_MS = 30_000L;
    private static final long ACTION_BAR_TRUST_MS = 5_000L;
    // a world that couldn't be read is left alone this long before the next try
    private static final long RETRY_MS = 10L * 60_000L;
    // after a drop the pity counter restarts, so the world is read again once things settle
    private static final long RESCAN_AFTER_DROP_MS = 5_000L;
    private static final int SETTLE_TICKS = 6;
    private static final int OPEN_TIMEOUT_TICKS = 100;
    private static final int LUCK_CHECK_TICKS = 20;

    private enum State {
        IDLE,
        WAIT_TREASURY,
        WAIT_VAULT
    }

    private static Map<String, String[]> crops = null;
    private static String crop = null;
    private static String world = null;
    private static long farmedAtMs = 0L;
    private static long actionBarAtMs = 0L;

    private static State state = State.IDLE;
    private static String scanWorld = null;
    private static boolean scanByHand = false;
    private static int ticks = 0;
    private static final Map<String, Long> nextTryMs = new HashMap<>();
    private static final Map<String, Long> rescanAtMs = new HashMap<>();

    private static boolean luck = false;
    private static int luckTicks = 0;

    private TreasureTracker() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bazzartreasures")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(ctx -> {
                            BazzarConfig.State config = BazzarConfig.load();
                            say(ctx.getSource().getClient(), "Treasure HUD is " + (TreasureHud.isVisible() ? "on" : "off")
                                    + (config.treasureLuckOnly ? ", only during Treasure Luck" : "")
                                    + ". /bazzartreasures hud, luck or scan");
                            return 1;
                        })
                        .then(literal("hud").executes(ctx -> {
                            say(ctx.getSource().getClient(), TreasureHud.toggle()
                                    ? "Treasure HUD shown. Drag it to move, scroll to resize."
                                    : "Treasure HUD hidden, and /treasures won't be opened for it.");
                            return 1;
                        }))
                        .then(literal("luck").executes(ctx -> {
                            BazzarConfig.State config = BazzarConfig.load();
                            config.treasureLuckOnly = !config.treasureLuckOnly;
                            BazzarConfig.save(config);
                            say(ctx.getSource().getClient(), config.treasureLuckOnly
                                    ? "Treasure HUD now only shows while the Treasure Luck boss bar is up."
                                    : "Treasure HUD shows whenever you farm.");
                            return 1;
                        }))
                        .then(literal("scan").executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            if (world == null) {
                                say(client, "Farm a crop first, the scan reads that crop's world.");
                                return 0;
                            }
                            BazzarConfig.State config = BazzarConfig.load();
                            if (config.treasureLockedWorlds != null && config.treasureLockedWorlds.remove(world)) {
                                BazzarConfig.save(config);
                            }
                            nextTryMs.remove(world);
                            if (!start(client, world, true)) {
                                say(client, "Can't open /treasures right now, close what's open and try again.");
                            }
                            return 1;
                        }))));
    }

    static String crop() {
        return crop;
    }

    static String world() {
        return world;
    }

    static Treasures.Info current() {
        return crop == null ? null : data().get(Treasures.key(crop));
    }

    static boolean isLocked(String worldName) {
        List<String> locked = BazzarConfig.load().treasureLockedWorlds;
        return worldName != null && locked != null && locked.contains(worldName);
    }

    static boolean isScanning() {
        return state != State.IDLE;
    }

    static boolean luckActive() {
        return luck;
    }

    static boolean farming() {
        return crop != null && System.currentTimeMillis() - farmedAtMs <= FARMING_MS;
    }

    // ---- what you're farming ----

    static void onActionBar(String line) {
        for (String gain : QuestLiveTracker.gains(line)) {
            String[] found = crops().get(Treasures.key(gain));
            if (found != null) {
                actionBarAtMs = System.currentTimeMillis();
                setCrop(found);
                return;
            }
        }
    }

    // the block's name is the backup when the action bar shows nothing: "Nether Quartz Ore" is Nether Quartz
    static void onBlockBroken(String blockName) {
        if (System.currentTimeMillis() - actionBarAtMs <= ACTION_BAR_TRUST_MS) {
            return;
        }
        String block = Treasures.key(blockName);
        String[] best = crops().get(block);
        if (best == null) {
            String bestKey = "";
            for (Map.Entry<String, String[]> entry : crops().entrySet()) {
                if (entry.getKey().length() >= 3 && block.contains(entry.getKey()) && entry.getKey().length() > bestKey.length()) {
                    bestKey = entry.getKey();
                    best = entry.getValue();
                }
            }
        }
        if (best != null) {
            setCrop(best);
        }
    }

    private static void setCrop(String[] found) {
        crop = found[0];
        world = found[1];
        farmedAtMs = System.currentTimeMillis();
    }

    private static Map<String, String[]> crops() {
        if (crops == null) {
            Map<String, String[]> found = new LinkedHashMap<>();
            for (String worldName : FarmWarpScreen.worldNames()) {
                for (String name : FarmWarpScreen.cropNames(worldName)) {
                    found.put(Treasures.key(name), new String[]{name, worldName});
                }
            }
            crops = found;
        }
        return crops;
    }

    // ---- drops, from the server's own lines only ----

    static void onChatLine(Minecraft client, String raw) {
        if (client.player == null) {
            return;
        }
        String line = SbText.stripFormattingCodes(raw);
        Treasures.Drop drop = Treasures.duplicate(line);
        if (drop == null) {
            drop = Treasures.unearthed(line, client.player.getName().getString());
        }
        if (drop == null) {
            return;
        }
        String[] known = crops().get(Treasures.key(drop.crop()));
        String dropWorld = known == null ? null : known[1];
        Map<String, Treasures.Info> data = data();
        String key = Treasures.key(drop.crop());
        BazzarConfig.State config = BazzarConfig.load();
        config.treasures.put(key, Treasures.apply(data.get(key), drop, dropWorld));
        BazzarConfig.save(config);
        if (dropWorld != null) {
            rescanAtMs.put(dropWorld, System.currentTimeMillis() + RESCAN_AFTER_DROP_MS);
        }
    }

    private static Map<String, Treasures.Info> data() {
        BazzarConfig.State config = BazzarConfig.load();
        if (config.treasures == null) {
            config.treasures = new LinkedHashMap<>();
        }
        return config.treasures;
    }

    // ---- reading /treasures ----

    static void tick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (++luckTicks >= LUCK_CHECK_TICKS) {
            luckTicks = 0;
            luck = readLuck(client);
        }
        if (state != State.IDLE) {
            tickScan(client);
            return;
        }
        if (!TreasureHud.isVisible() || !farming() || !needsScan(world)) {
            return;
        }
        // the menu only opens when nothing else is up and nothing else of ours is busy with a menu
        if (McCompat.screen(client) != null || FishingPause.active(client) || CommandSender.queued() > 0
                || GuildQuestScanner.isScanning() || FarmMobScanner.isScanning() || MobMilestones.isScanning()
                || FishWarps.isScanning() || GuildRoster.isScanning()) {
            return;
        }
        start(client, world, false);
    }

    private static boolean needsScan(String worldName) {
        if (worldName == null || isLocked(worldName)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < nextTryMs.getOrDefault(worldName, 0L)) {
            return false;
        }
        Long rescan = rescanAtMs.get(worldName);
        if (rescan != null) {
            return now >= rescan;
        }
        for (Treasures.Info info : data().values()) {
            if (worldName.equals(info.world) && info.scannedAt > 0L) {
                return false;
            }
        }
        return true;
    }

    private static boolean start(Minecraft client, String worldName, boolean byHand) {
        if (state != State.IDLE || client.player == null || McCompat.screen(client) != null) {
            return false;
        }
        scanWorld = worldName;
        scanByHand = byHand;
        ticks = 0;
        nextTryMs.put(worldName, System.currentTimeMillis() + RETRY_MS);
        rescanAtMs.remove(worldName);
        state = State.WAIT_TREASURY;
        CommandSender.send(client, COMMAND);
        return true;
    }

    private static void tickScan(Minecraft client) {
        AbstractContainerScreen<?> screen = menu(client);
        ticks++;
        switch (state) {
            case WAIT_TREASURY -> {
                if (screen == null || !SbText.normalizeText(screen.getTitle().getString()).contains("treasury")) {
                    if (ticks > OPEN_TIMEOUT_TICKS) {
                        finish(client, "Couldn't open /" + COMMAND + ".");
                    }
                    return;
                }
                if (ticks < SETTLE_TICKS) {
                    return;
                }
                int slot = worldSlot(screen, scanWorld);
                if (slot == -2) {
                    BazzarConfig.State config = BazzarConfig.load();
                    if (!config.treasureLockedWorlds.contains(scanWorld)) {
                        config.treasureLockedWorlds.add(scanWorld);
                        BazzarConfig.save(config);
                    }
                    finish(client, scanWorld + " is locked in /" + COMMAND + ", skipping it.");
                    return;
                }
                if (slot < 0) {
                    finish(client, "Couldn't find " + scanWorld + " in /" + COMMAND + ".");
                    return;
                }
                client.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot, 0, ClickType.PICKUP, client.player);
                ticks = 0;
                state = State.WAIT_VAULT;
            }
            case WAIT_VAULT -> {
                if (screen == null || !SbText.normalizeText(screen.getTitle().getString()).contains("vault")) {
                    if (ticks > OPEN_TIMEOUT_TICKS) {
                        finish(client, "Couldn't open the " + scanWorld + " vault.");
                    }
                    return;
                }
                if (ticks < SETTLE_TICKS) {
                    return;
                }
                int read = readVault(client, screen, scanWorld);
                finish(client, read == 0 ? "No treasures found in the " + scanWorld + " vault."
                        : "Read " + read + " treasures from " + scanWorld + ".");
            }
            default -> {
            }
        }
    }

    // the slot of the world's icon, -2 if it's there but locked, -1 if it isn't there at all
    private static int worldSlot(AbstractContainerScreen<?> screen, String worldName) {
        String wanted = Treasures.key(worldName);
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!Treasures.key(SbText.normalizeText(stack.getHoverName().getString())).contains(wanted)) {
                continue;
            }
            for (String line : lore(stack)) {
                String lower = SbText.normalizeText(line);
                if (lower.contains("locked") && !lower.contains("unlocked")) {
                    return -2;
                }
            }
            return i;
        }
        return -1;
    }

    private static int readVault(Minecraft client, AbstractContainerScreen<?> screen, String worldName) {
        long now = System.currentTimeMillis();
        BazzarConfig.State config = BazzarConfig.load();
        int read = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            Treasures.Info info = Treasures.fromItem(SbText.stripFormattingCodes(stack.getHoverName().getString()), lore(stack),
                    worldName, now);
            if (info != null) {
                config.treasures.put(Treasures.key(info.name), info);
                read++;
            }
        }
        if (read > 0) {
            BazzarConfig.save(config);
        }
        return read;
    }

    private static List<String> lore(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Component component : stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL)) {
            String plain = SbText.stripFormattingCodes(component.getString());
            if (!plain.isBlank()) {
                lines.add(plain);
            }
        }
        return lines;
    }

    private static void finish(Minecraft client, String message) {
        state = State.IDLE;
        if (menu(client) != null) {
            McCompat.setScreen(client, null);
        }
        // the automatic scans stay quiet, only one you asked for says how it went
        if (scanByHand) {
            say(client, message);
        }
        scanWorld = null;
        scanByHand = false;
    }

    private static AbstractContainerScreen<?> menu(Minecraft client) {
        if (McCompat.screen(client) instanceof InventoryScreen) {
            return null;
        }
        return McCompat.screen(client) instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    // ---- the Treasure Luck boss bar ----

    private static boolean readLuck(Minecraft client) {
        try {
            BossHealthOverlay overlay = McCompat.bossOverlay(client);
            if (overlay == null) {
                return false;
            }
            for (Field field : overlay.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                if (!(field.get(overlay) instanceof Map<?, ?> map)) {
                    continue;
                }
                for (Object event : map.values()) {
                    if (event instanceof BossEvent bossEvent && bossEvent.getName() != null) {
                        String name = Treasures.key(SbText.normalizeText(bossEvent.getName().getString()));
                        if (name.contains("treasureluck") || name.contains("fog") && name.contains("treasure")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void say(Minecraft client, String text) {
        if (client.player != null) {
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(text).withStyle(ChatFormatting.AQUA)));
        }
    }
}
