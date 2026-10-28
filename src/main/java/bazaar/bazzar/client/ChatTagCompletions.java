package bazaar.bazzar.client;

import java.util.Collection;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;

final class ChatTagCompletions {
    private static final List<String> TAGS = List.of(
            "[crops]",
            "[farming]",
            "[i]",
            "[item]",
            "[pearls]",
            "[blood]",
            "[completion]",
            "[slain]",
            "[bp]",
            "[backpack]",
            "[playtime]",
            "[level]",
            "[bosses]",
            "[cobble]",
            "[shards]",
            "[gems]",
            "[multi]"
    );
    private static final int CHECK_INTERVAL_TICKS = 40;

    private static int tickCounter = 0;

    private ChatTagCompletions() {
    }

    static void tick(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            return;
        }
        if (++tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        ClientSuggestionProvider provider = client.player.connection.getSuggestionsProvider();
        // the server can replace custom completions, so add them back when they go missing
        if (customSuggestions(provider).containsAll(TAGS)) {
            return;
        }
        provider.modifyCustomCompletions(ClientboundCustomChatCompletionsPacket.Action.ADD, TAGS);
    }

    private static Collection<String> customSuggestions(ClientSuggestionProvider provider) {
        //? if >=26.1 {
        /*return provider.getCustomTabSuggestions();
        *///?} else
        return provider.getCustomTabSugggestions();
    }
}
