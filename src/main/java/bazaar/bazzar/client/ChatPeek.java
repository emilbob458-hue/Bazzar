package bazaar.bazzar.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

// hold the chat peek key and the chat shows full size like it's open, without opening the chat box.
// The mouse wheel scrolls the chat instead of the hotbar while it's held. Unbound until someone picks a key.
public final class ChatPeek {
    private static boolean active = false;

    private ChatPeek() {
    }

    public static boolean active() {
        return active;
    }

    static void tick(Minecraft client, KeyMapping key) {
        boolean was = active;
        active = McCompat.keyHeld(client, key) && McCompat.screen(client) == null && client.player != null;
        if (was && !active) {
            McCompat.chatComponent(client).resetChatScroll();
        }
    }

    // scrolls like the open chat box does: 7 lines a notch, 1 with shift held
    public static boolean scroll(Minecraft client, double amount) {
        if (!active || McCompat.screen(client) != null || amount == 0.0) {
            return false;
        }
        double lines = Mth.clamp(amount, -1.0, 1.0);
        if (!client.hasShiftDown()) {
            lines *= 7.0;
        }
        McCompat.chatComponent(client).scrollChat((int) lines);
        return true;
    }
}
