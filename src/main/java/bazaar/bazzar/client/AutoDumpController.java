package bazaar.bazzar.client;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class AutoDumpController {
    private enum State {
        IDLE,
        WAIT_FOR_GUI,
        DUMPING
    }

    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final int GUI_TIMEOUT_TICKS = 100;
    private static final int DUMP_STEP_TICKS = 2;
    private static final long RETRY_COOLDOWN_MS = 15_000L;
    private static final long FULL_PV_COOLDOWN_MS = 300_000L;

    private static State state = State.IDLE;
    private static int checkCooldown = 0;
    private static int waitTicks = 0;
    private static int stepCooldown = 0;
    private static int noProgressSteps = 0;
    private static int lastRemaining = -1;
    private static long nextAllowedMs = 0L;

    private AutoDumpController() {
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null || client.level == null) {
            reset();
            return;
        }
        BazzarConfig.State config = BazzarConfig.load();
        if (config == null || !config.autoDumpEnabled) {
            reset();
            return;
        }
        switch (state) {
            case IDLE -> tickIdle(client, config);
            case WAIT_FOR_GUI -> tickWaitForGui(client);
            case DUMPING -> tickDumping(client);
        }
    }

    private static void tickIdle(Minecraft client, BazzarConfig.State config) {
        if (--checkCooldown > 0) {
            return;
        }
        checkCooldown = CHECK_INTERVAL_TICKS;
        if (McCompat.screen(client) != null) {
            return;
        }
        if (System.currentTimeMillis() < nextAllowedMs) {
            return;
        }
        if (countEmptyMainSlots(client) > 0) {
            return;
        }
        CommandSender.send(client, "pv " + clampPage(config.autoDumpPvPage));
        waitTicks = 0;
        state = State.WAIT_FOR_GUI;
    }

    private static void tickWaitForGui(Minecraft client) {
        if (isPvScreen(client)) {
            state = State.DUMPING;
            stepCooldown = 4;
            noProgressSteps = 0;
            lastRemaining = -1;
            return;
        }
        if (++waitTicks > GUI_TIMEOUT_TICKS) {
            state = State.IDLE;
            nextAllowedMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS;
        }
    }

    private static void tickDumping(Minecraft client) {
        if (!isPvScreen(client)) {
            state = State.IDLE;
            nextAllowedMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS;
            return;
        }
        if (--stepCooldown > 0) {
            return;
        }
        stepCooldown = DUMP_STEP_TICKS;
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) McCompat.screen(client);

        int remaining = 0;
        int nextSlot = -1;
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!(slot.container instanceof Inventory)) {
                continue;
            }
            int containerSlot = slot.getContainerSlot();
            if (containerSlot < 9 || containerSlot > 35) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            remaining++;
            if (nextSlot < 0) {
                nextSlot = i;
            }
        }

        if (remaining == 0) {
            finish(client, "Inventory dumped to /pv " + clampPage(BazzarConfig.load().autoDumpPvPage), false);
            return;
        }
        if (lastRemaining >= 0 && remaining >= lastRemaining) {
            noProgressSteps++;
            if (noProgressSteps >= 6) {
                finish(client, "PV looks full, dumped what fit (" + remaining + " stacks left).", true);
                return;
            }
        } else {
            noProgressSteps = 0;
        }
        lastRemaining = remaining;
        client.gameMode.handleInventoryMouseClick(
                screen.getMenu().containerId, nextSlot, 0, ClickType.QUICK_MOVE, client.player);
    }

    private static void finish(Minecraft client, String message, boolean pvFull) {
        McCompat.setScreen(client, null);
        McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(message)
                .withStyle(pvFull ? ChatFormatting.YELLOW : ChatFormatting.AQUA)));
        state = State.IDLE;
        nextAllowedMs = System.currentTimeMillis() + (pvFull ? FULL_PV_COOLDOWN_MS : RETRY_COOLDOWN_MS);
    }

    private static int countEmptyMainSlots(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        int empty = 0;
        for (int i = 9; i <= 35; i++) {
            if (inventory.getItem(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static boolean isPvScreen(Minecraft client) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("pv") || title.contains("vault");
    }

    private static void reset() {
        state = State.IDLE;
        checkCooldown = 0;
        waitTicks = 0;
    }

    static int clampPage(int page) {
        return Math.max(1, Math.min(50, page));
    }
}
