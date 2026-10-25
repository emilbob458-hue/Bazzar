package com.shady.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

public class ShadyClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ShadyConfig.State config = ShadyConfig.load();
        ShadySummonerTimerHud.applyConfig(config);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ShadySummonerTimerHud.consumeDirty()) {
                ShadyConfig.State state = ShadyConfig.load();
                ShadySummonerTimerHud.captureConfig(state);
                ShadyConfig.save(state);
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            ShadySummonerTimerHud.onChatLine(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            ShadySummonerTimerHud.onChatLine(message.getString());
        });

        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client.screen == null) {
                ShadySummonerTimerHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), -1, -1);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenMouseEvents.afterMouseClick(screen).register((scr, event, canceled) -> {
                ShadySummonerTimerHud.onMouseClick(scr, event);
                return false;
            });
            ScreenMouseEvents.afterMouseRelease(screen).register((scr, event, canceled) -> {
                ShadySummonerTimerHud.onMouseRelease(scr, event);
                return false;
            });
            ScreenMouseEvents.afterMouseDrag(screen).register((scr, event, deltaX, deltaY, canceled) -> {
                ShadySummonerTimerHud.onMouseDrag(scr, event, deltaX, deltaY);
                return false;
            });
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, delta) -> {
                ShadySummonerTimerHud.render(graphics, graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY);
            });
        });
    }
}
