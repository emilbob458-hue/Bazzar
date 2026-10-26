package bazaar.bazzar.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
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
        ShardCalculator.setShardPrice(config.shardPrice);
        ShardCalculator.setGemPrice(config.gemPrice);
        ShardCalculator.setCurrentMulti(config.currentMulti);
    }

    @Override
    public void onInitializeClient() {
        ShardCalculator.registerCommands();
        BazaarFlipScanner.registerCommands();
        CookieController.registerCommands();
        AltCommandController.registerCommands();
        CobblePayoutController.registerCommands();
        ShardPayoutController.registerCommands();
        ConfigPresets.registerCommands();
        AutoUpdater.registerCommands();
        UsageStats.registerCommands();
        PlayerStats.registerCommands();
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (BazzarGuard.isBlocked()) {
                return;
            }
            while (scanKey.consumeClick()) {
                BazaarAutoScanController.triggerManual(client, BazaarAutoScanController.ScanMode.CROPS);
            }
            while (farmsKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new FarmWarpScreen());
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
                    client.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    PinnedBazaarHud.isVisible() ? "[Bazaar] HUD shown" : "[Bazaar] HUD hidden"),
                            false
                    );
                }
            }
            while (trackerGuiKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new BazaarTrackerScreen());
                }
            }
            while (questHudKey.consumeClick()) {
                QuestCommandHud.setVisible(true);
                GuildQuestScanner.triggerManual(client);
            }
            while (pvSearchKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new PvSearchScreen());
                }
            }
            for (int i = 0; i < macroKeys.length; i++) {
                while (macroKeys[i].consumeClick()) {
                    MacroController.sendKeyMacro(client, i);
                }
            }
            MacroController.tick(client);
            BazaarAutoScanController.triggerAuto(client);
            GuildQuestScanner.triggerAuto(client);
            CookieController.triggerAuto(client);
            ShardPayController.triggerAuto(client);
            AutoPunchController.triggerAuto(client);
            AltCommandController.tick(client);
            SessionProfitHud.tick(client);
            PlaytimeRewardsController.tick(client);
            CelestialRewardsController.tick(client);
            CelestialBoosterReminder.tick(client);
            PvScanner.tick(client);
            AutoDumpController.tick(client);
            ShadySummonerFinder.tick(client);
            ChatCommandTriggerController.tick(client);
            BazaarAutoScanController.tick(client);
            GuildQuestScanner.tick(client);
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
            if (hudDirty || autoScanDirty || guildQuestDirty || shadyDirty || questHudDirty || profitHudDirty) {
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
            ShardPayController.onChatLine(client, msg);
            CookieController.onChatMessage(client, msg);
            PlaytimeRewardsController.onChatLine(client, msg);
            CelestialBoosterReminder.onChatLine(client, msg);
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
        });

        ClientSendMessageEvents.COMMAND.register(CelestialBoosterReminder::onCommandSent);
        net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register(
                (level, player, pos, state) -> BlockBreakCounter.onOwnBreak(pos));


        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            if (BazzarGuard.isBlocked()) {
                return;
            }
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            ScoreboardOverlay.render(graphics);
            if (client.screen == null) {
                PinnedBazaarHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1);
                ShadySummonerTimerHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1);
                QuestCommandHud.render(graphics, -1, -1);
                SessionProfitHud.render(graphics, -1, -1);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenMouseEvents.afterMouseClick(screen).register((scr, event, canceled) -> {
                PinnedBazaarHud.onMouseClick(scr, event);
                ShadySummonerTimerHud.onMouseClick(scr, event);
                QuestCommandHud.onMouseClick(scr, event);
                SessionProfitHud.onMouseClick(scr, event);
                ScoreboardOverlay.onMouseClick(scr, event);
                return false;
            });
            ScreenMouseEvents.afterMouseRelease(screen).register((scr, event, canceled) -> {
                PinnedBazaarHud.onMouseRelease(scr, event);
                ShadySummonerTimerHud.onMouseRelease(scr, event);
                QuestCommandHud.onMouseRelease(scr, event);
                SessionProfitHud.onMouseRelease(scr, event);
                ScoreboardOverlay.onMouseRelease(scr, event);
                return false;
            });
            ScreenMouseEvents.afterMouseDrag(screen).register((scr, event, deltaX, deltaY, canceled) -> {
                PinnedBazaarHud.onMouseDrag(scr, event, deltaX, deltaY);
                ShadySummonerTimerHud.onMouseDrag(scr, event, deltaX, deltaY);
                QuestCommandHud.onMouseDrag(scr, event, deltaX, deltaY);
                SessionProfitHud.onMouseDrag(scr, event, deltaX, deltaY);
                ScoreboardOverlay.onMouseDrag(scr, event, deltaX, deltaY);
                return false;
            });
            ScreenMouseEvents.afterMouseScroll(screen).register((scr, mouseX, mouseY, horizontalAmount, verticalAmount, canceled) -> {
                PinnedBazaarHud.onMouseScroll(scr, mouseX, mouseY, horizontalAmount, verticalAmount);
                QuestCommandHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                SessionProfitHud.onMouseScroll(mouseX, mouseY, verticalAmount);
                ScoreboardOverlay.onMouseScroll(mouseX, mouseY, verticalAmount);
                return false;
            });
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, delta) -> {
                PvScanner.renderHighlight(scr, graphics);
                PinnedBazaarHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY);
                ShadySummonerTimerHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY);
                QuestCommandHud.render(graphics, mouseX, mouseY);
                SessionProfitHud.render(graphics, mouseX, mouseY);
            });
        });
    }
}
