package bazaar.bazzar.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

// Shared look for Bazaar chat messages: a gold-to-orange gradient "Bazaar »" tag.
final class BazaarChat {
    static final String TAG = "Bazaar »";
    static final int GOLD = 0xFFD54A;
    private static final int ORANGE = 0xFF7A3D;

    private BazaarChat() {
    }

    static MutableComponent prefix() {
        return gradient("Bazaar", true)
                .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY));
    }

    static MutableComponent gradient(String text, boolean bold) {
        MutableComponent out = Component.empty();
        int steps = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            int rgb = blend(GOLD, ORANGE, i / (float) steps);
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(style -> style.withColor(TextColor.fromRgb(rgb)).withBold(bold)));
        }
        return out;
    }

    static MutableComponent colored(String text, int rgb) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
