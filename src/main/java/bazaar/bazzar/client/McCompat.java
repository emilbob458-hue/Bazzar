package bazaar.bazzar.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;

final class McCompat {
    private McCompat() {
    }

    static Screen screen(Minecraft client) {
        //? if >=26.1 {
        /*return client.gui.screen();
        *///?} else
        return client.screen;
    }

    static void setScreen(Minecraft client, Screen screen) {
        //? if >=26.1 {
        /*client.gui.setScreen(screen);
        *///?} else
        client.setScreen(screen);
    }

    static void chat(LocalPlayer player, Component message) {
        //? if >=26.1 {
        /*player.sendSystemMessage(message);
        *///?} else
        player.displayClientMessage(message, false);
    }

    static void showTitle(Minecraft client, int fadeIn, int stay, int fadeOut, Component title) {
        //? if >=26.1 {
        /*client.gui.hud.setTimes(fadeIn, stay, fadeOut);
        client.gui.hud.setSubtitle(Component.empty());
        client.gui.hud.setTitle(title);
        *///?} else {
        client.gui.setTimes(fadeIn, stay, fadeOut);
        client.gui.setSubtitle(Component.empty());
        client.gui.setTitle(title);
        //?}
    }

    static void showTitle(Minecraft client, int fadeIn, int stay, int fadeOut, Component title, Component subtitle) {
        //? if >=26.1 {
        /*client.gui.hud.setTimes(fadeIn, stay, fadeOut);
        client.gui.hud.setSubtitle(subtitle);
        client.gui.hud.setTitle(title);
        *///?} else {
        client.gui.setTimes(fadeIn, stay, fadeOut);
        client.gui.setSubtitle(subtitle);
        client.gui.setTitle(title);
        //?}
    }

    static BossHealthOverlay bossOverlay(Minecraft client) {
        //? if >=26.1 {
        /*return client.gui.hud.getBossOverlay();
        *///?} else
        return client.gui.getBossOverlay();
    }

    static boolean hudHidden(Minecraft client) {
        //? if >=26.1 {
        /*return client.gui.hud.isHidden();
        *///?} else
        return client.options.hideGui;
    }

    // Minecraft.setScreen calls KeyMapping.releaseAll, so isDown() goes false the moment a menu
    // opens. Holding a key through a menu has to be read from the window itself.
    static boolean keyHeld(Minecraft client, KeyMapping mapping) {
        if (client == null || mapping == null || client.getWindow() == null) {
            return false;
        }
        //? if >=26.1 {
        /*InputConstants.Key key = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(mapping);
        *///?} else
        InputConstants.Key key = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(mapping);
        if (key == null || key.getType() != InputConstants.Type.KEYSYM
                || key.getValue() == InputConstants.UNKNOWN.getValue()) {
            return false;
        }
        return InputConstants.isKeyDown(client.getWindow(), key.getValue());
    }

    static DisplaySlot teamSlot(PlayerTeam team) {
        //? if >=26.1 {
        /*return team.getColor().map(color -> color.displaySlot()).orElse(null);
        *///?} else
        return DisplaySlot.teamColorToSlot(team.getColor());
    }
}
