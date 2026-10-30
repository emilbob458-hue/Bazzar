package bazaar.bazzar.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class BazzarClient implements ClientModInitializer {

    private static final Category BAZZAR_CATEGORY = Category.register(Identifier.parse("bazzar"));

    static void applyConfig(BazzarConfig.State config) {
        BazzarTheme.reload();
        BazaarFlipScanner.applyConfig(config);
        PinnedBazaarHud.applyConfig(config);
        BazaarAutoScanController.applyConfig(config);
        GuildQuestScanner.applyConfig(config);
        ShadySummonerTimerHud.applyConfig(config);
        ShadySummonerFinder.applyConfig(config);
        ChatCommandTriggerController.applyConfig(config);
        ShardPayController.applyConfig(config);
        SessionProfitHud.applyConfig(config);
        QuestCommandHud.applyConfig(config);
        MobMilestoneHud.applyConfig(config);
        GuildWorkHud.applyConfig(config);
        MobKillHud.applyConfig(config);
        ShardCalculator.setShardPrice(config.shardPrice);
        ShardCalculator.setGemPrice(config.gemPrice);
        ShardCalculator.setCurrentMulti(config.currentMulti);
    }

    private static boolean isBazzarScreen(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof BazaarTrackerScreen
                || screen instanceof FarmWarpScreen
                || screen instanceof PvSearchScreen
                || screen instanceof PayoutHistoryScreen;
    }

    @Override
    public void onInitializeClient() {
        ShardCalculator.registerCommands();
        BazaarFlipScanner.registerCommands();
        CookieController.registerCommands();
        AltCommandController.registerCommands();
        CobblePayoutController.registerCommands();
        ConfigPresets.registerCommands();
        AutoUpdater.registerCommands();
        UsageStats.registerCommands();
        PlayerStats.registerCommands();
        MobMilestones.registerCommands();
        MobKillTracker.registerCommands();
        // both keep an all time count and only write it out every half minute, so closing the game
        // in between would otherwise throw away the kills since the last write
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            MobKillTracker.save();
            MobMilestones.save();
        });
        CelestialTracker.registerCommands();
        CelestialMarket.registerCommands();
        GuildShare.registerCommands();
        Announcements.registerCommands();
        KeyMapping scanKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.scan",
                GLFW.GLFW_KEY_B,
                BAZZAR_CATEGORY));
        KeyMapping farmsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.farms",
                GLFW.GLFW_KEY_F,
                BAZZAR_CATEGORY));
        KeyMapping scaleUpKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.hud_scale_up",
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                BAZZAR_CATEGORY));
        KeyMapping scaleDownKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.hud_scale_down",
                GLFW.GLFW_KEY_LEFT_BRACKET,
                BAZZAR_CATEGORY));
        KeyMapping toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.toggle_hud",
                GLFW.GLFW_KEY_H,
                BAZZAR_CATEGORY));
        KeyMapping trackerGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.tracker_gui",
                GLFW.GLFW_KEY_J,
                BAZZAR_CATEGORY));
        KeyMapping questHudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.quest_hud_toggle",
                GLFW.GLFW_KEY_K,
                BAZZAR_CATEGORY));
        KeyMapping pvSearchKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.pv_search",
                GLFW.GLFW_KEY_O,
                BAZZAR_CATEGORY));
        KeyMapping cbHudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.cb_hud",
                GLFW.GLFW_KEY_U,
                BAZZAR_CATEGORY));
        KeyMapping cbBuyKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bazzar.cb_buy",
                GLFW.GLFW_KEY_I,
                BAZZAR_CATEGORY));
        CelestialMarketHud.setBuyKey(cbBuyKey);
        KeyMapping[] macroKeys = new KeyMapping[MacroController.KEY_MACRO_COUNT];
        for (int i = 0; i < macroKeys.length; i++) {
            macroKeys[i] = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.bazzar.macro_" + (i + 1),
                    GLFW.GLFW_KEY_UNKNOWN,
                    BAZZAR_CATEGORY));
        }

        applyConfig(BazzarConfig.load());
        AutoUpdater.start();
        UsageStats.start();
        PlayerStats.start();
        MobMilestones.registerEvents();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (BazzarGuard.isBlocked()) {
                return;
            }
            while (scanKey.consumeClick()) {
                BazaarAutoScanController.triggerManual(client, BazaarAutoScanController.ScanMode.CROPS);
            }
            while (farmsKey.consumeClick()) {
                if (McCompat.screen(client) == null) {
                    McCompat.setScreen(client, new FarmWarpScreen());
                }
            }
            while (scaleUpKey.consumeClick()) {
                PinnedBazaarHud.setScale(PinnedBazaarHud.getScale() + 0.1f);
            }
            while (scaleDownKey.consumeClick()) {
                PinnedBazaarHud.setScale(PinnedBazaarHud.getScale() - 0.1f);
            }
            while (toggleHudKey.consumeClick()) {
                PinnedBazaarHud.toggleVisibility();
                if (client.player != null) {
                    McCompat.chat(client.player, BazaarChat.prefix().append(net.minecraft.network.chat.Component.literal(
                            PinnedBazaarHud.isVisible()
                                    ? PinnedBazaarHud.hasPins()
                                            ? "HUD shown"
                                            : "HUD shown. Nothing is pinned yet, so scan the bazaar first."
                                    : "HUD hidden")));
                }
            }
            while (trackerGuiKey.consumeClick()) {
                if (McCompat.screen(client) == null) {
                    McCompat.setScreen(client, new BazaarTrackerScreen());
                }
            }
            while (questHudKey.consumeClick()) {
                QuestCommandHud.setVisible(true);
                GuildQuestScanner.triggerManual(client);
            }
            // hold the quest key to keep /gq up instead of it flashing shut after the scan
            GuildQuestScanner.setHoldOpen(McCompat.keyHeld(client, questHudKey));
            while (pvSearchKey.consumeClick()) {
                if (McCompat.screen(client) == null) {
                    McCompat.setScreen(client, new PvSearchScreen());
                }
            }
            while (cbHudKey.consumeClick()) {
                CelestialMarketHud.toggle(client);
            }
            while (cbBuyKey.consumeClick()) {
                // the buy key only does something while the market HUD is on, so it can't fire by accident
                if (CelestialMarketHud.isVisible() && McCompat.screen(client) == null) {
                    // nobody gets charged by a hotkey before they've read the pay warning once
                    if (BazzarConfig.load().cbWarningAccepted) {
                        CelestialMarket.buyCheapest(client);
                    } else {
                        McCompat.setScreen(client, BazaarTrackerScreen.openCelestial(CelestialPanel.View.MARKET));
                    }
                }
            }
            for (int i = 0; i < macroKeys.length; i++) {
                while (macroKeys[i].consumeClick()) {
                    MacroController.sendKeyMacro(client, i);
                }
            }
            CommandSender.tick(client);
            MacroController.tick(client);
            BazaarAutoScanController.triggerAuto(client);
            GuildQuestScanner.triggerAuto(client);
            CookieController.triggerAuto(client);
            ShardPayController.triggerAuto(client);
            AutoPunchController.triggerAuto(client);
            AltCommandController.tick(client);
            SessionProfitHud.tick(client);
            PlaytimeRewardsController.tick(client);
            CelestialBoosterReminder.tick(client);
            PvScanner.tick(client);
            AutoDumpController.tick(client);
            ShadySummonerFinder.tick(client);
            ChatCommandTriggerController.tick(client);
            BazaarAutoScanController.tick(client);
            GuildQuestScanner.tick(client);
            FarmMobScanner.tick(client);
            MobMilestones.tick(client);
            ChatTagCompletions.tick(client);
            FishWarps.tick(client);
            CelestialMarket.tick(client);
            CelestialMarketHud.tick(client);
            GuildShare.tick(client);
            CelestialTracker.tick(client);
            Announcements.tick(client);
            MultiplierMenuScanner.tick(client);
            AutoUpdater.tick(client);
            UsageStats.tick(client);
            PayoutQueue.tick(client);
            PlayerStats.tick(client);
            PinnedBazaarHud.tick(client);
            boolean hudDirty = PinnedBazaarHud.consumeDirty();
            boolean autoScanDirty = BazaarAutoScanController.consumeSettingsDirty();
            boolean guildQuestDirty = GuildQuestScanner.consumeSettingsDirty();
            boolean shadyDirty = ShadySummonerTimerHud.consumeDirty();
            boolean questHudDirty = QuestCommandHud.consumeDirty();
            boolean profitHudDirty = SessionProfitHud.consumeDirty();
            boolean milestoneHudDirty = MobMilestoneHud.consumeDirty();
            boolean guildWorkDirty = GuildWorkHud.consumeDirty();
            boolean killHudDirty = MobKillHud.consumeDirty();
            if (hudDirty || autoScanDirty || guildQuestDirty || shadyDirty || questHudDirty || profitHudDirty || milestoneHudDirty
                    || guildWorkDirty || killHudDirty) {
                BazzarConfig.State merged = BazzarConfig.load();
                if (hudDirty) {
                    BazzarConfig.State hudOnly = PinnedBazaarHud.captureConfig();
                    merged.hudVisible = hudOnly.hudVisible;
                    merged.hudX = hudOnly.hudX;
                    merged.hudY = hudOnly.hudY;
                    merged.hudScale = hudOnly.hudScale;
                }
                if (autoScanDirty) {
                    BazaarAutoScanController.applyConfigToState(merged);
                }
                if (guildQuestDirty) {
                    GuildQuestScanner.applyConfigToState(merged);
                }
                if (shadyDirty) {
                    ShadySummonerTimerHud.captureConfig(merged);
                }
                if (questHudDirty) {
                    QuestCommandHud.applyConfigToState(merged);
                }
                if (profitHudDirty) {
                    SessionProfitHud.applyConfigToState(merged);
                }
                if (milestoneHudDirty) {
                    MobMilestoneHud.applyConfigToState(merged);
                }
                if (guildWorkDirty) {
                    GuildWorkHud.applyConfigToState(merged);
                }
                if (killHudDirty) {
                    MobKillHud.applyConfigToState(merged);
                }
                BazzarConfig.save(merged);
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (BazzarGuard.isBlocked()) {
                return;
            }
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            String msg = message.getString();
            ShadySummonerTimerHud.onChatLine(msg);
            ShadySummonerFinder.onChatLine(msg);
            AutoEggController.onChatLine(client, msg);
            ChatCommandTriggerController.onChatLine(client, msg, null);
            CobblePayoutController.onChatLine(client, msg);
            ShardPayoutController.onChatLine(client, msg);
            GemPayoutController.onChatLine(client, msg);
            PayoutQueue.onChatLine(client, msg);
            GuildQuestScanner.onChatLine(msg);
            ShardPayController.onChatLine(client, msg);
            CookieController.onChatMessage(client, msg);
            PlaytimeRewardsController.onChatLine(client, msg);
            CelestialBoosterReminder.onChatLine(client, msg);
            CelestialTracker.onChatLine(client, msg);
            CelestialMarket.onChatLine(client, msg);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (BazzarGuard.isBlocked()) {
                return;
            }
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            String msg = message.getString();
            ShadySummonerTimerHud.onChatLine(msg);
            ShadySummonerFinder.onChatLine(msg);
            String senderName = params != null && params.name() != null ? params.name().getString() : null;
            ChatCommandTriggerController.onChatLine(client, msg, senderName);
            CookieController.onChatMessage(client, msg);
            PlaytimeRewardsController.onChatLine(client, msg);
            CelestialBoosterReminder.onChatLine(client, msg);
            CelestialTracker.onChatLine(client, msg);
            CelestialMarket.onChatLine(client, msg);
        });

        ClientSendMessageEvents.COMMAND.register(CelestialBoosterReminder::onCommandSent);
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> BazzarGuard.isBlocked() || !CelestialTracker.onOwnChat(message));
        ClientSendMessageEvents.COMMAND.register(CookieController::onCommandSent);
        net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register(
                (level, player, pos, state) -> BlockBreakCounter.onOwnBreak(pos, state));


        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("bazzar", "hud"), (graphics, tickDelta) -> {
            if (BazzarGuard.isBlocked()) {
                return;
            }
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            ScoreboardOverlay.render(graphics);
            if (McCompat.screen(client) == null) {
                PinnedBazaarHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1);
                ShadySummonerTimerHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1);
                QuestCommandHud.render(graphics, -1, -1);
                SessionProfitHud.render(graphics, -1, -1);
                MobMilestoneHud.render(graphics, -1, -1);
                CelestialMarketHud.render(graphics, -1, -1);
                GuildWorkHud.render(graphics, -1, -1);
                MobKillHud.render(graphics, -1, -1);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // Bazzar's own screens cover the HUDs, so the HUDs must not draw over them or steal their clicks
            if (isBazzarScreen(screen)) {
                return;
            }
            ScreenMouseEvents.afterMouseClick(screen).register((scr, event, canceled) -> {
                PinnedBazaarHud.onMouseClick(scr, event);
                ShadySummonerTimerHud.onMouseClick(scr, event);
                QuestCommandHud.onMouseClick(scr, event);
                SessionProfitHud.onMouseClick(scr, event);
                MobMilestoneHud.onMouseClick(scr, event);
                CelestialMarketHud.onMouseClick(scr, event);
                GuildWorkHud.onMouseClick(scr, event);
                MobKillHud.onMouseClick(scr, event);
                ScoreboardOverlay.onMouseClick(scr, event);
                return false;
            });
            ScreenMouseEvents.afterMouseRelease(screen).register((scr, event, canceled) -> {
                PinnedBazaarHud.onMouseRelease(scr, event);
                ShadySummonerTimerHud.onMouseRelease(scr, event);
                QuestCommandHud.onMouseRelease(scr, event);
                SessionProfitHud.onMouseRelease(scr, event);
                MobMilestoneHud.onMouseRelease(scr, event);
                CelestialMarketHud.onMouseRelease(scr, event);
                GuildWorkHud.onMouseRelease(scr, event);
                MobKillHud.onMouseRelease(scr, event);
                ScoreboardOverlay.onMouseRelease(scr, event);
                return false;
            });
            ScreenMouseEvents.afterMouseDrag(screen).register((scr, event, deltaX, deltaY, canceled) -> {
                PinnedBazaarHud.onMouseDrag(scr, event, deltaX, deltaY);
                ShadySummonerTimerHud.onMouseDrag(scr, event, deltaX, deltaY);
                QuestCommandHud.onMouseDrag(scr, event, deltaX, deltaY);
                SessionProfitHud.onMouseDrag(scr, event, deltaX, deltaY);
                MobMilestoneHud.onMouseDrag(scr, event, deltaX, deltaY);
                CelestialMarketHud.onMouseDrag(scr, event, deltaX, deltaY);
                GuildWorkHud.onMouseDrag(scr, event, deltaX, deltaY);
                MobKillHud.onMouseDrag(scr, event, deltaX, deltaY);
                ScoreboardOverlay.onMouseDrag(scr, event, deltaX, deltaY);
                return false;
            });
            ScreenMouseEvents.afterMouseScroll(screen).register((scr, mouseX, mouseY, horizontalAmount, verticalAmount, canceled) -> {
                PinnedBazaarHud.onMouseScroll(scr, mouseX, mouseY, horizontalAmount, verticalAmount);
                QuestCommandHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                SessionProfitHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                MobMilestoneHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                GuildWorkHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                MobKillHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                ScoreboardOverlay.onMouseScroll(mouseX, mouseY, verticalAmount);
                return false;
            });
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, delta) -> {
                PvScanner.renderHighlight(scr, graphics);
                PinnedBazaarHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY);
                ShadySummonerTimerHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY);
                QuestCommandHud.render(graphics, mouseX, mouseY);
                SessionProfitHud.render(graphics, mouseX, mouseY);
                MobMilestoneHud.render(graphics, mouseX, mouseY);
                CelestialMarketHud.render(graphics, mouseX, mouseY);
                GuildWorkHud.render(graphics, mouseX, mouseY);
                MobKillHud.render(graphics, mouseX, mouseY);
            });
        });
    }
}
