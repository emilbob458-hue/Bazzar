package bazaar.bazzar.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;

// the item sprites the server puts next to each currency, e.g. "[item/gold_ingot@items]1.00M Tokens"
final class CurrencyIcons {
    private static final Pattern SPRITE_BEFORE_CURRENCY = Pattern.compile(
            "\\[([a-z0-9_./:-]+?)(?:@([a-z0-9_./:-]+))?\\]\\s*[0-9.,]*[kmbt]?\\s*(tokens?|gems?|shards?|cobble)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, Component> ICONS = new HashMap<>();

    static {
        ICONS.put("tokens", sprite(AtlasIds.ITEMS, "item/gold_ingot"));
        ICONS.put("gems", sprite(AtlasIds.ITEMS, "item/amethyst_shard"));
        ICONS.put("shards", sprite(AtlasIds.ITEMS, "item/prismarine_shard"));
        ICONS.put("cobble", sprite(AtlasSprite.DEFAULT_ATLAS, "block/cobblestone"));
    }

    private CurrencyIcons() {
    }

    // picks up whatever sprite the server shows next to a currency, so a change on the server shows up here too
    static void learn(String raw) {
        if (raw == null || raw.indexOf('[') < 0) {
            return;
        }
        Matcher matcher = SPRITE_BEFORE_CURRENCY.matcher(raw);
        while (matcher.find()) {
            Identifier spriteId = Identifier.tryParse(matcher.group(1));
            Identifier atlasId = matcher.group(2) == null ? AtlasSprite.DEFAULT_ATLAS : Identifier.tryParse(matcher.group(2));
            if (spriteId == null || atlasId == null) {
                continue;
            }
            String currency = CelestialMarket.currencyOf(matcher.group(3).toLowerCase(Locale.ROOT));
            if (currency != null) {
                ICONS.put(currency, Component.object(new AtlasSprite(atlasId, spriteId)));
            }
        }
    }

    // draws the 8x8 icon with its top-left at (x, y), scaled down for small-caps labels
    static void draw(GuiGraphics graphics, Font font, String currency, int x, int y, float scale) {
        Component icon = ICONS.get(currency);
        if (icon == null) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.drawString(font, icon, 0, 0, 0xFFFFFFFF, false);
        graphics.pose().popMatrix();
    }

    private static Component sprite(Identifier atlas, String path) {
        return Component.object(new AtlasSprite(atlas, Identifier.withDefaultNamespace(path)));
    }
}
