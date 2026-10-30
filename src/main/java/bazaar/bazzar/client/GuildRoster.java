package bazaar.bazzar.client;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class GuildRoster {
    private enum State {
        IDLE,
        WAIT_FOR_OPEN,
        WAIT_FOR_ITEMS
    }

    private static final String GUILD_COMMAND = "guild";
    private static final long FIRST_SCAN_DELAY_MS = 15_000L;
    private static final long RESCAN_MS = 30L * 60_000L;
    private static final long RETRY_MS = 5L * 60_000L;
    private static final int ITEM_DELAY_TICKS = 4;
    private static final int TIMEOUT_TICKS = 100;
    private static final Pattern MEMBER_PATTERN = Pattern.compile("^([A-Za-z0-9_]{3,16})\\s*\\((?:online|offline)\\)", Pattern.CASE_INSENSITIVE);

    private static State state = State.IDLE;
    private static int ticks = 0;
    private static long joinedAtMs = 0L;
    private static long nextScanMs = 0L;
    private static Set<String> members = Set.of();

    private GuildRoster() {
    }

    static Set<String> members() {
        return members;
    }

    static boolean isMember(String name) {
        return name != null && (members.isEmpty() || members.contains(name.toLowerCase(Locale.ROOT)));
    }

    static void scanSoon() {
        nextScanMs = 0L;
    }

    static boolean isScanning() {
        return state != State.IDLE;
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.getCurrentServer() == null) {
            joinedAtMs = 0L;
            state = State.IDLE;
            return;
        }
        long now = System.currentTimeMillis();
        if (joinedAtMs == 0L) {
            joinedAtMs = now;
            nextScanMs = 0L;
        }
        switch (state) {
            case IDLE -> {
                if (GuildShare.isOff() || now - joinedAtMs < FIRST_SCAN_DELAY_MS || now < nextScanMs) {
                    return;
                }
                if (McCompat.screen(client) != null || GuildQuestScanner.isScanning()) {
                    return;
                }
                nextScanMs = now + RETRY_MS;
                ticks = 0;
                CommandSender.send(client, GUILD_COMMAND);
                state = State.WAIT_FOR_OPEN;
            }
            case WAIT_FOR_OPEN -> {
                Screen screen = McCompat.screen(client);
                if (screen instanceof AbstractContainerScreen<?> && guildName(screen) != null) {
                    ticks = 0;
                    state = State.WAIT_FOR_ITEMS;
                } else if (++ticks > TIMEOUT_TICKS) {
                    state = State.IDLE;
                }
            }
            case WAIT_FOR_ITEMS -> {
                Screen screen = McCompat.screen(client);
                if (!(screen instanceof AbstractContainerScreen<?> container) || guildName(screen) == null) {
                    state = State.IDLE;
                    return;
                }
                if (++ticks < ITEM_DELAY_TICKS) {
                    return;
                }
                Set<String> found = readMembers(container);
                if (!found.isEmpty()) {
                    members = Set.copyOf(found);
                    GuildShare.onGuildDetected(guildName(screen));
                    nextScanMs = now + RESCAN_MS;
                }
                McCompat.setScreen(client, null);
                state = State.IDLE;
            }
        }
    }

    static String guildName(Screen screen) {
        return parseGuildName(screen.getTitle().getString());
    }

    static String parseGuildName(String title) {
        String text = SbText.normalizeText(title).trim();
        if (!text.startsWith("guild info")) {
            return null;
        }
        int bar = text.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String name = text.substring(bar + 1).replaceAll("[^a-z0-9_ ]", "").trim().replaceAll("\\s+", " ");
        if (name.length() < 2) {
            return null;
        }
        return name.length() > 32 ? name.substring(0, 32).trim() : name;
    }

    static String parseMember(String itemName) {
        Matcher matcher = MEMBER_PATTERN.matcher(SbText.stripFormattingCodes(itemName).trim());
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static Set<String> readMembers(AbstractContainerScreen<?> container) {
        Set<String> found = new LinkedHashSet<>();
        for (Slot slot : container.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || slot.container instanceof Inventory) {
                continue;
            }
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("player_head")) {
                continue;
            }
            String member = parseMember(stack.getHoverName().getString());
            if (member != null) {
                found.add(member);
            }
        }
        return found;
    }
}
