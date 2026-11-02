package bazaar.bazzar.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

// hold the chat peek key and chat opens, let go and it shuts again. Unbound until someone picks a key.
final class ChatPeek {
    private static ChatScreen opened = null;
    // after the peek shuts some other way (Enter, Escape) it waits for the key to come up before opening again
    private static boolean waitForRelease = false;

    private ChatPeek() {
    }

    static void tick(Minecraft client, KeyMapping key) {
        boolean held = McCompat.keyHeld(client, key);
        if (!held) {
            waitForRelease = false;
        }
        Screen screen = McCompat.screen(client);
        if (opened != null) {
            if (screen != opened) {
                opened = null;
                waitForRelease = held;
            } else if (!held) {
                opened = null;
                McCompat.setScreen(client, null);
            }
            return;
        }
        if (held && !waitForRelease && screen == null && client.player != null) {
            opened = new ChatScreen("", false);
            McCompat.setScreen(client, opened);
        }
    }
}
