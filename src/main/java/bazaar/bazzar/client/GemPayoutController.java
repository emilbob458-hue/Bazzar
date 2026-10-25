package bazaar.bazzar.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class GemPayoutController {

    private static final Pattern[] RECEIVE_PATTERNS = {
            Pattern.compile("(?i)^\\s*(\\w{1,16})\\s+(?:has\\s+)?(?:sent|paid|gave)\\s+you\\s+(?:x|[^\\w\\s])?\\s*([\\d,.]+[a-z]{0,2})\\s+gems?"),
            Pattern.compile("(?i)^\\s*you\\s+(?:have\\s+)?(?:received|got)\\s+(?:x|[^\\w\\s])?\\s*([\\d,.]+[a-z]{0,2})\\s+gems?\\s+from\\s+(\\w{1,16})"),
            Pattern.compile("(?i)^\\s*received\\s+(?:x|[^\\w\\s])?\\s*([\\d,.]+[a-z]{0,2})\\s+gems?\\s+from\\s+(\\w{1,16})")
    };


    private GemPayoutController() {
    }

    static int runStatus(CommandContext<FabricClientCommandSource> context) {
        BazzarConfig.State state = BazzarConfig.load();
        String priceText = state.gemPrice > 0.0d ? SbText.formatCompact(state.gemPrice) : "not set";
        context.getSource().sendFeedback(prefix()
                .append(Component.literal("Gem auto pay: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(state.gemPayoutEnabled ? "ON" : "OFF")
                        .withStyle(state.gemPayoutEnabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("  price/gem: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(priceText).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  (/setgemprice <price>, /setgemprice on|off)").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    static int runToggle(CommandContext<FabricClientCommandSource> context, boolean enable) {
        BazzarConfig.State state = BazzarConfig.load();
        if (enable && state.gemPrice <= 0.0d) {
            context.getSource().sendFeedback(prefix()
                    .append(Component.literal("Set a price first: /setgemprice <price>").withStyle(ChatFormatting.RED)));
            return 0;
        }
        state.gemPayoutEnabled = enable;
        BazzarConfig.save(state);
        context.getSource().sendFeedback(prefix()
                .append(Component.literal(enable
                        ? "Gem auto pay ON, paying " + SbText.formatCompact(state.gemPrice) + " per gem received."
                        : "Gem auto pay OFF.")
                        .withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        return 1;
    }

    static void onChatLine(Minecraft client, String message) {
        if (client == null || client.player == null || client.player.connection == null || message == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.gemPayoutEnabled || state.gemPrice <= 0.0d) {
            return;
        }
        Parsed parsed = parseReceive(SbText.stripSprites(message));
        if (parsed == null) {
            return;
        }
        String self = client.player.getName().getString();
        if (parsed.sender.equalsIgnoreCase(self) || ShardPayoutController.isOwnAccount(state, parsed.sender)) {
            return;
        }
        double total = parsed.amount * state.gemPrice;
        String amountText = ShardPayoutController.formatPayAmount(total);
        PayoutQueue.enqueue(client, "gem", parsed.sender, parsed.amount, total, amountText);
    }

    private static Parsed parseReceive(String message) {
        for (int i = 0; i < RECEIVE_PATTERNS.length; i++) {
            Matcher matcher = RECEIVE_PATTERNS[i].matcher(message);
            if (!matcher.find()) {
                continue;
            }
            String name = i == 0 ? matcher.group(1) : matcher.group(2);
            String amountRaw = i == 0 ? matcher.group(2) : matcher.group(1);
            Double amount = ShardPayoutController.parseCompactNumber(amountRaw);
            if (amount == null || amount < 1.0d) {
                return null;
            }
            return new Parsed(name, amount);
        }
        return null;
    }

    private static MutableComponent prefix() {
        return Component.literal("[Bazaar] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    private record Parsed(String sender, double amount) {
    }
}
