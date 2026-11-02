package bazaar.bazzar.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

// hold the chat peek key and the chat shows full size like it's open, without opening the chat box.
// Unbound until someone picks a key.
public final class ChatPeek {
    private static boolean active = false;

    private ChatPeek() {
    }

    public static boolean active() {
        return active;
    }

    static void tick(Minecraft client, KeyMapping key) {
        active = McCompat.keyHeld(client, key) && McCompat.screen(client) == null && client.player != null;
    }
}
