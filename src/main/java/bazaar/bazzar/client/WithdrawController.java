package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class WithdrawController {
    private enum State {
        IDLE,
        WAIT_BACKPACK,
        SEARCH,
        WAIT_PAGE,
        WAIT_DIALOG,
        CONFIRM
    }

    private enum Phase {
        MAIN,
        MOB_DROPS
    }

    private static final int SETTLE_TICKS = 4;
    private static final int GUI_TIMEOUT_TICKS = 100;
    private static final int PAGE_TIMEOUT_TICKS = 40;
    private static final int DIALOG_TIMEOUT_TICKS = 80;
    private static final int MAX_PAGES = 12;
    private static final Pattern AMOUNT_TAIL = Pattern.compile(
            "^(.*\\S)\\s+(all|\\d[\\d,]*(?:\\.\\d+)?[kmbtq]?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile("\\s*\\([^()]*\\)\\s*$");
    private static final Set<String> KNOWN_NAMES = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> MOB_DROP_KEYS = new HashSet<>();

    private static State state = State.IDLE;
    private static Phase[] phases = new Phase[0];
    private static int phaseIndex = 0;
    private static boolean enteredMobDrops = false;
    private static int pagesVisited = 0;
    private static int waitTicks = 0;
    private static int settleTicks = 0;
    private static String pageFingerprint = "";
    private static String targetName = "";
    private static String targetKey = "";
    private static String amount = null;

    private WithdrawController() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("withdraw")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(BazaarChat.prefix().append(Component.literal("Usage: /withdraw <item> [amount]")
                                    .withStyle(ChatFormatting.YELLOW)));
                            return 1;
                        })
                        .then(argument("item", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                    for (String name : KNOWN_NAMES) {
                                        if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(WithdrawController::runWithdraw))));
    }

    private static int runWithdraw(CommandContext<FabricClientCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "item").trim();
        String item = input;
        String requested = null;
        Matcher matcher = AMOUNT_TAIL.matcher(input);
        if (matcher.matches()) {
            item = matcher.group(1).trim();
            String value = matcher.group(2);
            requested = value.equalsIgnoreCase("all") ? null : value.replace(",", "").toUpperCase(Locale.ROOT);
        }
        String key = key(item);
        if (key.isEmpty()) {
            ctx.getSource().sendFeedback(BazaarChat.prefix().append(Component.literal("Usage: /withdraw <item> [amount]")
                    .withStyle(ChatFormatting.YELLOW)));
            return 0;
        }
        targetName = item;
        targetKey = key;
        amount = requested;
        phases = MOB_DROP_KEYS.contains(key)
                ? new Phase[]{Phase.MOB_DROPS, Phase.MAIN}
                : new Phase[]{Phase.MAIN, Phase.MOB_DROPS};
        phaseIndex = 0;
        openBackpack(Minecraft.getInstance());
        ctx.getSource().sendFeedback(BazaarChat.prefix().append(Component.literal("Withdrawing "
                + (amount == null ? "all " : amount + " ") + item + "...").withStyle(ChatFormatting.AQUA)));
        return 1;
    }

    static void tick(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        if (client == null || client.player == null || client.gameMode == null || client.level == null) {
            state = State.IDLE;
            return;
        }
        switch (state) {
            case WAIT_BACKPACK -> tickWaitBackpack(client);
            case SEARCH -> tickSearch(client);
            case WAIT_PAGE -> tickWaitPage(client);
            case WAIT_DIALOG -> tickWaitDialog(client);
            case CONFIRM -> tickConfirm(client);
            default -> {
            }
        }
    }

    private static void openBackpack(Minecraft client) {
        if (client.player != null && McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        enteredMobDrops = false;
        pagesVisited = 0;
        waitTicks = 0;
        state = State.WAIT_BACKPACK;
        CommandSender.send(client, "backpack");
    }

    private static void tickWaitBackpack(Minecraft client) {
        if (backpackScreen(client) != null) {
            state = State.SEARCH;
            settleTicks = SETTLE_TICKS;
            return;
        }
        if (++waitTicks > GUI_TIMEOUT_TICKS) {
            fail(client, "Your backpack didn't open.");
        }
    }

    private static void tickSearch(Minecraft client) {
        AbstractContainerScreen<?> screen = backpackScreen(client);
        if (screen == null) {
            fail(client, "Backpack closed, withdraw cancelled.");
            return;
        }
        if (--settleTicks > 0) {
            return;
        }
        rememberNames(screen);
        Phase phase = phases[phaseIndex];
        if (phase == Phase.MOB_DROPS && !enteredMobDrops) {
            int mobDropsSlot = findSlot(screen, name -> key(name).equals("mobdrops"));
            if (mobDropsSlot >= 0) {
                enteredMobDrops = true;
                pagesVisited = 0;
                clickAndWait(client, screen, mobDropsSlot);
                return;
            }
            if (!turnPage(client, screen)) {
                nextPhase(client);
            }
            return;
        }
        int targetSlot = findSlot(screen, name -> key(name).equals(targetKey));
        if (targetSlot >= 0) {
            if (phase == Phase.MOB_DROPS) {
                MOB_DROP_KEYS.add(targetKey);
            } else {
                MOB_DROP_KEYS.remove(targetKey);
            }
            client.gameMode.handleInventoryMouseClick(
                    screen.getMenu().containerId, targetSlot, 1, ClickType.PICKUP, client.player);
            waitTicks = 0;
            state = State.WAIT_DIALOG;
            return;
        }
        if (!turnPage(client, screen)) {
            nextPhase(client);
        }
    }

    private static boolean turnPage(Minecraft client, AbstractContainerScreen<?> screen) {
        if (pagesVisited >= MAX_PAGES) {
            return false;
        }
        int nextSlot = findSlot(screen, name -> SbText.normalizeText(name).contains("next"));
        if (nextSlot < 0) {
            return false;
        }
        pagesVisited++;
        clickAndWait(client, screen, nextSlot);
        return true;
    }

    private static void nextPhase(Minecraft client) {
        if (++phaseIndex >= phases.length) {
            fail(client, "Couldn't find " + targetName + " in your backpack.");
            return;
        }
        openBackpack(client);
    }

    private static void clickAndWait(Minecraft client, AbstractContainerScreen<?> screen, int slot) {
        pageFingerprint = fingerprint(screen);
        client.gameMode.handleInventoryMouseClick(
                screen.getMenu().containerId, slot, 0, ClickType.PICKUP, client.player);
        waitTicks = 0;
        state = State.WAIT_PAGE;
    }

    private static void tickWaitPage(Minecraft client) {
        AbstractContainerScreen<?> screen = backpackScreen(client);
        if (screen == null) {
            if (++waitTicks > PAGE_TIMEOUT_TICKS) {
                fail(client, "Backpack closed, withdraw cancelled.");
            }
            return;
        }
        if (!fingerprint(screen).equals(pageFingerprint) || ++waitTicks > PAGE_TIMEOUT_TICKS) {
            state = State.SEARCH;
            settleTicks = SETTLE_TICKS;
        }
    }

    private static void tickWaitDialog(Minecraft client) {
        EditBox box = McCompat.screen(client) == null || McCompat.screen(client) instanceof AbstractContainerScreen<?>
                ? null
                : findWidget(McCompat.screen(client).children(), EditBox.class, widget -> true);
        if (box == null) {
            if (++waitTicks > DIALOG_TIMEOUT_TICKS) {
                fail(client, "The withdraw prompt didn't show up.");
            }
            return;
        }
        if (amount != null) {
            box.setValue(amount);
        }
        settleTicks = 2;
        state = State.CONFIRM;
    }

    private static void tickConfirm(Minecraft client) {
        if (--settleTicks > 0) {
            return;
        }
        if (McCompat.screen(client) == null) {
            fail(client, "The withdraw prompt closed.");
            return;
        }
        AbstractButton button = findWidget(McCompat.screen(client).children(), AbstractButton.class,
                widget -> SbText.normalizeText(widget.getMessage().getString()).contains("withdraw"));
        if (button == null) {
            fail(client, "Couldn't find the Withdraw button.");
            return;
        }
        button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        state = State.IDLE;
        McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal("Withdrew "
                + (amount == null ? "all " : amount + " ") + targetName + ".").withStyle(ChatFormatting.AQUA)));
    }

    private static void fail(Minecraft client, String message) {
        state = State.IDLE;
        if (client.player != null) {
            McCompat.chat(client.player,
                    BazaarChat.prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
        }
    }

    private static AbstractContainerScreen<?> backpackScreen(Minecraft client) {
        if (McCompat.screen(client) instanceof InventoryScreen || McCompat.screen(client) instanceof CreativeModeInventoryScreen) {
            return null;
        }
        return McCompat.screen(client) instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    private static int findSlot(AbstractContainerScreen<?> screen, Predicate<String> matches) {
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && matches.test(stripAmount(stack.getHoverName().getString()))) {
                return i;
            }
        }
        return -1;
    }

    private static void rememberNames(AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) {
                continue;
            }
            String raw = SbText.stripFormattingCodes(slot.getItem().getHoverName().getString()).trim();
            if (AMOUNT_SUFFIX.matcher(raw).find()) {
                KNOWN_NAMES.add(stripAmount(raw));
            }
        }
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

    private static String stripAmount(String name) {
        return AMOUNT_SUFFIX.matcher(SbText.stripFormattingCodes(name)).replaceAll("").trim();
    }

    private static String key(String name) {
        return SbText.normalizeText(name).replaceAll("[^a-z0-9]", "");
    }

    private static <T> T findWidget(List<? extends GuiEventListener> listeners, Class<T> type, Predicate<T> filter) {
        for (GuiEventListener listener : listeners) {
            if (type.isInstance(listener) && filter.test(type.cast(listener))) {
                return type.cast(listener);
            }
            if (listener instanceof ContainerEventHandler container) {
                T nested = findWidget(container.children(), type, filter);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
