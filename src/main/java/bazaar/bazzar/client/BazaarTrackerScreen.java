package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class BazaarTrackerScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 560;
    private static final int MAX_PANEL_HEIGHT = 320;
    private static final int SIDEBAR_W = 118;
    private static final String SIDEBAR_TITLE_SUFFIX = "";
    private static final int SIDEBAR_GROUP_LABEL_H = 11;
    private static final int SIDEBAR_GROUP_TEXT = 0xFF8B95A3;
    private static final int PAD = 14;
    private static final int ROW_HEIGHT = 16;
    private static final int AUTO_INTERVAL_STEP_SECONDS = 15;
    private static final int SLIDER_MIN_SECONDS = 15;
    private static final int SLIDER_MAX_SECONDS = 3600;

    private static final int PANEL_BG_TOP = 0xCC000000;
    private static final int PANEL_BG_BOTTOM = 0x88000000;
    private static final int PANEL_BORDER = 0x66FFFFFF;
    private static final int SIDEBAR_SHADE = 0x2E000000;
    private static final int HEADER_TEXT = 0xFFFFFFFF;
    private static final int MUTED_TEXT = 0xCC8A97A6;
    private static final int TAB_INACTIVE = 0x998A97A6;
    private static final int NAV_HOVER_BG = 0x22FFFFFF;
    private static final int ROW_BG = 0x1AFFFFFF;
    private static final int ROW_BG_ALT = 0x0DFFFFFF;
    private static final int BUY_TEXT = 0xFF86EFAC;
    private static final int SELL_TEXT = 0xFF7DD3FC;
    private static final int OFF_TEXT = 0xFFEF4444;
    private static final int PROFIT_TEXT = 0xFF86EFAC;
    private static final int LOSS_TEXT = 0xFFF87171;
    private static final int TIP_TEXT = 0xFFD1D5DB;
    private static final int TOGGLE_TRACK_OFF = 0xFF23282F;
    private static final int TOGGLE_BORDER_OFF = 0xFF39404A;
    private static final int TOGGLE_KNOB = 0xFFF2F4F6;
    private static final int RESET_BG = 0xFF3B1E1E;
    private static final int RESET_HOVER = 0xFF502828;
    private static final int PILL_BG = 0xFF14171C;
    private static final int SLIDER_HANDLE = 0xFFF5F7FA;

    private int ACCENT;
    private int ACCENT_DIM;
    private int ACCENT_TEXT;
    private int ON_ACCENT_TEXT;
    private int NAV_ACTIVE_BG;
    private int ROW_BG_HOVER;
    private int RATE_TEXT;
    private int ON_TEXT;
    private int BUTTON_BG;
    private int BUTTON_BG_HOVER;
    private int BUTTON_BG_ACTIVE;
    private int TOGGLE_ON;
    private int TOGGLE_BORDER_ON;
    private int SAVE_BG;
    private int SAVE_HOVER;
    private int SAVE_TEXT;
    private int SLIDER_LEFT;
    private int SLIDER_RIGHT;

    private static final List<String> DEFAULT_CROPS = List.of(
            "Wheat",
            "Carrot",
            "Potato",
            "Beetroot",
            "Pumpkin",
            "Rose",
            "Pitcher Plant",
            "Peony",
            "Lilac",
            "Azalea",
            "Basalt",
            "Calcite",
            "Magma",
            "Ice",
            "Snow",
            "Diamond",
            "Quartz",
            "Prismarine",
            "Bone",
            "Lightwood",
            "Gold",
            "Resin",
            "Glowstone",
            "Tuff",
            "Obsidian",
            "Cherry",
            "Copper"
    );

    private static MainTab lastActiveTab = MainTab.TRACKER;

    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int page;
    private int gemPage;
    private boolean sliderDragging = false;
    private boolean punchSliderDragging = false;
    private boolean dumpSliderDragging = false;
    private boolean svDragging = false;
    private boolean hueDragging = false;
    private float customHue = 0f;
    private boolean suppressHexHue = false;
    private MainTab activeMainTab = lastActiveTab;
    private SortTab activeTab = SortTab.HIGHEST_SELL;
    private GemSortTab activeGemSortTab = GemSortTab.HIGHEST_SELL;
    private String statusText = "Click a crop to toggle blacklist.";
    private final List<RowEntry> rows = new ArrayList<>();
    private final List<GemFlipEntry> gemFlips = new ArrayList<>();
    private String gemOverviewText = "Scan to calculate best gem flips.";
    private final Map<String, Float> toggleAnim = new HashMap<>();
    private long lastAnimMillis = -1L;
    private float animDelta;
    private int themeScroll;
    private int infoScroll;
    private BazzarTheme pendingTheme = BazzarTheme.current();
    private boolean cookieFallbackOpen;
    private boolean cookieRushOpen;
    private final EditBox[] macroBoxes = new EditBox[MacroController.KEY_MACRO_COUNT];
    private EditBox macroTimerBox;
    private boolean macroSliderDragging = false;
    private final long openedAtMs = System.currentTimeMillis();
    private float sidebarIndicatorY = Float.NaN;
    private long closingSinceMs = -1L;
    private final EditBox[] triggerBoxes = new EditBox[ChatCommandTriggerController.SLOT_COUNT];
    private final EditBox[] commandBoxes = new EditBox[ChatCommandTriggerController.SLOT_COUNT];
    private final EditBox[] shardPayMemberBoxes = new EditBox[ShardPayController.MEMBER_SLOT_COUNT];
    private EditBox shardPayOwnerBox;
    private EditBox customHexBox;
    private EditBox presetNameBox;
    private List<String> presetNames = new ArrayList<>();
    private long presetNamesReadMs;
    private static boolean presetsPublicView;
    private List<PublicPresets.Summary> publicPresets = new ArrayList<>();
    private String publicPresetsNote = "";
    private PublicPresets.Preset publicConfirm;
    private long publicConfirmUntilMs;

    BazaarTrackerScreen() {
        super(Component.literal("Bazaar Crop Tracker"));
        ShardPayController.applyConfig(BazzarConfig.load());
    }

    @Override
    protected void init() {
        panelW = Math.max(Math.min(300, width - 8), Math.min(MAX_PANEL_WIDTH, width - 8));
        panelH = Math.max(Math.min(200, height - 8), Math.min(MAX_PANEL_HEIGHT, height - 8));
        left = Math.max(4, (width - panelW) / 2);
        top = Math.max(4, (height - panelH) / 2);
        refreshThemeColors();
        clearWidgets();
        createTriggerBoxes();
        createMacroBoxes();
        createShardPayBoxes();
        createPresetBox();
        createCustomHexBox();
        reloadRows();
        reloadGemFlips();
    }

    private void createPresetBox() {
        presetNameBox = new EditBox(font, contentX(), presetSaveRowY(), contentW() - 80, 16, Component.literal("Preset name"));
        presetNameBox.setMaxLength(32);
        presetNameBox.setHint(Component.literal("preset name").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(presetNameBox);
        presetNames = ConfigPresets.names();
    }

    private void createCustomHexBox() {
        int rgb = BazzarConfig.load().customThemeRgb & 0xFFFFFF;
        customHue = rgbToHue(rgb);
        customHexBox = new EditBox(font, hueBarLeft() + 24, pickerTop() + PICKER_SIZE - 16, 70, 16, Component.literal("Custom hex"));
        customHexBox.setMaxLength(7);
        customHexBox.setValue(String.format(Locale.ROOT, "#%06X", rgb));
        customHexBox.setResponder(this::onCustomHexChanged);
        addRenderableWidget(customHexBox);
    }

    private void onCustomHexChanged(String value) {
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return;
        }
        int rgb = Integer.parseInt(hex, 16);
        BazzarConfig.State state = BazzarConfig.load();
        if ((state.customThemeRgb & 0xFFFFFF) != rgb) {
            state.customThemeRgb = rgb;
            BazzarConfig.save(state);
        }
        if (!suppressHexHue) {
            customHue = rgbToHue(rgb);
        }
        pendingTheme = BazzarTheme.byId("custom");
        statusText = "Custom color #" + hex.toUpperCase(Locale.ROOT) + ", press Save & Apply.";
    }

    private void createTriggerBoxes() {
        int rowX = contentX() + 14;
        int available = contentW() - 14 - 6;
        int triggerW = Math.max(80, available * 52 / 100);
        int commandW = Math.max(60, available - triggerW);
        int commandX = rowX + triggerW + 6;
        for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
            int rowY = settingsTop() + 30 + i * triggerRowStep();
            int slot = i;

            EditBox trigger = new EditBox(font, rowX, rowY, triggerW, 16, Component.literal("Trigger " + (i + 1)));
            trigger.setMaxLength(200);
            trigger.setValue(ChatCommandTriggerController.getTriggerMessage(i));
            trigger.setHint(Component.literal("trigger text").withStyle(ChatFormatting.DARK_GRAY));
            trigger.setResponder(value -> ChatCommandTriggerController.setTriggerMessage(slot, value));
            triggerBoxes[i] = trigger;
            addRenderableWidget(trigger);

            EditBox command = new EditBox(font, commandX, rowY, commandW, 16, Component.literal("Command " + (i + 1)));
            command.setMaxLength(200);
            command.setValue(ChatCommandTriggerController.getCommandToSend(i));
            command.setHint(Component.literal("command to send").withStyle(ChatFormatting.DARK_GRAY));
            command.setResponder(value -> ChatCommandTriggerController.setCommandToSend(slot, value));
            commandBoxes[i] = command;
            addRenderableWidget(command);
        }
    }

    private void createMacroBoxes() {
        int rowX = contentX() + 44;
        int boxW = contentW() - 44;
        for (int i = 0; i < MacroController.KEY_MACRO_COUNT; i++) {
            int slot = i;
            EditBox box = new EditBox(font, rowX, macroBoxesTop() + i * macroRowStep(), boxW, 14, Component.literal("Macro " + (i + 1)));
            box.setMaxLength(200);
            box.setValue(MacroController.getKeyMessage(i));
            box.setHint(Component.literal("message or /command").withStyle(ChatFormatting.DARK_GRAY));
            box.setResponder(value -> MacroController.setKeyMessage(slot, value));
            macroBoxes[i] = box;
            addRenderableWidget(box);
        }
        macroTimerBox = new EditBox(font, contentX(), macroTimerBoxY(), contentW(), 14, Component.literal("Timer macro"));
        macroTimerBox.setMaxLength(200);
        macroTimerBox.setValue(BazzarConfig.load().macroTimerMessage);
        macroTimerBox.setHint(Component.literal("message or /command sent on the timer").withStyle(ChatFormatting.DARK_GRAY));
        macroTimerBox.setResponder(value -> {
            BazzarConfig.State state = BazzarConfig.load();
            state.macroTimerMessage = value == null ? "" : value;
            BazzarConfig.save(state);
        });
        addRenderableWidget(macroTimerBox);
    }

    private void createShardPayBoxes() {
        int rowX = contentX() + 44;
        shardPayOwnerBox = new EditBox(font, rowX, shardPayOwnerRowY(), 150, 16, Component.literal("Owner"));
        shardPayOwnerBox.setMaxLength(40);
        shardPayOwnerBox.setValue(ShardPayController.getOwner());
        shardPayOwnerBox.setHint(Component.literal("owner username").withStyle(ChatFormatting.DARK_GRAY));
        shardPayOwnerBox.setResponder(ShardPayController::setOwner);
        addRenderableWidget(shardPayOwnerBox);

        int boxW = (contentW() - 6) / 2;
        for (int i = 0; i < ShardPayController.MEMBER_SLOT_COUNT; i++) {
            int slot = i;
            int col = i % 2;
            int row = i / 2;
            EditBox member = new EditBox(
                    font,
                    contentX() + col * (boxW + 6),
                    shardPayMembersTopY() + row * shardMemberRowStep(),
                    boxW,
                    16,
                    Component.literal("Payer " + (i + 1)));
            member.setMaxLength(40);
            member.setValue(ShardPayController.getMember(i));
            member.setHint(Component.literal("alt username").withStyle(ChatFormatting.DARK_GRAY));
            member.setResponder(value -> ShardPayController.setMember(slot, value));
            shardPayMemberBoxes[i] = member;
            addRenderableWidget(member);
        }
    }

    private void updateTriggerBoxVisibility() {
        boolean show = activeMainTab == MainTab.AUTO_CMD;
        for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
            if (triggerBoxes[i] != null) {
                triggerBoxes[i].visible = show;
                triggerBoxes[i].active = show;
            }
            if (commandBoxes[i] != null) {
                commandBoxes[i].visible = show;
                commandBoxes[i].active = show;
            }
        }
        boolean showMacros = activeMainTab == MainTab.MACROS;
        for (int i = 0; i < MacroController.KEY_MACRO_COUNT; i++) {
            if (macroBoxes[i] != null) {
                macroBoxes[i].visible = showMacros;
                macroBoxes[i].active = showMacros;
            }
        }
        if (macroTimerBox != null) {
            macroTimerBox.visible = showMacros;
            macroTimerBox.active = showMacros;
        }
        boolean showShardPay = activeMainTab == MainTab.SHARD_PAY;
        if (shardPayOwnerBox != null) {
            shardPayOwnerBox.visible = showShardPay;
            shardPayOwnerBox.active = showShardPay;
        }
        for (int i = 0; i < ShardPayController.MEMBER_SLOT_COUNT; i++) {
            if (shardPayMemberBoxes[i] != null) {
                shardPayMemberBoxes[i].visible = showShardPay;
                shardPayMemberBoxes[i].active = showShardPay;
            }
        }
        if (presetNameBox != null) {
            boolean showPresets = activeMainTab == MainTab.PRESETS;
            presetNameBox.visible = showPresets;
            presetNameBox.active = showPresets;
        }
        if (customHexBox != null) {
            boolean showHex = activeMainTab == MainTab.THEMES && isCustomSelected();
            customHexBox.visible = showHex;
            customHexBox.active = showHex;
            if (showHex) {
                customHexBox.setX(hueBarLeft() + 24);
                customHexBox.setY(pickerTop() + PICKER_SIZE - 16);
            }
        }
    }

    private void persistTriggerSlots() {
        BazzarConfig.State state = BazzarConfig.load();
        List<String> triggers = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
            triggers.add(ChatCommandTriggerController.getTriggerMessage(i));
            commands.add(ChatCommandTriggerController.getCommandToSend(i));
        }
        state.chatTriggerMessages = triggers;
        state.chatTriggerCommands = commands;
        BazzarConfig.save(state);
    }

    private void persistShardPaySettings() {
        BazzarConfig.State state = BazzarConfig.load();
        ShardPayController.applyConfigToState(state);
        BazzarConfig.save(state);
    }

    @Override
    public void removed() {
        persistTriggerSlots();
        persistShardPaySettings();
        super.removed();
    }

    private void refreshThemeColors() {
        BazzarTheme theme = BazzarTheme.current();
        ACCENT = theme.accent();
        ACCENT_DIM = theme.accentDim();
        ACCENT_TEXT = theme.light();
        ON_ACCENT_TEXT = theme.readableOnAccent();
        NAV_ACTIVE_BG = theme.soft(0x29);
        ROW_BG_HOVER = theme.soft(0x33);
        RATE_TEXT = theme.accent();
        ON_TEXT = theme.accent();
        BUTTON_BG = theme.buttonBg();
        BUTTON_BG_HOVER = theme.buttonBgHover();
        BUTTON_BG_ACTIVE = theme.buttonBgActive();
        TOGGLE_ON = theme.accent();
        TOGGLE_BORDER_ON = theme.light();
        SAVE_BG = theme.accent();
        SAVE_HOVER = theme.light();
        SAVE_TEXT = ON_ACCENT_TEXT;
        SLIDER_LEFT = theme.dark();
        SLIDER_RIGHT = theme.accent();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        animDelta = lastAnimMillis < 0 ? 0.0f : Math.min(0.1f, (now - lastAnimMillis) / 1000.0f);
        lastAnimMillis = now;
        refreshThemeColors();

        float open = BazzarUi.screenAnim(openedAtMs, closingSinceMs);
        graphics.fill(0, 0, width, height, (int) (0xAA * open) << 24);
        float openScale = 0.95f + 0.05f * open;
        graphics.pose().pushMatrix();
        graphics.pose().translate(left + panelW / 2.0f, top + panelH / 2.0f);
        graphics.pose().scale(openScale, openScale);
        graphics.pose().translate(-(left + panelW / 2.0f), -(top + panelH / 2.0f));

        BazzarUi.fillRoundedGradient(graphics, left, top, panelW, panelH, 8, PANEL_BG_TOP, PANEL_BG_BOTTOM);
        BazzarUi.drawRoundedGlowBorder(graphics, left, top, panelW, panelH, 8);
        drawSidebar(graphics, mouseX, mouseY);
        drawSectionHeader(graphics);
        if (activeMainTab == MainTab.TRACKER) {
            drawToolbar(graphics, mouseX, mouseY);
            drawList(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.AUTO_SCAN) {
            drawAutoScanPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.AUTO_EGG) {
            drawAutoEggPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.AUTO_CMD) {
            drawAutoCmdPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.MACROS) {
            drawMacrosPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.SHARD_PAY) {
            drawShardPayPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.PRESETS) {
            drawPresetsPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.COOKIE) {
            drawCookiePanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.HUD) {
            drawHudPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.THEMES) {
            drawThemesPanel(graphics, mouseX, mouseY);
        } else if (activeMainTab == MainTab.INFO) {
            drawInfoPanel(graphics);
        } else {
            drawToolbar(graphics, mouseX, mouseY);
            drawGemFlipList(graphics, mouseX, mouseY);
        }
        drawFooter(graphics, mouseX, mouseY);
        if (activeMainTab == MainTab.COOKIE && cookieFallbackOpen) {
            drawCookieTypeDropdown(graphics, mouseX, mouseY,
                    CookieController.getFallbackCookieOptions(), BazzarConfig.load().cookieAutoFallback, cookieFallbackButton());
        }
        if (activeMainTab == MainTab.COOKIE && cookieRushOpen) {
            drawCookieTypeDropdown(graphics, mouseX, mouseY,
                    CookieController.getRushCookieOptions(), BazzarConfig.load().cookieRushType, cookieRushTypeButton());
        }
        updateTriggerBoxVisibility();
        super.render(graphics, mouseX, mouseY, delta);
        graphics.pose().popMatrix();
    }

    @Override
    public void onClose() {
        if (closingSinceMs < 0L) {
            closingSinceMs = System.currentTimeMillis();
        }
    }

    @Override
    public void tick() {
        if (closingSinceMs >= 0L && System.currentTimeMillis() - closingSinceMs >= 130L) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (closingSinceMs >= 0L) {
            return true;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        MainTab[] tabs = MainTab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (isInside(mouseX, mouseY, sidebarItemBounds(i))) {
                MainTab tab = tabs[i];
                if (activeMainTab != tab) {
                    activeMainTab = tab;
                    lastActiveTab = tab;
                    if (tab == MainTab.GEM_FLIPS) {
                        reloadGemFlips();
                    }
                    statusText = switch (tab) {
                        case TRACKER -> "Tracker tab opened.";
                        case GEM_FLIPS -> "Best gem flips tab opened.";
                        case AUTO_SCAN -> "Auto scan settings opened.";
                        case AUTO_EGG -> "Misc settings opened.";
                        case AUTO_CMD -> "Auto command triggers opened.";
                        case MACROS -> "Chat macros opened.";
                        case SHARD_PAY -> "Shard pay settings opened.";
                        case PRESETS -> "Presets opened.";
                        case COOKIE -> "Cookie settings opened.";
                        case HUD -> "HUD settings opened.";
                        case THEMES -> "Theme gallery opened.";
                        case INFO -> "Commands and keybinds.";
                    };
                    setFocused(null);
                    cookieFallbackOpen = false;
                    cookieRushOpen = false;
                }
                return true;
            }
        }

        if (activeMainTab == MainTab.COOKIE && cookieFallbackOpen) {
            return handleCookieFallbackDropdownClick(mouseX, mouseY);
        }

        if (activeMainTab == MainTab.COOKIE && cookieRushOpen) {
            return handleCookieRushDropdownClick(mouseX, mouseY);
        }

        if (isInside(mouseX, mouseY, resetButton())) {
            resetCurrentTab();
            return true;
        }

        if (isInside(mouseX, mouseY, saveButton())) {
            saveAndClose();
            return true;
        }

        if (activeMainTab == MainTab.AUTO_SCAN) {
            return handleAutoScanClick(mouseX, mouseY);
        }

        if (activeMainTab == MainTab.AUTO_EGG) {
            return handleAutoEggClick(mouseX, mouseY);
        }

        if (activeMainTab == MainTab.AUTO_CMD) {
            if (event.button() == 1) {
                int row = autoCmdRowAt(mouseX, mouseY);
                if (row >= 0) {
                    BazzarConfig.State state = BazzarConfig.load();
                    while (state.chatTriggerSelfOnly.size() <= row) {
                        state.chatTriggerSelfOnly.add(false);
                    }
                    boolean next = !Boolean.TRUE.equals(state.chatTriggerSelfOnly.get(row));
                    state.chatTriggerSelfOnly.set(row, next);
                    BazzarConfig.save(state);
                    statusText = next
                            ? "Trigger " + (row + 1) + " now only fires from you & your alts."
                            : "Trigger " + (row + 1) + " fires from any chat.";
                    return true;
                }
            }
            return super.mouseClicked(event, bl);
        }

        if (activeMainTab == MainTab.MACROS) {
            if (handleMacrosClick(mouseX, mouseY)) {
                return true;
            }
            return super.mouseClicked(event, bl);
        }

        if (activeMainTab == MainTab.SHARD_PAY) {
            if (handleShardPayClick(mouseX, mouseY)) {
                return true;
            }
            return super.mouseClicked(event, bl);
        }

        if (activeMainTab == MainTab.PRESETS) {
            if (handlePresetsClick(mouseX, mouseY)) {
                return true;
            }
            return super.mouseClicked(event, bl);
        }

        if (activeMainTab == MainTab.COOKIE) {
            return handleCookieClick(mouseX, mouseY);
        }

        if (activeMainTab == MainTab.HUD) {
            return handleHudClick(mouseX, mouseY);
        }

        if (activeMainTab == MainTab.THEMES) {
            if (handleThemesClick(mouseX, mouseY)) {
                return true;
            }
            if (handlePickerClick(mouseX, mouseY)) {
                return true;
            }
            return super.mouseClicked(event, bl);
        }

        if (activeMainTab == MainTab.TRACKER && isInside(mouseX, mouseY, headerInfoBounds())) {
            RowEntry best = bestFarmRow();
            if (best != null) {
                String command = FarmLogic.cropToFarmCommand(best.displayName());
                if (command != null) {
                    Minecraft client = Minecraft.getInstance();
                    CommandSender.send(client, command);
                    client.setScreen(null);
                    return true;
                }
            }
        }

        if (isInside(mouseX, mouseY, scanButton())) {
            startManualScanAndClose();
            return true;
        }

        if (isInside(mouseX, mouseY, refreshButton())) {
            if (activeMainTab == MainTab.TRACKER) {
                reloadRows();
                statusText = "Tracker refreshed.";
            } else if (activeMainTab == MainTab.GEM_FLIPS) {
                reloadGemFlips();
                statusText = "Gem flip suggestions refreshed.";
            }
            return true;
        }

        if (!toolbarTight() && isInside(mouseX, mouseY, prevButton())) {
            if (activeMainTab == MainTab.TRACKER && page > 0) {
                page--;
                return true;
            }
            if (activeMainTab == MainTab.GEM_FLIPS && gemPage > 0) {
                gemPage--;
                return true;
            }
        }

        if (!toolbarTight() && isInside(mouseX, mouseY, nextButton())) {
            if (activeMainTab == MainTab.TRACKER && page + 1 < pageCount()) {
                page++;
                return true;
            }
            if (activeMainTab == MainTab.GEM_FLIPS && gemPage + 1 < gemPageCount()) {
                gemPage++;
                return true;
            }
        }

        if (activeMainTab == MainTab.GEM_FLIPS) {
            if (isInside(mouseX, mouseY, gemSellSortButton())) {
                if (activeGemSortTab != GemSortTab.HIGHEST_SELL) {
                    activeGemSortTab = GemSortTab.HIGHEST_SELL;
                    applyGemSort();
                    statusText = "Gem flips sorted by highest sell first.";
                }
                return true;
            }

            if (isInside(mouseX, mouseY, gemBuySortButton())) {
                if (activeGemSortTab != GemSortTab.LOWEST_BUY) {
                    activeGemSortTab = GemSortTab.LOWEST_BUY;
                    applyGemSort();
                    statusText = "Gem flips sorted by lowest buy first.";
                }
                return true;
            }

            GemFlipEntry entry = getGemFlipRowAt(mouseX, mouseY);
            if (entry != null) {
                statusText = entry.displayName() + ", Stock: " + formatStock(entry.stock());
                return true;
            }
            return false;
        }

        if (isInside(mouseX, mouseY, sellSortButton())) {
            if (activeTab != SortTab.HIGHEST_SELL) {
                activeTab = SortTab.HIGHEST_SELL;
                applySort();
                statusText = "Sorted by highest sell first.";
            }
            return true;
        }

        if (isInside(mouseX, mouseY, buySortButton())) {
            if (activeTab != SortTab.LOWEST_BUY) {
                activeTab = SortTab.LOWEST_BUY;
                applySort();
                statusText = "Sorted by lowest buy first.";
            }
            return true;
        }

        RowEntry row = getRowAt(mouseX, mouseY);
        if (row != null) {
            boolean enableBlacklist = !row.blacklisted();
            BazaarFlipScanner.setCropBlacklisted(row.blacklistToken(), enableBlacklist);
            reloadRows();
            reloadGemFlips();
            statusText = enableBlacklist
                    ? "Blacklisted: " + row.displayName()
                    : "Tracking again: " + row.displayName();
            return true;
        }

        return false;
    }

    private int contentX() {
        return left + SIDEBAR_W + PAD;
    }

    private int contentRight() {
        return left + panelW - PAD;
    }

    private int contentW() {
        return panelW - SIDEBAR_W - 2 * PAD;
    }

    private int sectionY() {
        return top + 14;
    }

    private int toolbarY() {
        return top + 34;
    }

    private int listTop() {
        return top + 70;
    }

    private int settingsTop() {
        return top + 38;
    }

    private int settingRowStep() {
        return panelH < 300 ? 34 : 40;
    }

    private int miscRowStep() {
        return panelH < 300 ? 28 : 34;
    }

    private int triggerRowStep() {
        return panelH < 300 ? 20 : 26;
    }

    private int shardMemberRowStep() {
        return panelH < 300 ? 18 : 20;
    }

    private int footerLineY() {
        return top + panelH - 34;
    }

    private int footerButtonY() {
        return top + panelH - 26;
    }

    private boolean toolbarTight() {
        return prevButton().x() < contentX() + 266;
    }

    private void drawSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        BazzarUi.fillLeftRoundedRect(graphics, left + 1, top + 1, SIDEBAR_W - 1, panelH - 2, 7, SIDEBAR_SHADE);
        graphics.fill(left + SIDEBAR_W, top + 1, left + SIDEBAR_W + 1, top + panelH - 1, PANEL_BORDER);

        int titleX = left + (SIDEBAR_W - font.width("Bazaar" + SIDEBAR_TITLE_SUFFIX)) / 2;
        graphics.drawString(font, "Bazaar", titleX, top + 13, HEADER_TEXT, false);
        graphics.drawString(font, SIDEBAR_TITLE_SUFFIX, titleX + font.width("Bazaar"), top + 13, ACCENT_TEXT, false);
        graphics.fill(left + 10, top + 30, left + SIDEBAR_W - 9, top + 31, PANEL_BORDER);

        MainTab[] tabs = MainTab.values();
        ButtonBounds indicatorTarget = sidebarItemBounds(activeMainTab.ordinal());
        if (Float.isNaN(sidebarIndicatorY)) {
            sidebarIndicatorY = indicatorTarget.y();
        }
        sidebarIndicatorY += (indicatorTarget.y() - sidebarIndicatorY) * Math.min(1.0f, animDelta * 16.0f);
        if (Math.abs(indicatorTarget.y() - sidebarIndicatorY) < 0.5f) {
            sidebarIndicatorY = indicatorTarget.y();
        }
        int indicatorY = Math.round(sidebarIndicatorY);
        BazzarUi.fillRoundedRect(graphics, indicatorTarget.x(), indicatorY, indicatorTarget.w(), indicatorTarget.h(), 4, NAV_ACTIVE_BG);
        BazzarUi.fillCircleSmooth(graphics, indicatorTarget.x() + 6.5f, indicatorY + indicatorTarget.h() / 2.0f, 2.0f, ACCENT);

        boolean showGroupLabels = sidebarHeaderH() >= SIDEBAR_GROUP_LABEL_H;
        for (int i = 0; i < tabs.length; i++) {
            ButtonBounds item = sidebarItemBounds(i);
            if (showGroupLabels && startsSidebarGroup(i)) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(left + 10, item.y() - sidebarHeaderH() + 2);
                graphics.pose().scale(0.75f, 0.75f);
                graphics.drawString(font, tabs[i].group(), 0, 0, SIDEBAR_GROUP_TEXT, false);
                graphics.pose().popMatrix();
            }
            boolean active = activeMainTab == tabs[i];
            boolean hovered = isInside(mouseX, mouseY, item);
            if (!active && hovered) {
                BazzarUi.fillRoundedRect(graphics, item.x(), item.y(), item.w(), item.h(), 4, NAV_HOVER_BG);
            }
            int textColor = active ? ACCENT_TEXT : (hovered ? HEADER_TEXT : TAB_INACTIVE);
            graphics.drawString(font, tabs[i].label, item.x() + 13, item.y() + (item.h() - 8) / 2 + 1, textColor, false);
        }
    }

    private void drawSectionHeader(GuiGraphics graphics) {
        String sectionTitle = switch (activeMainTab) {
            case TRACKER -> "CROP TRACKER";
            case AUTO_SCAN -> "AUTO SCAN SETTINGS";
            case GEM_FLIPS -> "GEM FLIP SUGGESTIONS";
            case AUTO_EGG -> "MISC SETTINGS";
            case AUTO_CMD -> "AUTO COMMAND TRIGGERS";
            case MACROS -> "CHAT MACROS";
            case SHARD_PAY -> "SHARD PAY";
            case PRESETS -> "PRESETS";
            case COOKIE -> "COOKIE AUTOMATION";
            case HUD -> "HUD SETTINGS";
            case THEMES -> "THEMES";
            case INFO -> "COMMANDS & INFO";
        };
        int x = contentX();
        int y = sectionY();
        graphics.drawString(font, sectionTitle, x, y, ACCENT_TEXT, false);
        int lineStart = x + font.width(sectionTitle) + 8;
        int lineEnd = contentRight();
        String info = headerInfo();
        if (!info.isEmpty()) {
            int infoW = font.width(info);
            graphics.drawString(font, info, contentRight() - infoW, y, MUTED_TEXT, false);
            lineEnd = contentRight() - infoW - 8;
        }
        if (lineEnd > lineStart) {
            graphics.fill(lineStart, y + 4, lineEnd, y + 5, ACCENT_DIM);
        }
    }

    private RowEntry bestFarmRow() {
        List<RowEntry> eligible = rows.stream().filter(row -> !row.blacklisted()).toList();
        List<String> names = eligible.stream().map(RowEntry::displayName).toList();
        List<Double> values = eligible.stream()
                .map(row -> row.sell() != null ? row.sell() : row.buy())
                .toList();
        int index = FarmLogic.pickBestIndex(names, values);
        return index < 0 ? null : eligible.get(index);
    }

    private ButtonBounds headerInfoBounds() {
        String info = headerInfo();
        int infoW = font.width(info);
        return new ButtonBounds(contentRight() - infoW, sectionY() - 2, infoW, 12);
    }

    private String headerInfo() {
        return switch (activeMainTab) {
            case TRACKER -> {
                RowEntry best = bestFarmRow();
                if (best == null) {
                    yield "Blacklisted: " + rows.stream().filter(RowEntry::blacklisted).count();
                }
                Double value = best.sell() != null ? best.sell() : best.buy();
                yield "Best: " + best.displayName() + " " + SbText.formatCompact(value) + " ▶";
            }
            case AUTO_SCAN -> BazaarAutoScanController.isAutoScanEnabled()
                    ? "ON · every " + BazaarAutoScanController.getAutoScanIntervalSeconds() + "s"
                    : "OFF";
            case GEM_FLIPS -> "";
            case AUTO_EGG -> AutoEggController.isEnabled() ? "ON" : "OFF";
            case HUD -> GuildQuestScanner.isAutoScanEnabled() ? "Auto ON · on refresh" : "Auto OFF";
            case AUTO_CMD -> activeTriggerCount() + " active";
            case MACROS -> BazzarConfig.load().macroTimerEnabled ? "Timer ON" : "";
            case SHARD_PAY -> ShardPayController.isEnabled() ? "ON" : "OFF";
            case PRESETS -> presetNames.size() + " saved";
            case COOKIE -> BazzarConfig.load().cookieAutoEnabled ? "Auto ON" : "Auto OFF";
            case THEMES -> pendingTheme != null ? pendingTheme.name : BazzarTheme.current().name;
            case INFO -> "Scroll for more";
        };
    }

    private int activeTriggerCount() {
        int count = 0;
        for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
            if (!ChatCommandTriggerController.getTriggerMessage(i).isBlank()
                    && !ChatCommandTriggerController.getCommandToSend(i).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private void drawToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (activeMainTab == MainTab.TRACKER) {
            drawPrimaryPillButton(graphics, scanButton(), "Scan", mouseX, mouseY);
            drawPillButton(graphics, refreshButton(), "Refresh", mouseX, mouseY, true, false);
            drawPillButton(graphics, sellSortButton(), "Highest Sell", mouseX, mouseY, true, activeTab == SortTab.HIGHEST_SELL);
            drawPillButton(graphics, buySortButton(), "Lowest Buy", mouseX, mouseY, true, activeTab == SortTab.LOWEST_BUY);
            if (!toolbarTight()) {
                drawPillButton(graphics, prevButton(), "<", mouseX, mouseY, page > 0, false);
                drawPillButton(graphics, nextButton(), ">", mouseX, mouseY, page + 1 < pageCount(), false);
                graphics.drawCenteredString(font, (page + 1) + "/" + pageCount(), contentRight() - 44, toolbarY() + 4, MUTED_TEXT);
            }
        } else if (activeMainTab == MainTab.GEM_FLIPS) {
            drawPrimaryPillButton(graphics, scanButton(), "Scan", mouseX, mouseY);
            drawPillButton(graphics, refreshButton(), "Refresh", mouseX, mouseY, true, false);
            drawPillButton(graphics, gemSellSortButton(), "Highest Sell", mouseX, mouseY, true, activeGemSortTab == GemSortTab.HIGHEST_SELL);
            drawPillButton(graphics, gemBuySortButton(), "Lowest Buy", mouseX, mouseY, true, activeGemSortTab == GemSortTab.LOWEST_BUY);
            if (!toolbarTight()) {
                drawPillButton(graphics, prevButton(), "<", mouseX, mouseY, gemPage > 0, false);
                drawPillButton(graphics, nextButton(), ">", mouseX, mouseY, gemPage + 1 < gemPageCount(), false);
                graphics.drawCenteredString(font, (gemPage + 1) + "/" + gemPageCount(), contentRight() - 44, toolbarY() + 4, MUTED_TEXT);
            }
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listX = contentX();
        int listY = listTop();
        int listWidth = contentW();
        int colBuy = listX + (int) (listWidth * 0.41f);
        int colSell = listX + (int) (listWidth * 0.56f);
        int colRate = listX + (int) (listWidth * 0.71f);
        int colTrack = listX + (int) (listWidth * 0.85f);

        graphics.drawString(font, "Crop", listX + 4, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Buy", colBuy, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Sell", colSell, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Rate", colRate, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Track", colTrack, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.fill(listX, listY - 2, listX + listWidth, listY - 1, ACCENT_DIM);

        int visibleRows = rowsPerPage();
        int start = page * visibleRows;
        int end = Math.min(start + visibleRows, rows.size());
        int nameMaxW = colBuy - listX - 10;
        for (int i = start; i < end; i++) {
            int rowIndex = i - start;
            int y1 = listY + rowIndex * ROW_HEIGHT;
            int y2 = y1 + ROW_HEIGHT - 1;
            RowEntry row = rows.get(i);
            boolean hovered = isInside(mouseX, mouseY, listX, y1, listWidth, ROW_HEIGHT);
            int bg = hovered ? ROW_BG_HOVER : ((rowIndex % 2 == 0) ? ROW_BG : ROW_BG_ALT);
            graphics.fill(listX, y1, listX + listWidth, y2, bg);

            String clippedName = font.plainSubstrByWidth(row.displayName(), nameMaxW);
            Component nameText = row.blacklisted()
                    ? Component.literal(clippedName).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH)
                    : Component.literal(clippedName).withStyle(ChatFormatting.WHITE);
            graphics.drawString(font, nameText, listX + 4, y1 + 4, HEADER_TEXT, false);
            graphics.drawString(font, formatPrice(row.buy()), colBuy, y1 + 4, BUY_TEXT, false);
            graphics.drawString(font, formatPrice(row.sell()), colSell, y1 + 4, SELL_TEXT, false);
            graphics.drawString(font, formatRate(row.marketRate()), colRate, y1 + 4, RATE_TEXT, false);
            graphics.drawString(font, row.blacklisted() ? "OFF" : "ON", colTrack + 3, y1 + 4, row.blacklisted() ? OFF_TEXT : ON_TEXT, false);
        }

        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, "No crop data yet. Press Scan to pull bazaar prices.", contentX() + contentW() / 2, listY + 22, MUTED_TEXT);
        }
    }

    private void drawGemFlipList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listX = contentX();
        int listY = listTop();
        int listWidth = contentW();
        int colBuy = listX + (int) (listWidth * 0.31f);
        int colSell = listX + (int) (listWidth * 0.43f);
        int colAvg = listX + (int) (listWidth * 0.55f);
        int colSpread = listX + (int) (listWidth * 0.67f);
        int colStock = listX + (int) (listWidth * 0.83f);

        graphics.drawString(font, "Spawner", listX + 4, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Buy", colBuy, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Sell", colSell, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Avg", colAvg, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Spread", colSpread, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.drawString(font, "Stock", colStock, listY - 12, SIDEBAR_GROUP_TEXT, false);
        graphics.fill(listX, listY - 2, listX + listWidth, listY - 1, ACCENT_DIM);

        int visibleRows = gemRowsPerPage();
        int start = gemPage * visibleRows;
        int end = Math.min(start + visibleRows, gemFlips.size());
        int nameMaxW = colBuy - listX - 10;
        for (int i = start; i < end; i++) {
            int rowIndex = i - start;
            int y1 = listY + rowIndex * ROW_HEIGHT;
            int y2 = y1 + ROW_HEIGHT - 1;
            GemFlipEntry row = gemFlips.get(i);
            boolean hovered = isInside(mouseX, mouseY, listX, y1, listWidth, ROW_HEIGHT);
            int bg = hovered ? ROW_BG_HOVER : ((rowIndex % 2 == 0) ? ROW_BG : ROW_BG_ALT);
            graphics.fill(listX, y1, listX + listWidth, y2, bg);

            graphics.drawString(font, font.plainSubstrByWidth(row.displayName(), nameMaxW), listX + 4, y1 + 4, HEADER_TEXT, false);
            graphics.drawString(font, formatPrice(row.buy()), colBuy, y1 + 4, BUY_TEXT, false);
            graphics.drawString(font, formatPrice(row.sell()), colSell, y1 + 4, SELL_TEXT, false);
            graphics.drawString(font, formatPrice(row.average()), colAvg, y1 + 4, RATE_TEXT, false);
            graphics.drawString(font, formatSignedPrice(row.spread()), colSpread, y1 + 4, spreadColor(row.spread()), false);
            graphics.drawString(font, formatStock(row.stock()), colStock, y1 + 4, TIP_TEXT, false);
        }

        if (gemFlips.isEmpty()) {
            graphics.drawCenteredString(font, "No flip data yet. Press Scan, then open Gem Flips.", contentX() + contentW() / 2, listY + 22, MUTED_TEXT);
            return;
        }

        int overviewY = listY + visibleRows * ROW_HEIGHT + 4;
        graphics.drawString(font, font.plainSubstrByWidth(gemOverviewText, listWidth - 8), listX + 4, overviewY, MUTED_TEXT, false);
    }

    private void drawAutoScanPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        boolean enabled = BazaarAutoScanController.isAutoScanEnabled();
        int intervalSeconds = BazaarAutoScanController.getAutoScanIntervalSeconds();

        drawSettingRow(
                graphics,
                contentX,
                settingsTop(),
                "Automatic Scan",
                "Scan bazaar prices on a recurring interval",
                autoScanToggleButton(),
                enabled,
                mouseX,
                mouseY
        );
        drawSliderSettingRow(
                graphics,
                contentX,
                settingsTop() + settingRowStep(),
                "Scan Interval",
                "Time between automatic bazaar scans",
                autoIntervalSliderBounds(),
                intervalSeconds,
                SLIDER_MIN_SECONDS,
                SLIDER_MAX_SECONDS,
                intervalSeconds + "s"
        );
        drawPillButton(graphics, autoScanNowButton(), "Scan Now", mouseX, mouseY, true, false);
        graphics.drawString(
                font,
                "Press Scan Now for an immediate manual scan.",
                contentX,
                settingsTop() + 110,
                MUTED_TEXT,
                false
        );
    }

    private void drawAutoEggPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        BazzarConfig.State state = BazzarConfig.load();

        drawSettingRow(
                graphics,
                contentX,
                settingsTop(),
                "Auto Egg Response",
                "Send 'egg' when someone buys from the store",
                autoEggToggleButton(),
                AutoEggController.isEnabled(),
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + miscRowStep(),
                "Shady Summoner Finder",
                "Alert when the Shady Summoner is nearby",
                shadyFinderToggleButton(),
                ShadySummonerFinder.isEnabled(),
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 2 * miscRowStep(),
                "AFK Failsafe",
                "Disable the active cookie after 3 min without moving",
                miscAfkToggleButton(),
                state.cookieAfkFailsafeEnabled,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 3 * miscRowStep(),
                "Auto Playtime Rewards",
                "Claim /playtimerewards when they're ready",
                miscPlaytimeToggleButton(),
                PlaytimeRewardsController.isEnabled(),
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 4 * miscRowStep(),
                "Auto PV Dump",
                "Dump full inventory into /pv",
                miscDumpToggleButton(),
                state.autoDumpEnabled,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 5 * miscRowStep(),
                "Auto Celestial Reward",
                "Claim the /rewards celestial reward when it's ready",
                miscCelestialToggleButton(),
                CelestialRewardsController.isEnabled(),
                mouseX,
                mouseY
        );
        drawGradientSlider(
                graphics,
                miscDumpSliderBounds(),
                AutoDumpController.clampPage(state.autoDumpPvPage),
                1,
                50,
                "pv " + AutoDumpController.clampPage(state.autoDumpPvPage)
        );
    }

    private void drawAutoCmdPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        List<Boolean> selfOnly = BazzarConfig.load().chatTriggerSelfOnly;
        graphics.drawString(
                font,
                font.plainSubstrByWidth("Sends commands when chat contains a trigger. Right click a row to make it fire only from you & alts.", contentW()),
                contentX,
                settingsTop(),
                MUTED_TEXT,
                false
        );
        graphics.drawString(font, "Trigger", contentX + 14, settingsTop() + 18, ACCENT_TEXT, false);
        int available = contentW() - 14 - 6;
        int triggerW = Math.max(80, available * 52 / 100);
        graphics.drawString(font, "Command", contentX + 14 + triggerW + 6, settingsTop() + 18, ACCENT_TEXT, false);
        for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
            int boxTop = settingsTop() + 30 + i * triggerRowStep();
            boolean rowSelfOnly = selfOnly != null && i < selfOnly.size() && Boolean.TRUE.equals(selfOnly.get(i));
            if (rowSelfOnly) {
                BazzarUi.drawRoundedGlowBorder(graphics, contentX + 12, boxTop - 3, available + 4, 22, 4);
            }
            int numberColor = rowSelfOnly ? ACCENT : MUTED_TEXT;
            graphics.drawString(font, String.valueOf(i + 1), contentX + 2, boxTop + 4, numberColor, false);
        }
    }

    private int autoCmdRowAt(int mouseX, int mouseY) {
        int contentX = contentX();
        int available = contentW() - 14 - 6;
        for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
            int boxTop = settingsTop() + 30 + i * triggerRowStep();
            if (isInside(mouseX, mouseY, contentX + 2, boxTop - 3, available + 14, 22)) {
                return i;
            }
        }
        return -1;
    }

    private void drawMacrosPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        BazzarConfig.State state = BazzarConfig.load();
        graphics.drawString(
                font,
                font.plainSubstrByWidth("Bind keys in Options → Controls → Bazaar. Start with / for commands.", contentW()),
                contentX,
                settingsTop(),
                MUTED_TEXT,
                false
        );
        for (int i = 0; i < MacroController.KEY_MACRO_COUNT; i++) {
            graphics.drawString(font, "Key " + (i + 1), contentX, macroBoxesTop() + 3 + i * macroRowStep(), ACCENT_TEXT, false);
        }
        drawSettingRow(
                graphics,
                contentX,
                macroTimerRowY(),
                "Timer Macro",
                "Send the message below on a repeating timer",
                macroTimerToggleButton(),
                state.macroTimerEnabled,
                mouseX,
                mouseY
        );
        drawGradientSlider(
                graphics,
                macroTimerSliderBounds(),
                state.macroTimerIntervalSeconds,
                5,
                600,
                state.macroTimerIntervalSeconds + "s"
        );
    }

    private int macroBoxesTop() {
        return settingsTop() + 24;
    }

    private int macroRowStep() {
        return panelH < 300 ? 16 : 20;
    }

    private int macroTimerRowY() {
        return macroBoxesTop() + MacroController.KEY_MACRO_COUNT * macroRowStep() + 6;
    }

    private int macroTimerBoxY() {
        return macroTimerRowY() + 20;
    }

    private ButtonBounds macroTimerToggleButton() {
        return new ButtonBounds(contentRight() - 32, macroTimerRowY() + 3, 32, 14);
    }

    private ButtonBounds macroTimerSliderBounds() {
        int width = Math.min(200, contentW() - 100);
        return new ButtonBounds(contentRight() - 40 - width, macroTimerBoxY() + 20, width, 16);
    }

    private boolean handleMacrosClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, macroTimerToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.macroTimerEnabled = !state.macroTimerEnabled;
            BazzarConfig.save(state);
            MacroController.resetTimer();
            statusText = state.macroTimerEnabled
                    ? "Timer macro enabled (every " + state.macroTimerIntervalSeconds + "s)."
                    : "Timer macro disabled.";
            return true;
        }
        ButtonBounds slider = macroTimerSliderBounds();
        if (isInside(mouseX, mouseY, slider.x(), slider.y(), slider.w(), slider.h())) {
            macroSliderDragging = true;
            applyMacroSliderPosition(mouseX);
            return true;
        }
        return false;
    }

    private void applyMacroSliderPosition(int mouseX) {
        ButtonBounds slider = macroTimerSliderBounds();
        int trackX = slider.x();
        int trackW = slider.w() - 52;
        float ratio = Math.max(0.0f, Math.min(1.0f, (float) (mouseX - trackX) / trackW));
        int raw = 5 + Math.round(ratio * (600 - 5));
        int snapped = Math.max(5, Math.min(600, Math.round((float) raw / 5) * 5));
        BazzarConfig.State state = BazzarConfig.load();
        if (state.macroTimerIntervalSeconds != snapped) {
            state.macroTimerIntervalSeconds = snapped;
            BazzarConfig.save(state);
            statusText = "Timer macro every " + snapped + "s.";
        }
    }

    private void drawShardPayPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        drawSettingRow(
                graphics,
                contentX,
                settingsTop(),
                "Auto Shard Pay",
                "Accounts auto pay their scoreboard shards to the owner",
                shardPayToggleButton(),
                ShardPayController.isEnabled(),
                mouseX,
                mouseY
        );
        graphics.drawString(font, "Owner", contentX, shardPayOwnerRowY() + 4, ACCENT_TEXT, false);
        graphics.drawString(font, "Payers (accounts that send /shardpay)", contentX, shardPayMembersTopY() - (panelH < 300 ? 10 : 12), ACCENT_TEXT, false);
        drawPillButton(graphics, shardPayNowButton(), "Pay Now", mouseX, mouseY, true, false);
        drawPillButton(graphics, shardPayoutsButton(), "Payouts", mouseX, mouseY, true, false);
        Minecraft client = Minecraft.getInstance();
        graphics.drawString(
                font,
                font.plainSubstrByWidth(ShardPayController.localStatusLine(client), contentW() - 84),
                contentX + 80,
                shardPayNowButton().y() + 4,
                MUTED_TEXT,
                false
        );
        BazzarConfig.State state = BazzarConfig.load();
        drawSettingRow(
                graphics,
                contentX,
                shardPunchRowY(),
                "Auto Punch",
                "",
                shardPunchToggleButton(),
                state.autoPunchEnabled,
                mouseX,
                mouseY
        );
        drawGradientSlider(
                graphics,
                shardPunchSliderBounds(),
                state.autoPunchIntervalSeconds,
                5,
                600,
                state.autoPunchIntervalSeconds + "s"
        );
        drawSettingRow(
                graphics,
                contentX,
                shardCobbleRowY(),
                "Pay Cobble",
                "",
                shardCobbleToggleButton(),
                state.cobblePayEnabled,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                shardGemsRowY(),
                "Pay Gems",
                "",
                shardGemsToggleButton(),
                state.gemPayEnabled,
                mouseX,
                mouseY
        );
    }

    private boolean handleShardPayClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, shardPayToggleButton())) {
            boolean enabled = !ShardPayController.isEnabled();
            ShardPayController.setEnabled(enabled);
            persistShardPaySettings();
            statusText = enabled ? "Auto shard pay enabled." : "Auto shard pay disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, shardPayNowButton())) {
            statusText = ShardPayController.payNow(Minecraft.getInstance());
            return true;
        }
        if (isInside(mouseX, mouseY, shardPayoutsButton())) {
            Minecraft.getInstance().setScreen(new PayoutHistoryScreen(this));
            return true;
        }
        if (isInside(mouseX, mouseY, shardPunchToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.autoPunchEnabled = !state.autoPunchEnabled;
            BazzarConfig.save(state);
            statusText = state.autoPunchEnabled ? "Auto punch enabled." : "Auto punch disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, shardCobbleToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cobblePayEnabled = !state.cobblePayEnabled;
            BazzarConfig.save(state);
            statusText = state.cobblePayEnabled ? "Cobble auto pay enabled." : "Cobble auto pay disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, shardGemsToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.gemPayEnabled = !state.gemPayEnabled;
            BazzarConfig.save(state);
            statusText = state.gemPayEnabled ? "Gems auto pay enabled." : "Gems auto pay disabled.";
            return true;
        }
        ButtonBounds punchSlider = shardPunchSliderBounds();
        if (isInside(mouseX, mouseY, punchSlider.x(), punchSlider.y(), punchSlider.w(), punchSlider.h())) {
            punchSliderDragging = true;
            applyPunchSliderPosition(mouseX);
            return true;
        }
        return false;
    }

    private void drawPresetsPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        if (now - presetNamesReadMs > 1000L) {
            presetNames = ConfigPresets.names();
            presetNamesReadMs = now;
        }
        int contentX = contentX();
        graphics.drawString(font, "Save current settings as a preset", contentX, settingsTop(), ACCENT_TEXT, false);
        drawPillButton(graphics, presetSaveButton(), "Save", mouseX, mouseY, true, false);
        graphics.drawString(font, presetsPublicView ? "Public presets" : "Presets", contentX, presetListTopY() - 14, ACCENT_TEXT, false);
        drawPillButton(graphics, presetMineButton(), "Mine", mouseX, mouseY, true, !presetsPublicView);
        drawPillButton(graphics, presetPublicButton(), "Public", mouseX, mouseY, true, presetsPublicView);
        if (presetsPublicView) {
            drawPublicPresets(graphics, mouseX, mouseY);
            return;
        }
        if (presetNames.isEmpty()) {
            graphics.drawString(font, "No presets yet.", contentX, presetListTopY() + 3, MUTED_TEXT, false);
            return;
        }
        int rows = Math.min(presetNames.size(), presetRowCapacity());
        for (int i = 0; i < rows; i++) {
            String name = presetNames.get(i);
            int y = presetListTopY() + i * presetRowStep();
            int nameMaxW = presetShareButton(i).x() - 8 - contentX;
            graphics.drawString(font, font.plainSubstrByWidth(name, nameMaxW), contentX, y + 3, HEADER_TEXT, false);
            drawPillButton(graphics, presetShareButton(i), "Share", mouseX, mouseY, true, false);
            drawPillButton(graphics, presetLoadButton(i), "Load", mouseX, mouseY, true, false);
            drawPillButton(graphics, presetDeleteButton(i), "Delete", mouseX, mouseY, true, false);
        }
        if (presetNames.size() > rows) {
            graphics.drawString(font, "+" + (presetNames.size() - rows) + " more, use /config list", contentX,
                    presetListTopY() + rows * presetRowStep() + 3, MUTED_TEXT, false);
        }
    }

    private void drawPublicPresets(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        if (publicPresets.isEmpty()) {
            graphics.drawString(font, publicPresetsNote.isEmpty() ? "No public presets yet, press Share on one of yours." : publicPresetsNote,
                    contentX, presetListTopY() + 3, MUTED_TEXT, false);
            return;
        }
        int rows = Math.min(publicPresets.size(), presetRowCapacity());
        for (int i = 0; i < rows; i++) {
            PublicPresets.Summary preset = publicPresets.get(i);
            int y = presetListTopY() + i * presetRowStep();
            int maxW = presetApplyButton(i).x() - 8 - contentX;
            String uses = preset.downloads() + " uses";
            int usesW = font.width(uses);
            String name = font.plainSubstrByWidth(preset.name(), Math.max(20, maxW - usesW - 8));
            graphics.drawString(font, name, contentX, y + 3, HEADER_TEXT, false);
            int nameW = font.width(name);
            String author = font.plainSubstrByWidth(" by " + preset.author(), Math.max(0, maxW - usesW - 8 - nameW));
            graphics.drawString(font, author, contentX + nameW, y + 3, MUTED_TEXT, false);
            graphics.drawString(font, uses, presetApplyButton(i).x() - 8 - usesW, y + 3, MUTED_TEXT, false);
            boolean confirming = publicConfirm != null && publicConfirm.code().equals(preset.code())
                    && System.currentTimeMillis() < publicConfirmUntilMs;
            drawPillButton(graphics, presetApplyButton(i), confirming ? "Sure?" : "Apply", mouseX, mouseY, true, confirming);
        }
        if (publicPresets.size() > rows) {
            graphics.drawString(font, "+" + (publicPresets.size() - rows) + " more, use /config public list", contentX,
                    presetListTopY() + rows * presetRowStep() + 3, MUTED_TEXT, false);
        }
    }

    private void loadPublicPresets() {
        publicPresetsNote = "Loading public presets...";
        publicPresets = new ArrayList<>();
        PublicPresets.list("top", presets -> {
            publicPresets = presets;
            publicPresetsNote = "";
        }, error -> publicPresetsNote = "Could not load: " + error + ".");
    }

    private void applyPublicPreset(PublicPresets.Preset preset) {
        publicConfirm = null;
        ConfigPresets.Result result = PublicPresets.apply(preset);
        if (result.ok()) {
            pendingTheme = BazzarTheme.current();
            rebuildWidgets();
        }
        statusText = result.message();
        presetNames = ConfigPresets.names();
    }

    private boolean handlePublicPresetsClick(int mouseX, int mouseY) {
        int rows = Math.min(publicPresets.size(), presetRowCapacity());
        for (int i = 0; i < rows; i++) {
            PublicPresets.Summary summary = publicPresets.get(i);
            if (!isInside(mouseX, mouseY, presetApplyButton(i))) {
                continue;
            }
            if (publicConfirm != null && publicConfirm.code().equals(summary.code())
                    && System.currentTimeMillis() < publicConfirmUntilMs) {
                applyPublicPreset(publicConfirm);
                return true;
            }
            statusText = "Loading " + summary.name() + "...";
            PublicPresets.fetch(summary.code(), preset -> {
                if (preset.commands().isEmpty()) {
                    applyPublicPreset(preset);
                    return;
                }
                publicConfirm = preset;
                publicConfirmUntilMs = System.currentTimeMillis() + 15_000L;
                statusText = "Adds " + preset.commands().size() + " chat command(s), see /config public view "
                        + preset.code() + ". Click again to apply.";
            }, error -> statusText = "Could not load " + summary.name() + ": " + error + ".");
            return true;
        }
        return false;
    }

    private boolean handlePresetsClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, presetMineButton())) {
            presetsPublicView = false;
            return true;
        }
        if (isInside(mouseX, mouseY, presetPublicButton())) {
            presetsPublicView = true;
            loadPublicPresets();
            return true;
        }
        if (isInside(mouseX, mouseY, presetSaveButton())) {
            ConfigPresets.Result result = ConfigPresets.save(presetNameBox == null ? "" : presetNameBox.getValue());
            statusText = result.message();
            if (result.ok() && presetNameBox != null) {
                presetNameBox.setValue("");
            }
            presetNames = ConfigPresets.names();
            return true;
        }
        if (presetsPublicView) {
            return handlePublicPresetsClick(mouseX, mouseY);
        }
        int rows = Math.min(presetNames.size(), presetRowCapacity());
        for (int i = 0; i < rows; i++) {
            String name = presetNames.get(i);
            if (isInside(mouseX, mouseY, presetShareButton(i))) {
                statusText = "Sharing " + name + "...";
                PublicPresets.upload(name,
                        code -> statusText = "Shared " + name + ", code " + code + " (/config public apply " + code + ").",
                        error -> statusText = "Could not share " + name + ": " + error + ".");
                return true;
            }
            if (isInside(mouseX, mouseY, presetLoadButton(i))) {
                ConfigPresets.Result result = ConfigPresets.load(name);
                if (result.ok()) {
                    pendingTheme = BazzarTheme.current();
                    rebuildWidgets();
                }
                statusText = result.message();
                presetNames = ConfigPresets.names();
                return true;
            }
            if (isInside(mouseX, mouseY, presetDeleteButton(i))) {
                statusText = ConfigPresets.delete(name).message();
                presetNames = ConfigPresets.names();
                return true;
            }
        }
        return false;
    }

    private int presetSaveRowY() {
        return settingsTop() + 14;
    }

    private ButtonBounds presetSaveButton() {
        return new ButtonBounds(contentRight() - 74, presetSaveRowY(), 74, 16);
    }

    private int presetListTopY() {
        return presetSaveRowY() + (panelH < 300 ? 38 : 46);
    }

    private int presetRowStep() {
        return panelH < 300 ? 18 : 20;
    }

    private int presetRowCapacity() {
        return Math.max(1, (footerLineY() - 16 - presetListTopY()) / presetRowStep());
    }

    private ButtonBounds presetLoadButton(int row) {
        return new ButtonBounds(contentRight() - 112, presetListTopY() + row * presetRowStep(), 50, 16);
    }

    private ButtonBounds presetShareButton(int row) {
        return new ButtonBounds(contentRight() - 168, presetListTopY() + row * presetRowStep(), 50, 16);
    }

    private ButtonBounds presetApplyButton(int row) {
        return new ButtonBounds(contentRight() - 56, presetListTopY() + row * presetRowStep(), 56, 16);
    }

    private ButtonBounds presetMineButton() {
        return new ButtonBounds(contentRight() - 100, presetListTopY() - 18, 46, 14);
    }

    private ButtonBounds presetPublicButton() {
        return new ButtonBounds(contentRight() - 50, presetListTopY() - 18, 50, 14);
    }

    private ButtonBounds presetDeleteButton(int row) {
        return new ButtonBounds(contentRight() - 56, presetListTopY() + row * presetRowStep(), 56, 16);
    }

    private ButtonBounds shardPayoutsButton() {
        return new ButtonBounds(contentRight() - 74, shardPayOwnerRowY(), 74, 16);
    }

    private void applyPunchSliderPosition(int mouseX) {
        ButtonBounds slider = shardPunchSliderBounds();
        int trackX = slider.x();
        int trackW = slider.w() - 52;
        float ratio = Math.max(0.0f, Math.min(1.0f, (float) (mouseX - trackX) / trackW));
        int raw = 5 + Math.round(ratio * (600 - 5));
        int snapped = Math.max(5, Math.min(600, Math.round((float) raw / 5) * 5));
        BazzarConfig.State state = BazzarConfig.load();
        if (state.autoPunchIntervalSeconds != snapped) {
            state.autoPunchIntervalSeconds = snapped;
            BazzarConfig.save(state);
            statusText = "Auto punch every " + snapped + "s.";
        }
    }

    private void drawCookiePanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();
        BazzarConfig.State state = BazzarConfig.load();

        drawSettingRow(
                graphics,
                contentX,
                settingsTop(),
                "Auto Cookie",
                "Use your best cookie while Celestial Boost is active",
                cookieAutoToggleButton(),
                state.cookieAutoEnabled,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + settingRowStep(),
                "Disable After Boost",
                "Turn the cookie off when the boost ends (else use fallback)",
                cookieDisableAfterToggleButton(),
                state.cookieAutoDisableAfterCelestial,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 2 * settingRowStep(),
                "Celestial Alert",
                "Show a title alert when Celestial Boost starts",
                cookieAlertToggleButton(),
                state.cookieCelestialStartAlertEnabled,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 3 * settingRowStep(),
                "Claim Cookies",
                "Run /claimall when your charm gifts you",
                cookieClaimToggleButton(),
                state.cookieAutoClaimAllOnGift,
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 4 * settingRowStep(),
                "Rush Cookie",
                "Use a cookie when you mine a rush",
                cookieRushToggleButton(),
                state.cookieAutoOnRush,
                mouseX,
                mouseY
        );
        drawPillButton(
                graphics,
                cookieFallbackButton(),
                "Fallback: " + CookieController.getCookieLabel(state.cookieAutoFallback) + " ▾",
                mouseX,
                mouseY,
                true,
                cookieFallbackOpen
        );
        drawPillButton(
                graphics,
                cookieRushTypeButton(),
                "Rush: " + CookieController.getCookieLabel(state.cookieRushType) + " ▾",
                mouseX,
                mouseY,
                true,
                cookieRushOpen
        );

        String lastUsed = CookieController.getLastUsedLabel();
        graphics.drawString(
                font,
                font.plainSubstrByWidth("Last used: " + (lastUsed == null || lastUsed.isBlank() ? "never" : lastUsed)
                        + "  ·  /cookie [type] works in chat too", contentW()),
                contentX,
                cookiePillRowY() + 44,
                MUTED_TEXT,
                false
        );
    }

    private void drawCookieTypeDropdown(GuiGraphics graphics, int mouseX, int mouseY,
            String[] options, String currentType, ButtonBounds button) {
        int itemH = 14;
        int listH = options.length * itemH + 4;
        int listX = button.x();
        int listY = cookieDropdownListY(button, listH);
        int listW = button.w();

        BazzarUi.fillRoundedRect(graphics, listX - 1, listY - 1, listW + 2, listH + 2, 5, ACCENT_DIM);
        BazzarUi.fillRoundedRect(graphics, listX, listY, listW, listH, 4, 0xFF10141B);

        String current = CookieController.normalizeCookieType(currentType);
        for (int i = 0; i < options.length; i++) {
            int itemY = listY + 2 + i * itemH;
            boolean hovered = isInside(mouseX, mouseY, listX + 2, itemY, listW - 4, itemH);
            boolean selected = options[i].equals(current);
            if (hovered) {
                BazzarUi.fillRoundedRect(graphics, listX + 2, itemY, listW - 4, itemH, 3, NAV_ACTIVE_BG);
            }
            int textColor = selected ? ACCENT_TEXT : (hovered ? HEADER_TEXT : MUTED_TEXT);
            graphics.drawString(font, CookieController.getCookieLabel(options[i]), listX + 8, itemY + 3, textColor, false);
            if (selected) {
                BazzarUi.fillCircle(graphics, listX + listW - 9, itemY + itemH / 2, 2, ACCENT);
            }
        }
    }

    private int cookieDropdownListY(ButtonBounds button, int listH) {
        int listY = button.y() + button.h() + 2;
        if (listY + listH > top + panelH - 4) {
            listY = button.y() - listH - 2;
        }
        return listY;
    }

    private String pickCookieTypeFromDropdown(int mouseX, int mouseY, String[] options, ButtonBounds button) {
        int itemH = 14;
        int listH = options.length * itemH + 4;
        int listX = button.x();
        int listY = cookieDropdownListY(button, listH);
        for (int i = 0; i < options.length; i++) {
            int itemY = listY + 2 + i * itemH;
            if (isInside(mouseX, mouseY, listX + 2, itemY, button.w() - 4, itemH)) {
                return options[i];
            }
        }
        return null;
    }

    private boolean handleCookieFallbackDropdownClick(int mouseX, int mouseY) {
        String picked = pickCookieTypeFromDropdown(mouseX, mouseY,
                CookieController.getFallbackCookieOptions(), cookieFallbackButton());
        if (picked != null) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieAutoFallback = picked;
            BazzarConfig.save(state);
            statusText = "Fallback cookie: " + CookieController.getCookieLabel(picked) + ".";
        }
        cookieFallbackOpen = false;
        return true;
    }

    private boolean handleCookieRushDropdownClick(int mouseX, int mouseY) {
        String picked = pickCookieTypeFromDropdown(mouseX, mouseY,
                CookieController.getRushCookieOptions(), cookieRushTypeButton());
        if (picked != null) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieRushType = picked;
            BazzarConfig.save(state);
            statusText = "Rush cookie: " + CookieController.getCookieLabel(picked) + ".";
        }
        cookieRushOpen = false;
        return true;
    }

    private boolean handleCookieClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, cookieAutoToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieAutoEnabled = !state.cookieAutoEnabled;
            BazzarConfig.save(state);
            statusText = state.cookieAutoEnabled ? "Auto cookie enabled." : "Auto cookie disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, cookieDisableAfterToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieAutoDisableAfterCelestial = !state.cookieAutoDisableAfterCelestial;
            BazzarConfig.save(state);
            statusText = state.cookieAutoDisableAfterCelestial
                    ? "Cookie will be disabled after Celestial"
                    : "Fallback cookie will be used after Celestial";
            return true;
        }

        if (isInside(mouseX, mouseY, cookieAlertToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieCelestialStartAlertEnabled = !state.cookieCelestialStartAlertEnabled;
            BazzarConfig.save(state);
            statusText = state.cookieCelestialStartAlertEnabled ? "Celestial alert enabled." : "Celestial alert disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, cookieClaimToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieAutoClaimAllOnGift = !state.cookieAutoClaimAllOnGift;
            BazzarConfig.save(state);
            statusText = state.cookieAutoClaimAllOnGift ? "Auto /claimall on gifts enabled." : "Auto /claimall on gifts disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, cookieRushToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieAutoOnRush = !state.cookieAutoOnRush;
            BazzarConfig.save(state);
            statusText = state.cookieAutoOnRush ? "Auto cookie on rush enabled." : "Auto cookie on rush disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, cookieFallbackButton())) {
            cookieFallbackOpen = true;
            cookieRushOpen = false;
            return true;
        }

        if (isInside(mouseX, mouseY, cookieRushTypeButton())) {
            cookieRushOpen = true;
            cookieFallbackOpen = false;
            return true;
        }

        return false;
    }

    private void drawHudPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int contentX = contentX();

        drawSettingRow(
                graphics,
                contentX,
                settingsTop(),
                "Bazaar HUD",
                "Show pinned bazaar price overlay on screen",
                hudBazaarToggleButton(),
                PinnedBazaarHud.isVisible(),
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + settingRowStep(),
                "Quest HUD",
                "Show guild quest command overlay on screen",
                hudQuestToggleButton(),
                QuestCommandHud.isVisible(),
                mouseX,
                mouseY
        );
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 2 * settingRowStep(),
                "Quest Auto Scan",
                "Run /gq whenever your quests refresh",
                hudQuestAutoToggleButton(),
                GuildQuestScanner.isAutoScanEnabled(),
                mouseX,
                mouseY
        );
        graphics.drawString(font, "Next Quest Scan", contentX, settingsTop() + 3 * settingRowStep(), HEADER_TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(GuildQuestScanner.nextAutoScanText(), contentW()),
                contentX, settingsTop() + 3 * settingRowStep() + 12, MUTED_TEXT, false);
        drawSettingRow(
                graphics,
                contentX,
                settingsTop() + 4 * settingRowStep(),
                "Session Profit HUD",
                "Shards & blocks this session with /hr rates",
                hudProfitToggleButton(),
                SessionProfitHud.isEnabled(),
                mouseX,
                mouseY
        );

        drawPillButton(graphics, hudQuestScanNowButton(), "Scan Quests Now", mouseX, mouseY, true, false);
        drawPillButton(graphics, hudQuestResetButton(), "Reset Quest HUD", mouseX, mouseY, true, false);

        graphics.drawString(
                font,
                "Drag the quest HUD on screen to move it. Scroll to resize.",
                contentX,
                settingsTop() + 4 * settingRowStep() + 64,
                MUTED_TEXT,
                false
        );
        graphics.drawString(
                font,
                "Pos: " + QuestCommandHud.getHudX() + ", " + QuestCommandHud.getHudY()
                        + "  |  Scale: " + String.format(Locale.ROOT, "%.2f", QuestCommandHud.getScale()),
                contentX,
                settingsTop() + 4 * settingRowStep() + 76,
                MUTED_TEXT,
                false
        );
    }

    private static final int THEME_CARD_H = 30;
    private static final int THEME_CARD_STEP = 34;

    private static final int PICKER_SIZE = 56;

    private boolean isCustomSelected() {
        BazzarTheme sel = pendingTheme != null ? pendingTheme : BazzarTheme.current();
        return sel != null && "custom".equals(sel.id);
    }

    private int themesX0() {
        return contentX() + 1;
    }

    private static final int THEME_CARD_TOP_PAD = 2;

    private int themesY0() {
        return settingsTop() + 3;
    }

    private int themesGridW() {
        return contentW() - 6;
    }

    private int themesCardW() {
        return (themesGridW() - 6) / 2;
    }

    private int themesAvailH() {
        int full = footerLineY() - 8 - themesY0();
        return isCustomSelected() ? full - (PICKER_SIZE + 14) : full;
    }

    private int pickerTop() {
        return themesY0() + themesAvailH() + 8;
    }

    private int pickerLeft() {
        return themesX0();
    }

    private int svWidth() {
        return contentW() - 14 - 8 - 100;
    }

    private int hueBarLeft() {
        return pickerLeft() + svWidth() + 8;
    }

    private int themesTotalH() {
        int rows = (BazzarTheme.ALL.size() + 1) / 2;
        return THEME_CARD_TOP_PAD + rows * THEME_CARD_STEP - (THEME_CARD_STEP - THEME_CARD_H);
    }

    private static final String[][] INFO_LINES = {
            {"Shard Auto Pay"},
            {"/setshardprice <price>", "Price per shard, e.g. /setshardprice 1T (also used by /calcshard)"},
            {"/setshardprice on|off", "Turn shard auto pay on or off (/setshardprice alone shows status)"},
            {"/setshardprice 1T on", "Set the price and turn auto pay on in one go"},
            {"Gem Auto Pay"},
            {"/setgemprice <price>", "Price you pay per gem received, also used by /calcgems"},
            {"/setgemprice on|off", "Turn gem auto pay on or off"},
            {"Cobble Auto Pay"},
            {"/setcobble <price>", "Price you pay per cobble received"},
            {"/setcobble on|off", "Turn cobble auto pay on or off"},
            {"", "Payments of 5 or fewer shards, gems or cobble are ignored"},
            {"Calculators"},
            {"/calcshard <count>", "What that many shards are worth"},
            {"/calcgems <count>", "What that many gems are worth at your gem price"},
            {"/setmulti <multi>", "Set your current multiplier"},
            {"/calcmulti <target>", "Cost to reach a target multiplier (or /calc multi)"},
            {"/calcxp <levels>", "XP needed for levels"},
            {"/calc <expression>", "Calculator, works with k/m/b/t, e.g. /calc 2.5b*4"},
            {"Other Commands"},
            {"/alts <command>", "Run a command on your alts on this PC (/alts alone shows status)"},
            {"/config list|save|set|delete <name>", "Save and load config presets"},
            {"/config public [list|view|apply|upload|delete]", "Browse public presets, or share yours with upload <name>"},
            {"/cookie <type>", "Use a cookie: auto, suspicious, cobbled, colossal, golden..."},
            {"/blacklist <crop>", "Hide a crop from the tracker, run again to unhide"},
            {"/bazzarupdate [on|off]", "Check for an update now, or turn auto update on/off"},
            {"/bazzarusers [on|off]", "Show Bazzar users online, or turn being counted on/off"},
            {"/bazzarstats [on|off]", "Leaderboard stats status, or turn them on/off"},
            {"Keybinds"},
            {"Options > Controls > Bazaar", "Crop Tracker, Farm Warps, Scan Bazaar, PV Search, Guild Quests, HUD, Chat Macros 1-8"}
    };
    private static final int INFO_HEADER_H = 16;
    private static final int INFO_ENTRY_H = 22;
    private static final int INFO_NOTE_H = 13;

    private int infoY0() {
        return settingsTop() - 4;
    }

    private int infoAvailH() {
        return footerLineY() - 6 - infoY0();
    }

    private int infoLineH(String[] line) {
        if (line.length == 1) {
            return INFO_HEADER_H;
        }
        return line[0].isEmpty() ? INFO_NOTE_H : INFO_ENTRY_H;
    }

    private int infoTotalH() {
        int total = 0;
        for (String[] line : INFO_LINES) {
            total += infoLineH(line);
        }
        return total;
    }

    private void drawInfoPanel(GuiGraphics graphics) {
        int x = contentX();
        int y0 = infoY0();
        int availH = infoAvailH();
        int textW = contentW() - 8;
        int maxScroll = Math.max(0, infoTotalH() - availH);
        infoScroll = Math.max(0, Math.min(infoScroll, maxScroll));

        graphics.enableScissor(x, y0, x + contentW(), y0 + availH);
        int y = y0 - infoScroll;
        for (String[] line : INFO_LINES) {
            int h = infoLineH(line);
            if (y + h >= y0 && y <= y0 + availH) {
                if (line.length == 1) {
                    graphics.drawString(font, line[0].toUpperCase(Locale.ROOT), x, y + 5, ACCENT_TEXT, false);
                } else if (line[0].isEmpty()) {
                    graphics.drawString(font, font.plainSubstrByWidth(line[1], textW), x + 4, y + 1, TIP_TEXT, false);
                } else {
                    graphics.drawString(font, font.plainSubstrByWidth(line[0], textW), x + 4, y, HEADER_TEXT, false);
                    graphics.drawString(font, font.plainSubstrByWidth(line[1], textW - 4), x + 8, y + 10, MUTED_TEXT, false);
                }
            }
            y += h;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(12, availH * availH / infoTotalH());
            int barY = y0 + (availH - barH) * infoScroll / maxScroll;
            int barX = contentX() + contentW() - 2;
            graphics.fill(barX, y0, barX + 2, y0 + availH, 0x22FFFFFF);
            graphics.fill(barX, barY, barX + 2, barY + barH, ACCENT_DIM);
        }
    }

    private void drawThemesPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int x0 = themesX0();
        int y0 = themesY0();
        int availH = themesAvailH();
        int maxScroll = Math.max(0, themesTotalH() - availH);
        themeScroll = Math.max(0, Math.min(themeScroll, maxScroll));
        int cardW = themesCardW();

        graphics.enableScissor(contentX(), y0, contentX() + contentW(), y0 + availH);
        for (int i = 0; i < BazzarTheme.ALL.size(); i++) {
            BazzarTheme theme = BazzarTheme.ALL.get(i);
            int cx = x0 + (i % 2) * (cardW + 6);
            int cy = y0 + THEME_CARD_TOP_PAD + (i / 2) * THEME_CARD_STEP - themeScroll;
            if (cy + THEME_CARD_H < y0 || cy > y0 + availH) {
                continue;
            }
            BazzarTheme.refreshDynamic(theme);
            boolean selected = theme == (pendingTheme != null ? pendingTheme : BazzarTheme.current());
            boolean hovered = mouseY >= y0 && mouseY < y0 + availH && isInside(mouseX, mouseY, cx, cy, cardW, THEME_CARD_H);
            int bg = selected
                    ? BazzarUi.lerpColor(0xFF14181E, theme.accent(), 0.18f)
                    : (hovered ? 0xFF232931 : 0xFF1A1F26);
            int borderColor = selected ? theme.accent() : (hovered ? 0x55FFFFFF : 0x33FFFFFF);
            BazzarUi.fillRoundedRect(graphics, cx - 1, cy - 1, cardW + 2, THEME_CARD_H + 2, 5, borderColor);
            BazzarUi.fillRoundedRect(graphics, cx, cy, cardW, THEME_CARD_H, 4, bg);

            graphics.fill(cx + 8, cy + 11, cx + 16, cy + 19, theme.dark());
            graphics.fill(cx + 16, cy + 11, cx + 24, cy + 19, theme.accent());
            graphics.fill(cx + 24, cy + 11, cx + 32, cy + 19, theme.light());

            graphics.drawString(font, font.plainSubstrByWidth(theme.name, cardW - 60), cx + 38, cy + 11, HEADER_TEXT, false);
            if (selected) {
                BazzarUi.fillCircle(graphics, cx + cardW - 12, cy + 15, 4, theme.accent());
                BazzarUi.fillCircle(graphics, cx + cardW - 12, cy + 15, 2, 0xFFFFFFFF);
            }
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(12, availH * availH / themesTotalH());
            int barY = y0 + (availH - barH) * themeScroll / maxScroll;
            int barX = contentX() + contentW() - 2;
            graphics.fill(barX, y0, barX + 2, y0 + availH, 0x22FFFFFF);
            graphics.fill(barX, barY, barX + 2, barY + barH, ACCENT_DIM);
        }

        if (isCustomSelected()) {
            drawColorPicker(graphics);
        }
    }

    private void drawColorPicker(GuiGraphics graphics) {
        int px = pickerLeft();
        int py = pickerTop();
        int size = PICKER_SIZE;
        int svW = svWidth();
        int rgb = BazzarConfig.load().customThemeRgb & 0xFFFFFF;
        float[] hsv = rgbToHsv(rgb);
        float hue = customHue;

        BazzarUi.fillRoundedRect(graphics, px - 1, py - 1, svW + 2, size + 2, 4, 0x66FFFFFF);
        for (int col = 0; col < svW; col++) {
            float s = (float) col / (svW - 1);
            int topColor = 0xFF000000 | hsvToRgb(hue, s, 1f);
            graphics.fillGradient(px + col, py, px + col + 1, py + size, topColor, 0xFF000000);
        }

        int svx = px + Math.round(hsv[1] * (svW - 1));
        int svy = py + Math.round((1f - hsv[2]) * (size - 1));
        BazzarUi.fillCircle(graphics, svx, svy, 4, 0xFF000000);
        BazzarUi.fillCircle(graphics, svx, svy, 3, 0xFFFFFFFF);

        int hx = hueBarLeft();
        BazzarUi.fillRoundedRect(graphics, hx - 1, py - 1, 14, size + 2, 4, 0x66FFFFFF);
        for (int row = 0; row < size; row++) {
            float h = (float) row / (size - 1);
            graphics.fill(hx, py + row, hx + 12, py + row + 1, 0xFF000000 | hsvToRgb(h, 1f, 1f));
        }
        int hueY = py + Math.round(hue * (size - 1));
        graphics.fill(hx - 2, hueY - 1, hx + 14, hueY + 1, 0xFFFFFFFF);
        graphics.fill(hx - 2, hueY, hx + 14, hueY + 1, 0xFF000000);

        int previewX = hueBarLeft() + 24;
        graphics.drawString(font, "Custom", previewX, py + 2, MUTED_TEXT, false);
        BazzarUi.fillRoundedRect(graphics, previewX + 46, py, 20, 16, 3, 0xFF000000 | rgb);
    }

    private void applySvPosition(int mouseX, int mouseY) {
        int px = pickerLeft();
        int py = pickerTop();
        int size = PICKER_SIZE;
        float s = clamp01((float) (mouseX - px) / (svWidth() - 1));
        float v = clamp01(1f - (float) (mouseY - py) / (size - 1));
        setCustomColor(hsvToRgb(customHue, s, v));
    }

    private void applyHuePosition(int mouseY) {
        int py = pickerTop();
        int size = PICKER_SIZE;
        customHue = clamp01((float) (mouseY - py) / (size - 1));
        float[] hsv = rgbToHsv(BazzarConfig.load().customThemeRgb & 0xFFFFFF);
        setCustomColor(hsvToRgb(customHue, hsv[1], hsv[2]));
    }

    private void setCustomColor(int rgb) {
        rgb &= 0xFFFFFF;
        BazzarConfig.State state = BazzarConfig.load();
        state.customThemeRgb = rgb;
        BazzarConfig.save(state);
        if (customHexBox != null) {
            suppressHexHue = true;
            customHexBox.setValue(String.format(Locale.ROOT, "#%06X", rgb));
            suppressHexHue = false;
        }
        pendingTheme = BazzarTheme.byId("custom");
        statusText = String.format(Locale.ROOT, "Custom color #%06X, press Save & Apply.", rgb);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float rgbToHue(int rgb) {
        return rgbToHsv(rgb)[0];
    }

    private static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = 0f;
        if (d > 0.00001f) {
            if (max == r) {
                h = ((g - b) / d) % 6f;
            } else if (max == g) {
                h = (b - r) / d + 2f;
            } else {
                h = (r - g) / d + 4f;
            }
            h /= 6f;
            if (h < 0f) {
                h += 1f;
            }
        }
        float s = max <= 0f ? 0f : d / max;
        return new float[]{h, s, max};
    }

    private static int hsvToRgb(float h, float s, float v) {
        h = (h % 1f + 1f) % 1f;
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    private boolean handleThemesClick(int mouseX, int mouseY) {
        int x0 = themesX0();
        int y0 = themesY0();
        int availH = themesAvailH();
        if (mouseY < y0 || mouseY >= y0 + availH) {
            return false;
        }
        int cardW = themesCardW();
        for (int i = 0; i < BazzarTheme.ALL.size(); i++) {
            int cx = x0 + (i % 2) * (cardW + 6);
            int cy = y0 + THEME_CARD_TOP_PAD + (i / 2) * THEME_CARD_STEP - themeScroll;
            if (isInside(mouseX, mouseY, cx, cy, cardW, THEME_CARD_H)) {
                pendingTheme = BazzarTheme.ALL.get(i);
                statusText = "Theme selected: " + pendingTheme.name + ", press Save & Apply.";
                return true;
            }
        }
        return false;
    }

    private boolean handlePickerClick(int mouseX, int mouseY) {
        if (!isCustomSelected()) {
            return false;
        }
        int px = pickerLeft();
        int py = pickerTop();
        int size = PICKER_SIZE;
        if (isInside(mouseX, mouseY, px, py, svWidth(), size)) {
            svDragging = true;
            applySvPosition(mouseX, mouseY);
            return true;
        }
        int hx = hueBarLeft();
        if (isInside(mouseX, mouseY, hx, py, 12, size)) {
            hueDragging = true;
            applyHuePosition(mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeMainTab == MainTab.THEMES) {
            int maxScroll = Math.max(0, themesTotalH() - themesAvailH());
            themeScroll = Math.max(0, Math.min((int) (themeScroll - scrollY * 18.0d), maxScroll));
            return true;
        }
        if (activeMainTab == MainTab.INFO) {
            int maxScroll = Math.max(0, infoTotalH() - infoAvailH());
            infoScroll = Math.max(0, Math.min((int) (infoScroll - scrollY * 18.0d), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void drawFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(contentX(), footerLineY(), contentRight(), footerLineY() + 1, PANEL_BORDER);
        int statusMaxW = resetButton().x() - 12 - contentX();
        if (statusMaxW > 0) {
            String status = statusText;
            if (font.width(status) > statusMaxW) {
                String cut = font.plainSubstrByWidth(status, statusMaxW - font.width("."));
                int lastSpace = cut.lastIndexOf(' ');
                if (lastSpace > 0) {
                    cut = cut.substring(0, lastSpace);
                }
                status = cut.replaceAll("[\\s.,]+$", "") + ".";
            }
            graphics.drawString(font, status, contentX(), footerButtonY() + 5, MUTED_TEXT, false);
        }
        drawFooterButton(graphics, resetButton(), "Reset", mouseX, mouseY, false);
        drawFooterButton(graphics, saveButton(), "Save & Apply", mouseX, mouseY, true);
    }

    private void saveAndClose() {
        if (pendingTheme != null && pendingTheme != BazzarTheme.current()) {
            BazzarTheme.select(pendingTheme);
        }
        persistTriggerSlots();
        BazzarConfig.State state = BazzarConfig.load();
        state.autoEggEnabled = AutoEggController.isEnabled();
        BazzarConfig.save(state);
        Minecraft.getInstance().setScreen(null);
        statusText = "Settings saved.";
    }

    private void resetCurrentTab() {
        switch (activeMainTab) {
            case AUTO_SCAN -> {
                BazaarAutoScanController.setAutoScanEnabled(false);
                BazaarAutoScanController.setAutoScanIntervalSeconds(120);
                statusText = "Auto scan settings reset.";
            }
            case AUTO_EGG -> {
                AutoEggController.setEnabled(false);
                BazzarConfig.State state = BazzarConfig.load();
                state.autoEggEnabled = false;
                state.cookieAfkFailsafeEnabled = true;
                state.scoreboardGlowEnabled = true;
                state.profitHudEnabled = true;
                state.playtimeClaimEnabled = true;
                state.celestialClaimEnabled = true;
                state.autoDumpEnabled = false;
                state.autoDumpPvPage = 1;
                BazzarConfig.save(state);
                statusText = "Misc settings reset.";
            }
            case HUD -> {
                QuestCommandHud.resetPositionAndScale();
                QuestCommandHud.setVisible(true);
                GuildQuestScanner.setAutoScanEnabled(false);
                statusText = "HUD settings reset.";
            }
            case TRACKER -> {
                for (RowEntry row : rows) {
                    if (row.blacklisted()) {
                        BazaarFlipScanner.setCropBlacklisted(row.blacklistToken(), false);
                    }
                }
                reloadRows();
                reloadGemFlips();
                statusText = "Crop blacklist cleared.";
            }
            case GEM_FLIPS -> {
                gemPage = 0;
                reloadGemFlips();
                statusText = "Gem flips view reset.";
            }
            case AUTO_CMD -> {
                for (int i = 0; i < ChatCommandTriggerController.SLOT_COUNT; i++) {
                    ChatCommandTriggerController.setTriggerMessage(i, "");
                    ChatCommandTriggerController.setCommandToSend(i, "");
                    if (triggerBoxes[i] != null) {
                        triggerBoxes[i].setValue("");
                    }
                    if (commandBoxes[i] != null) {
                        commandBoxes[i].setValue("");
                    }
                }
                persistTriggerSlots();
                BazzarConfig.State cmdState = BazzarConfig.load();
                for (int i = 0; i < cmdState.chatTriggerSelfOnly.size(); i++) {
                    cmdState.chatTriggerSelfOnly.set(i, false);
                }
                BazzarConfig.save(cmdState);
                statusText = "All trigger slots cleared.";
            }
            case SHARD_PAY -> {
                ShardPayController.setEnabled(false);
                ShardPayController.setOwner("");
                for (int i = 0; i < ShardPayController.MEMBER_SLOT_COUNT; i++) {
                    ShardPayController.setMember(i, "");
                    if (shardPayMemberBoxes[i] != null) {
                        shardPayMemberBoxes[i].setValue("");
                    }
                }
                if (shardPayOwnerBox != null) {
                    shardPayOwnerBox.setValue("");
                }
                persistShardPaySettings();
                BazzarConfig.State punchState = BazzarConfig.load();
                punchState.autoPunchEnabled = false;
                punchState.autoPunchIntervalSeconds = 30;
                punchState.cobblePayEnabled = false;
                punchState.gemPayEnabled = false;
                BazzarConfig.save(punchState);
                statusText = "Shard pay settings cleared.";
            }
            case COOKIE -> {
                BazzarConfig.State state = BazzarConfig.load();
                state.cookieAutoEnabled = false;
                state.cookieAutoDisableAfterCelestial = false;
                state.cookieAutoClaimAllOnGift = false;
                state.cookieCelestialStartAlertEnabled = false;
                state.cookieAutoFallback = "cobbled";
                state.cookieAutoOnRush = false;
                state.cookieRushType = "suspicious";
                BazzarConfig.save(state);
                statusText = "Cookie settings reset.";
            }
            case MACROS -> {
                BazzarConfig.State state = BazzarConfig.load();
                for (int i = 0; i < MacroController.KEY_MACRO_COUNT; i++) {
                    state.macroKeyMessages.set(i, "");
                    if (macroBoxes[i] != null) {
                        macroBoxes[i].setValue("");
                    }
                }
                state.macroTimerEnabled = false;
                state.macroTimerMessage = "";
                state.macroTimerIntervalSeconds = 60;
                BazzarConfig.save(state);
                if (macroTimerBox != null) {
                    macroTimerBox.setValue("");
                }
                statusText = "Macros reset.";
            }
            case THEMES -> {
                pendingTheme = BazzarTheme.defaultTheme();
                statusText = "Theme reset to " + pendingTheme.name + ", press Save & Apply.";
            }
        }
    }

    private void startManualScanAndClose() {
        Minecraft client = Minecraft.getInstance();
        if (activeMainTab == MainTab.GEM_FLIPS) {
            BazaarAutoScanController.triggerManual(client, BazaarAutoScanController.ScanMode.GEMS);
        } else {
            BazaarAutoScanController.triggerManual(client, BazaarAutoScanController.ScanMode.CROPS);
        }
        if (client.player != null) {
            String tabName = activeMainTab == MainTab.GEM_FLIPS ? "Gem Flips" : "Tracker";
            client.player.displayClientMessage(
                    Component.literal("[Bazaar] Started bazaar scan for " + tabName + ". Reopen the tracker when it finishes.")
                            .withStyle(ChatFormatting.AQUA),
                    false
            );
        }
        client.setScreen(null);
    }

    private boolean handleAutoScanClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, autoScanToggleButton())) {
            boolean enabled = !BazaarAutoScanController.isAutoScanEnabled();
            BazaarAutoScanController.setAutoScanEnabled(enabled);
            statusText = enabled ? "Automatic scan enabled." : "Automatic scan disabled.";
            return true;
        }

        ButtonBounds slider = autoIntervalSliderBounds();
        if (isInside(mouseX, mouseY, slider.x(), slider.y(), slider.w(), slider.h())) {
            sliderDragging = true;
            applySliderPosition(mouseX);
            return true;
        }

        if (isInside(mouseX, mouseY, autoScanNowButton())) {
            startManualScanAndClose();
            return true;
        }

        return false;
    }

    private boolean handleHudClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, hudBazaarToggleButton())) {
            PinnedBazaarHud.toggleVisibility();
            statusText = PinnedBazaarHud.isVisible() ? "Bazaar HUD enabled." : "Bazaar HUD disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, hudQuestToggleButton())) {
            boolean newValue = !QuestCommandHud.isVisible();
            QuestCommandHud.setVisible(newValue);
            statusText = newValue ? "Quest HUD enabled." : "Quest HUD disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, hudQuestAutoToggleButton())) {
            boolean enabled = !GuildQuestScanner.isAutoScanEnabled();
            GuildQuestScanner.setAutoScanEnabled(enabled);
            statusText = enabled ? "Quest auto scan enabled." : "Quest auto scan disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, hudProfitToggleButton())) {
            boolean enabled = !SessionProfitHud.isEnabled();
            SessionProfitHud.setEnabled(enabled);
            statusText = enabled ? "Session profit HUD enabled." : "Session profit HUD disabled.";
            return true;
        }

        if (isInside(mouseX, mouseY, hudQuestScanNowButton())) {
            GuildQuestScanner.triggerManual(Minecraft.getInstance());
            statusText = "Manual /gq scan started.";
            return true;
        }

        if (isInside(mouseX, mouseY, hudQuestResetButton())) {
            QuestCommandHud.resetPositionAndScale();
            statusText = "Quest HUD position and scale reset.";
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (sliderDragging && activeMainTab == MainTab.AUTO_SCAN) {
            applySliderPosition((int) event.x());
            return true;
        }
        if (punchSliderDragging && activeMainTab == MainTab.SHARD_PAY) {
            applyPunchSliderPosition((int) event.x());
            return true;
        }
        if (dumpSliderDragging && activeMainTab == MainTab.AUTO_EGG) {
            applyDumpSliderPosition((int) event.x());
            return true;
        }
        if (svDragging && activeMainTab == MainTab.THEMES) {
            applySvPosition((int) event.x(), (int) event.y());
            return true;
        }
        if (hueDragging && activeMainTab == MainTab.THEMES) {
            applyHuePosition((int) event.y());
            return true;
        }
        if (macroSliderDragging && activeMainTab == MainTab.MACROS) {
            applyMacroSliderPosition((int) event.x());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        sliderDragging = false;
        punchSliderDragging = false;
        dumpSliderDragging = false;
        svDragging = false;
        hueDragging = false;
        macroSliderDragging = false;
        return super.mouseReleased(event);
    }

    private void applySliderPosition(int mouseX) {
        ButtonBounds slider = autoIntervalSliderBounds();
        int trackX = slider.x();
        int trackW = slider.w() - 52;
        float ratio = Math.max(0.0f, Math.min(1.0f, (float) (mouseX - trackX) / trackW));
        int range = SLIDER_MAX_SECONDS - SLIDER_MIN_SECONDS;
        int raw = SLIDER_MIN_SECONDS + Math.round(ratio * range);

        int snapped = Math.max(SLIDER_MIN_SECONDS, Math.min(SLIDER_MAX_SECONDS,
                Math.round((float) raw / AUTO_INTERVAL_STEP_SECONDS) * AUTO_INTERVAL_STEP_SECONDS));
        int current = BazaarAutoScanController.getAutoScanIntervalSeconds();
        if (snapped != current) {
            BazaarAutoScanController.adjustAutoScanIntervalSeconds(snapped - current);
            statusText = "Auto scan every " + BazaarAutoScanController.getAutoScanIntervalSeconds() + "s.";
        }
    }

    private void drawSettingRow(
            GuiGraphics graphics,
            int x,
            int y,
            String title,
            String description,
            ButtonBounds toggleBounds,
            boolean enabled,
            int mouseX,
            int mouseY
    ) {
        int textMaxW = Math.max(0, toggleBounds.x() - 10 - x);
        graphics.drawString(font, font.plainSubstrByWidth(title, textMaxW), x, y, HEADER_TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(description, textMaxW), x, y + 12, MUTED_TEXT, false);
        boolean hovered = isInside(mouseX, mouseY, toggleBounds);
        drawToggle(graphics, title, toggleBounds.x(), toggleBounds.y(), toggleBounds.w(), toggleBounds.h(), enabled, hovered);
    }

    private void drawSliderSettingRow(
            GuiGraphics graphics,
            int x,
            int y,
            String title,
            String description,
            ButtonBounds sliderBounds,
            int value,
            int min,
            int max,
            String valueLabel
    ) {
        int textMaxW = Math.max(0, sliderBounds.x() - 10 - x);
        graphics.drawString(font, font.plainSubstrByWidth(title, textMaxW), x, y, HEADER_TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(description, textMaxW), x, y + 12, MUTED_TEXT, false);
        drawGradientSlider(graphics, sliderBounds, value, min, max, valueLabel);
    }

    private void drawToggle(GuiGraphics graphics, String key, int x, int y, int w, int h, boolean on, boolean hovered) {
        float target = on ? 1.0f : 0.0f;
        Float prev = toggleAnim.get(key);
        float p;
        if (prev == null) {
            p = target;
        } else {
            p = prev + (target - prev) * Math.min(1.0f, animDelta * 14.0f);
            if (Math.abs(target - p) < 0.01f) {
                p = target;
            }
        }
        toggleAnim.put(key, p);

        int border = BazzarUi.lerpColor(TOGGLE_BORDER_OFF, TOGGLE_BORDER_ON, p);
        if (hovered) {
            border = BazzarUi.lerpColor(border, 0xFFFFFFFF, 0.25f);
        }
        BazzarUi.fillPillSmooth(graphics, x - 1, y - 1, w + 2, h + 2, border);
        BazzarUi.fillPillSmooth(graphics, x, y, w, h, BazzarUi.lerpColor(TOGGLE_TRACK_OFF, TOGGLE_ON, p));

        float knobR = (h - 4) / 2.0f;
        float knobCx = x + 2 + knobR + p * (w - 4 - 2 * knobR);
        float knobCy = y + h / 2.0f;
        BazzarUi.fillCircleSmooth(graphics, knobCx, knobCy + 0.5f, knobR, 0x40000000);
        BazzarUi.fillCircleSmooth(graphics, knobCx, knobCy, knobR, TOGGLE_KNOB);
    }

    private void drawGradientSlider(GuiGraphics graphics, ButtonBounds slider, int value, int min, int max, String label) {
        int sx = slider.x();
        int sy = slider.y();
        int sw = slider.w();
        int sh = slider.h();
        int trackX = sx;
        int trackY = sy + sh / 2 - 2;
        int trackW = sw - 52;
        int trackH = 4;
        float ratio = max <= min ? 0.0f : (float) (value - min) / (max - min);
        ratio = Math.max(0.0f, Math.min(1.0f, ratio));

        for (int i = 0; i < trackW; i++) {
            float t = trackW <= 1 ? 0.0f : (float) i / (trackW - 1);
            graphics.fill(trackX + i, trackY, trackX + i + 1, trackY + trackH, BazzarUi.lerpColor(SLIDER_LEFT, SLIDER_RIGHT, t));
        }

        int handleX = trackX + Math.round(ratio * Math.max(0, trackW - 1));
        float handleCy = trackY + trackH / 2.0f;
        BazzarUi.fillCircleSmooth(graphics, handleX, handleCy + 0.5f, 4.5f, 0x40000000);
        BazzarUi.fillCircleSmooth(graphics, handleX, handleCy, 4.5f, SLIDER_HANDLE);

        int pillW = 44;
        int pillX = sx + sw - pillW;
        BazzarUi.fillPill(graphics, pillX - 1, sy - 1, pillW + 2, sh + 2, ACCENT_DIM);
        BazzarUi.fillPill(graphics, pillX, sy, pillW, sh, PILL_BG);
        graphics.drawCenteredString(font, label, pillX + pillW / 2, sy + (sh - 8) / 2, ACCENT_TEXT);
    }

    private void drawPrimaryPillButton(GuiGraphics graphics, ButtonBounds button, String text, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, button);
        if (hovered) {
            BazzarUi.fillPill(graphics, button.x() - 2, button.y() - 2, button.w() + 4, button.h() + 4, (0x55 << 24) | (ACCENT & 0xFFFFFF));
        }
        BazzarUi.fillPill(graphics, button.x(), button.y(), button.w(), button.h(), hovered ? SAVE_HOVER : ACCENT);
        graphics.drawString(font, text, button.x() + (button.w() - font.width(text)) / 2, button.y() + 4, ON_ACCENT_TEXT, false);
    }

    private void drawPillButton(
            GuiGraphics graphics,
            ButtonBounds button,
            String text,
            int mouseX,
            int mouseY,
            boolean enabled,
            boolean selected
    ) {
        boolean hovered = isInside(mouseX, mouseY, button);
        int bg = selected ? BUTTON_BG_ACTIVE : (hovered && enabled ? BUTTON_BG_HOVER : BUTTON_BG);
        int textColor = enabled ? (selected ? ACCENT_TEXT : HEADER_TEXT) : TAB_INACTIVE;
        if (selected) {
            BazzarUi.fillPill(graphics, button.x() - 1, button.y() - 1, button.w() + 2, button.h() + 2, ACCENT_DIM);
        }
        BazzarUi.fillPill(graphics, button.x(), button.y(), button.w(), button.h(), bg);
        graphics.drawCenteredString(font, text, button.x() + button.w() / 2, button.y() + 4, textColor);
    }

    private void drawFooterButton(
            GuiGraphics graphics,
            ButtonBounds button,
            String text,
            int mouseX,
            int mouseY,
            boolean primary
    ) {
        boolean hovered = isInside(mouseX, mouseY, button);
        int bg;
        int textColor;
        if (primary) {
            bg = hovered ? SAVE_HOVER : SAVE_BG;
            textColor = SAVE_TEXT;
        } else {
            bg = hovered ? RESET_HOVER : RESET_BG;
            textColor = HEADER_TEXT;
        }
        if (primary && hovered) {
            BazzarUi.fillPill(graphics, button.x() - 2, button.y() - 2, button.w() + 4, button.h() + 4, (0x55 << 24) | (ACCENT & 0xFFFFFF));
        }
        BazzarUi.fillPill(graphics, button.x(), button.y(), button.w(), button.h(), bg);
        graphics.drawString(font, text, button.x() + (button.w() - font.width(text)) / 2, button.y() + 5, textColor, !primary);
    }

    private boolean handleAutoEggClick(int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, autoEggToggleButton())) {
            boolean enabled = !AutoEggController.isEnabled();
            AutoEggController.setEnabled(enabled);
            BazzarConfig.State state = BazzarConfig.load();
            state.autoEggEnabled = enabled;
            BazzarConfig.save(state);
            statusText = enabled ? "Auto Egg enabled." : "Auto Egg disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, shadyFinderToggleButton())) {
            boolean enabled = !ShadySummonerFinder.isEnabled();
            ShadySummonerFinder.setEnabled(enabled);
            BazzarConfig.State state = BazzarConfig.load();
            state.shadyFinderEnabled = enabled;
            BazzarConfig.save(state);
            statusText = enabled ? "Shady Summoner finder enabled." : "Shady Summoner finder disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, miscAfkToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.cookieAfkFailsafeEnabled = !state.cookieAfkFailsafeEnabled;
            BazzarConfig.save(state);
            statusText = state.cookieAfkFailsafeEnabled ? "AFK failsafe enabled." : "AFK failsafe disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, miscPlaytimeToggleButton())) {
            boolean enabled = !PlaytimeRewardsController.isEnabled();
            PlaytimeRewardsController.setEnabled(enabled);
            statusText = enabled ? "Auto playtime rewards enabled." : "Auto playtime rewards disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, miscCelestialToggleButton())) {
            boolean enabled = !CelestialRewardsController.isEnabled();
            CelestialRewardsController.setEnabled(enabled);
            statusText = enabled ? "Auto celestial reward enabled." : "Auto celestial reward disabled.";
            return true;
        }
        if (isInside(mouseX, mouseY, miscDumpToggleButton())) {
            BazzarConfig.State state = BazzarConfig.load();
            state.autoDumpEnabled = !state.autoDumpEnabled;
            BazzarConfig.save(state);
            statusText = state.autoDumpEnabled
                    ? "Auto PV dump enabled (into /pv " + AutoDumpController.clampPage(state.autoDumpPvPage) + ")."
                    : "Auto PV dump disabled.";
            return true;
        }
        ButtonBounds dumpSlider = miscDumpSliderBounds();
        if (isInside(mouseX, mouseY, dumpSlider.x(), dumpSlider.y(), dumpSlider.w(), dumpSlider.h())) {
            dumpSliderDragging = true;
            applyDumpSliderPosition(mouseX);
            return true;
        }
        return false;
    }

    private void applyDumpSliderPosition(int mouseX) {
        ButtonBounds slider = miscDumpSliderBounds();
        int trackX = slider.x();
        int trackW = slider.w() - 52;
        float ratio = Math.max(0.0f, Math.min(1.0f, (float) (mouseX - trackX) / trackW));
        int snapped = 1 + Math.round(ratio * 49);
        BazzarConfig.State state = BazzarConfig.load();
        if (state.autoDumpPvPage != snapped) {
            state.autoDumpPvPage = snapped;
            BazzarConfig.save(state);
            statusText = "Auto dump target: /pv " + snapped + ".";
        }
    }

    private void reloadGemFlips() {
        List<GemFlipEntry> rebuilt = new ArrayList<>();
        for (BazaarFlipScanner.FlipCandidate candidate : BazaarFlipScanner.getLastGemsScan()) {
            if (candidate == null) {
                continue;
            }
            if (BazaarFlipScanner.isBlacklisted(candidate.name(), candidate.itemId())) {
                continue;
            }
            if (candidate.buy() == null && candidate.sell() == null) {
                continue;
            }
            double average = averagePrice(candidate.buy(), candidate.sell());
            Double spread = (candidate.buy() != null && candidate.sell() != null)
                    ? candidate.sell() - candidate.buy()
                    : null;
            rebuilt.add(new GemFlipEntry(
                    candidate.name(),
                    candidate.itemId(),
                    candidate.buy(),
                    candidate.sell(),
                    average,
                    spread,
                    candidate.marketRate(),
                    candidate.stock()
            ));
        }

        double globalAverage = rebuilt.stream()
                .mapToDouble(GemFlipEntry::average)
                .average()
                .orElse(0.0d);

        gemFlips.clear();
        gemFlips.addAll(rebuilt);

        applyGemSort();

        gemOverviewText = gemFlips.isEmpty()
                ? "Scan to calculate best gem flips."
                : "Avg scanned spawner price: " + formatPrice(globalAverage);
        gemPage = Math.max(0, Math.min(gemPage, gemPageCount() - 1));
    }

    private void applyGemSort() {
        Comparator<GemFlipEntry> comparator = switch (activeGemSortTab) {
            case HIGHEST_SELL -> Comparator
                    .comparing((GemFlipEntry row) -> row.sell() != null ? row.sell() : Double.NEGATIVE_INFINITY)
                    .reversed();
            case LOWEST_BUY -> Comparator
                    .comparing((GemFlipEntry row) -> row.buy() != null ? row.buy() : Double.POSITIVE_INFINITY);
        };
        gemFlips.sort(comparator.thenComparing(GemFlipEntry::displayName, String.CASE_INSENSITIVE_ORDER));
    }

    private static double averagePrice(Double buy, Double sell) {
        if (buy != null && sell != null) {
            return (buy + sell) / 2.0d;
        }
        if (buy != null) {
            return buy;
        }
        if (sell != null) {
            return sell;
        }
        return 0.0d;
    }

    private static String formatStock(Integer stock) {
        if (stock == null) {
            return "";
        }
        if (stock >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm", stock / 1_000_000d);
        }
        if (stock >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", stock / 1_000d);
        }
        return String.valueOf(stock);
    }

    private void reloadRows() {
        Map<String, RowEntry> merged = new LinkedHashMap<>();
        for (String crop : DEFAULT_CROPS) {
            String key = normalizeToken(crop);
            if (key.isBlank()) {
                continue;
            }
            merged.put(key, new RowEntry(crop, crop, null, null, null, false));
        }

        for (BazaarFlipScanner.FlipCandidate candidate : BazaarFlipScanner.getLastCropsScan()) {
            if (candidate == null) {
                continue;
            }
            String key = normalizeToken(candidate.itemId());
            if (key.isBlank()) {
                key = normalizeToken(candidate.name());
            }
            if (key.isBlank()) {
                continue;
            }
            String displayName = candidate.name() == null || candidate.name().isBlank()
                    ? prettifyKey(key)
                    : candidate.name();
            String token = (candidate.itemId() != null && !candidate.itemId().isBlank())
                    ? candidate.itemId()
                    : displayName;
            merged.put(key, new RowEntry(displayName, token, candidate.buy(), candidate.sell(), candidate.marketRate(), false));
        }

        Set<String> blacklist = BazaarFlipScanner.getBlacklistEntries();
        for (String blocked : blacklist) {
            if (blocked == null || blocked.isBlank()) {
                continue;
            }
            String key = normalizeToken(blocked);
            if (key.isBlank() || merged.containsKey(key)) {
                continue;
            }
            merged.put(key, new RowEntry(prettifyKey(blocked), blocked, null, null, null, false));
        }

        Map<String, RowEntry> deduped = new LinkedHashMap<>();
        for (RowEntry row : merged.values()) {
            String displayKey = normalizeToken(row.displayName());
            if (displayKey.isBlank()) {
                displayKey = normalizeToken(row.blacklistToken());
            }
            if (displayKey.isBlank()) {
                continue;
            }
            RowEntry existing = deduped.get(displayKey);
            deduped.put(displayKey, choosePreferred(existing, row));
        }

        rows.clear();
        for (RowEntry row : deduped.values()) {
            boolean blacklisted = BazaarFlipScanner.isBlacklisted(row.displayName(), row.blacklistToken());
            rows.add(new RowEntry(row.displayName(), row.blacklistToken(), row.buy(), row.sell(), row.marketRate(), blacklisted));
        }
        applySort();
        page = Math.max(0, Math.min(page, pageCount() - 1));
    }

    private RowEntry getRowAt(int mouseX, int mouseY) {
        int listX = contentX();
        int listY = listTop();
        int listWidth = contentW();
        if (!isInside(mouseX, mouseY, listX, listY, listWidth, rowsPerPage() * ROW_HEIGHT)) {
            return null;
        }
        int rowOffset = (mouseY - listY) / ROW_HEIGHT;
        int index = page * rowsPerPage() + rowOffset;
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        return rows.get(index);
    }

    private GemFlipEntry getGemFlipRowAt(int mouseX, int mouseY) {
        int listX = contentX();
        int listY = listTop();
        int listWidth = contentW();
        if (!isInside(mouseX, mouseY, listX, listY, listWidth, gemRowsPerPage() * ROW_HEIGHT)) {
            return null;
        }
        int rowOffset = (mouseY - listY) / ROW_HEIGHT;
        int index = gemPage * gemRowsPerPage() + rowOffset;
        if (index < 0 || index >= gemFlips.size()) {
            return null;
        }
        return gemFlips.get(index);
    }

    private int rowsPerPage() {
        return Math.max(1, (panelH - 108) / ROW_HEIGHT);
    }

    private int gemRowsPerPage() {

        return Math.max(1, rowsPerPage() - 1);
    }

    private int pageCount() {
        int rowsPerPage = rowsPerPage();
        if (rowsPerPage <= 0) {
            return 1;
        }
        return Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
    }

    private int gemPageCount() {
        int rowsPerPage = gemRowsPerPage();
        if (rowsPerPage <= 0) {
            return 1;
        }
        return Math.max(1, (gemFlips.size() + rowsPerPage - 1) / rowsPerPage);
    }

    private ButtonBounds sidebarItemBounds(int index) {
        int step = sidebarItemStep();
        int y = top + 36 + sidebarGroupsThrough(index) * sidebarHeaderH() + index * step;
        return new ButtonBounds(left + 6, y, SIDEBAR_W - 12, Math.min(16, step - 1));
    }

    private int sidebarItemStep() {
        int available = sidebarAvailableH() - sidebarGroupsThrough(MainTab.values().length - 1) * sidebarHeaderH();
        return Math.min(19, Math.max(12, available / MainTab.values().length));
    }

    private int sidebarAvailableH() {
        return panelH - 36 - 6;
    }

    private int sidebarHeaderH() {
        int groups = sidebarGroupsThrough(MainTab.values().length - 1);
        int roomForLabels = sidebarAvailableH() - groups * SIDEBAR_GROUP_LABEL_H;
        return roomForLabels >= MainTab.values().length * 15 ? SIDEBAR_GROUP_LABEL_H : 3;
    }

    private boolean startsSidebarGroup(int index) {
        MainTab[] tabs = MainTab.values();
        return index == 0 || !tabs[index].group().equals(tabs[index - 1].group());
    }

    private int sidebarGroupsThrough(int index) {
        int groups = 0;
        for (int i = 0; i <= index; i++) {
            if (startsSidebarGroup(i)) {
                groups++;
            }
        }
        return groups;
    }

    private ButtonBounds scanButton() {
        return new ButtonBounds(contentX(), toolbarY(), 52, 16);
    }

    private ButtonBounds refreshButton() {
        return new ButtonBounds(contentX() + 56, toolbarY(), 58, 16);
    }

    private ButtonBounds sellSortButton() {
        return new ButtonBounds(contentX() + 118, toolbarY(), 72, 16);
    }

    private ButtonBounds buySortButton() {
        return new ButtonBounds(contentX() + 194, toolbarY(), 68, 16);
    }

    private ButtonBounds gemSellSortButton() {
        return new ButtonBounds(contentX() + 118, toolbarY(), 72, 16);
    }

    private ButtonBounds gemBuySortButton() {
        return new ButtonBounds(contentX() + 194, toolbarY(), 68, 16);
    }

    private ButtonBounds prevButton() {
        return new ButtonBounds(contentRight() - 88, toolbarY(), 20, 16);
    }

    private ButtonBounds nextButton() {
        return new ButtonBounds(contentRight() - 20, toolbarY(), 20, 16);
    }

    private ButtonBounds autoScanToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3, 32, 14);
    }

    private ButtonBounds autoEggToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3, 32, 14);
    }

    private ButtonBounds shadyFinderToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + miscRowStep() + 3, 32, 14);
    }

    private ButtonBounds miscAfkToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 2 * miscRowStep() + 3, 32, 14);
    }

    private ButtonBounds miscPlaytimeToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3 * miscRowStep() + 3, 32, 14);
    }

    private ButtonBounds miscDumpToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 4 * miscRowStep() + 3, 32, 14);
    }

    private ButtonBounds miscCelestialToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 5 * miscRowStep() + 3, 32, 14);
    }

    private ButtonBounds miscDumpSliderBounds() {
        int width = Math.min(150, contentW() - 120);
        return new ButtonBounds(contentRight() - 40 - width, settingsTop() + 4 * miscRowStep(), width, 16);
    }

    private ButtonBounds autoIntervalSliderBounds() {
        return new ButtonBounds(contentRight() - 200, settingsTop() + settingRowStep() - 2, 200, 16);
    }

    private ButtonBounds autoScanNowButton() {
        return new ButtonBounds(contentX(), settingsTop() + 84, 80, 16);
    }

    private ButtonBounds resetButton() {
        return new ButtonBounds(contentRight() - 174, footerButtonY(), 64, 18);
    }

    private ButtonBounds saveButton() {
        return new ButtonBounds(contentRight() - 104, footerButtonY(), 104, 18);
    }

    private ButtonBounds hudBazaarToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3, 32, 14);
    }

    private ButtonBounds hudQuestToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + settingRowStep() + 3, 32, 14);
    }

    private ButtonBounds hudQuestAutoToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 2 * settingRowStep() + 3, 32, 14);
    }

    private ButtonBounds hudProfitToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 4 * settingRowStep() + 3, 32, 14);
    }

    private ButtonBounds hudQuestScanNowButton() {
        return new ButtonBounds(contentX(), settingsTop() + 4 * settingRowStep() + 40, 106, 16);
    }

    private ButtonBounds hudQuestResetButton() {
        return new ButtonBounds(contentX() + 112, settingsTop() + 4 * settingRowStep() + 40, 106, 16);
    }

    private ButtonBounds shardPayToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3, 32, 14);
    }

    private int shardPayOwnerRowY() {
        return settingsTop() + (panelH < 300 ? 28 : 34);
    }

    private int shardPayMembersTopY() {
        return settingsTop() + (panelH < 300 ? 56 : 70);
    }

    private ButtonBounds shardPayNowButton() {
        return new ButtonBounds(contentX(), shardPayMembersTopY() + 3 * shardMemberRowStep() + 6, 74, 16);
    }

    private int shardPunchRowY() {
        return shardPayMembersTopY() + 3 * shardMemberRowStep() + 26;
    }

    private ButtonBounds shardPunchToggleButton() {
        return new ButtonBounds(contentRight() - 32, shardPunchRowY() + 3, 32, 14);
    }

    private ButtonBounds shardPunchSliderBounds() {
        int width = Math.min(200, contentW() - 100);
        return new ButtonBounds(contentRight() - 40 - width, shardPunchRowY(), width, 16);
    }

    private int shardCobbleRowY() {
        return shardPunchRowY() + (panelH < 300 ? 22 : 26);
    }

    private int shardGemsRowY() {
        return shardPunchRowY() + (panelH < 300 ? 44 : 52);
    }

    private ButtonBounds shardCobbleToggleButton() {
        return new ButtonBounds(contentRight() - 32, shardCobbleRowY() + 3, 32, 14);
    }

    private ButtonBounds shardGemsToggleButton() {
        return new ButtonBounds(contentRight() - 32, shardGemsRowY() + 3, 32, 14);
    }

    private ButtonBounds cookieAutoToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3, 32, 14);
    }

    private ButtonBounds cookieDisableAfterToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + settingRowStep() + 3, 32, 14);
    }

    private ButtonBounds cookieAlertToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 2 * settingRowStep() + 3, 32, 14);
    }

    private ButtonBounds cookieClaimToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 3 * settingRowStep() + 3, 32, 14);
    }

    private ButtonBounds cookieRushToggleButton() {
        return new ButtonBounds(contentRight() - 32, settingsTop() + 4 * settingRowStep() + 3, 32, 14);
    }

    private int cookiePillRowY() {
        return settingsTop() + 4 * settingRowStep() + 26;
    }

    private ButtonBounds cookieFallbackButton() {
        return new ButtonBounds(contentX(), cookiePillRowY(), 156, 16);
    }

    private ButtonBounds cookieRushTypeButton() {
        return new ButtonBounds(contentX(), cookiePillRowY() + 20, 156, 16);
    }

    private boolean isInside(int mouseX, int mouseY, ButtonBounds bounds) {
        return isInside(mouseX, mouseY, bounds.x(), bounds.y(), bounds.w(), bounds.h());
    }

    private boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String formatPrice(Double value) {
        if (value == null) {
            return "";
        }
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm", value / 1_000_000d);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000d);
        }
        if (Math.rint(value) != value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String formatRate(Double value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String formatSignedPrice(Double value) {
        if (value == null) {
            return "";
        }
        if (value > 0) {
            return "+" + formatPrice(value);
        }
        if (value < 0) {
            return "-" + formatPrice(Math.abs(value));
        }
        return "0";
    }

    private static int spreadColor(Double spread) {
        if (spread == null) {
            return MUTED_TEXT;
        }
        if (spread > 0.0d) {
            return PROFIT_TEXT;
        }
        if (spread < 0.0d) {
            return LOSS_TEXT;
        }
        return HEADER_TEXT;
    }

    private static String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String value = token;
        int idx = value.indexOf(':');
        if (idx >= 0 && idx + 1 < value.length()) {
            value = value.substring(idx + 1);
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String prettifyKey(String raw) {
        String key = normalizeToken(raw);
        if (key.isBlank()) {
            return "Unknown";
        }
        StringBuilder out = new StringBuilder();
        boolean nextUpper = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                nextUpper = true;
                continue;
            }
            if (nextUpper) {
                out.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                out.append(c);
            }
        }
        return out.isEmpty() ? "Unknown" : out.toString();
    }

    private static RowEntry choosePreferred(RowEntry existing, RowEntry candidate) {
        if (existing == null) {
            return candidate;
        }
        int existingScore = dataScore(existing);
        int candidateScore = dataScore(candidate);
        if (candidateScore > existingScore) {
            return candidate;
        }
        if (candidateScore < existingScore) {
            return existing;
        }
        boolean existingHasItemId = existing.blacklistToken() != null && existing.blacklistToken().contains(":");
        boolean candidateHasItemId = candidate.blacklistToken() != null && candidate.blacklistToken().contains(":");
        if (candidateHasItemId && !existingHasItemId) {
            return candidate;
        }
        return existing;
    }

    private static int dataScore(RowEntry row) {
        int score = 0;
        if (row.buy() != null) {
            score++;
        }
        if (row.sell() != null) {
            score++;
        }
        if (row.marketRate() != null) {
            score++;
        }
        return score;
    }

    private void applySort() {
        Comparator<RowEntry> comparator = switch (activeTab) {
            case HIGHEST_SELL -> Comparator
                    .comparing((RowEntry row) -> row.sell() != null ? row.sell() : Double.NEGATIVE_INFINITY)
                    .reversed();
            case LOWEST_BUY -> Comparator
                    .comparing((RowEntry row) -> row.buy() != null ? row.buy() : Double.POSITIVE_INFINITY);
        };
        rows.sort(comparator.thenComparing(RowEntry::displayName, String.CASE_INSENSITIVE_ORDER));
    }

    private record RowEntry(String displayName, String blacklistToken, Double buy, Double sell, Double marketRate, boolean blacklisted) {
    }

    private record GemFlipEntry(
            String displayName,
            String itemId,
            Double buy,
            Double sell,
            double average,
            Double spread,
            Double marketRate,
            Integer stock
    ) {
    }

    private record ButtonBounds(int x, int y, int w, int h) {
    }

    private enum MainTab {
        TRACKER("Tracker"),
        GEM_FLIPS("Gem Flips"),
        AUTO_SCAN("Auto Scan"),
        AUTO_CMD("Auto Cmd"),
        MACROS("Macros"),
        SHARD_PAY("Shard Pay"),
        COOKIE("Cookie"),
        PRESETS("Presets"),
        HUD("HUD"),
        AUTO_EGG("Misc"),
        THEMES("Themes"),
        INFO("Info");

        final String label;

        MainTab(String label) {
            this.label = label;
        }

        String group() {
            return switch (this) {
                case TRACKER, GEM_FLIPS, AUTO_SCAN -> "BAZAAR";
                case AUTO_CMD, MACROS, SHARD_PAY, COOKIE, PRESETS -> "QUALITY OF LIFE";
                case HUD, AUTO_EGG, THEMES -> "DISPLAY";
                case INFO -> "HELP";
                default -> "COBBLE";
            };
        }
    }

    private enum SortTab {
        HIGHEST_SELL,
        LOWEST_BUY
    }

    private enum GemSortTab {
        HIGHEST_SELL,
        LOWEST_BUY
    }
}
