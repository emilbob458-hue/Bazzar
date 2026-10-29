package bazaar.bazzar.client;

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

    static DisplaySlot teamSlot(PlayerTeam team) {
        //? if >=26.1 {
        /*return team.getColor().map(color -> color.displaySlot()).orElse(null);
        *///?} else
        return DisplaySlot.teamColorToSlot(team.getColor());
    }
}
