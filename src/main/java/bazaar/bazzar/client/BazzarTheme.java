package bazaar.bazzar.client;

import java.util.List;

final class BazzarTheme {

    static final List<BazzarTheme> ALL = List.of(
            new BazzarTheme("rainbow", "Rainbow", "Cycles through every color", 0xFF5555),
            new BazzarTheme("custom", "Custom", "Your own hex color below", 0xFF5CA8),
            new BazzarTheme("amber", "Amber", "The classic Bazaar amber", 0xE39B3A),
            new BazzarTheme("vault", "Vault", "Glass + violet", 0x8C7BFF),
            new BazzarTheme("nova", "Nova", "Synthwave cyan glow", 0x2BD9EE),
            new BazzarTheme("rose", "Rose", "Pink couture", 0xF14C8F),
            new BazzarTheme("ember", "Ember", "Molten amber glow", 0xFF7C1A),
            new BazzarTheme("forest", "Forest", "Emerald canopy", 0x22C97E),
            new BazzarTheme("indigo", "Indigo", "Twilight indigo", 0x6D6AF0),
            new BazzarTheme("magenta", "Magenta", "Neon orchid", 0xE44CF5),
            new BazzarTheme("ocean", "Ocean", "Open water sapphire", 0x3E82F7),
            new BazzarTheme("crimson", "Crimson", "Blood velvet red", 0xEF4444),
            new BazzarTheme("lime", "Lime", "Radioactive lime", 0x96D913),
            new BazzarTheme("gold", "Gold", "Luxury gilded glow", 0xFFC42E),
            new BazzarTheme("aurora", "Aurora", "Northern lights", 0x57E6CE)
    );

    private static BazzarTheme current;

    final String id;
    final String name;
    final String description;
    int rgb;

    private BazzarTheme(String id, String name, String description, int rgb) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.rgb = rgb;
    }

    static void reload() {
        current = null;
    }

    static BazzarTheme current() {
        if (current == null) {
            current = byId(BazzarConfig.load().themeId);
        }
        refreshDynamic(current);
        return current;
    }

    static void refreshDynamic(BazzarTheme theme) {
        if (theme == null) {
            return;
        }
        if ("rainbow".equals(theme.id)) {
            theme.rgb = rainbowRgb();
        } else if ("custom".equals(theme.id)) {
            theme.rgb = BazzarConfig.load().customThemeRgb & 0xFFFFFF;
        }
    }

    private static int rainbowRgb() {
        float t = (System.currentTimeMillis() % 8000L) / 8000.0f * 6.0f;
        int i = (int) t;
        float f = t - i;
        int q = Math.round(255.0f * (1.0f - f));
        int u = Math.round(255.0f * f);
        return switch (i % 6) {
            case 0 -> 0xFF0000 | (u << 8);
            case 1 -> (q << 16) | 0x00FF00;
            case 2 -> 0x00FF00 | u;
            case 3 -> (q << 8) | 0x0000FF;
            case 4 -> (u << 16) | 0x0000FF;
            default -> 0xFF0000 | q;
        };
    }

    static void select(BazzarTheme theme) {
        current = theme;
        BazzarConfig.State state = BazzarConfig.load();
        state.themeId = theme.id;
        BazzarConfig.save(state);
    }

    static BazzarTheme byId(String id) {
        if (id != null) {
            for (BazzarTheme theme : ALL) {
                if (theme.id.equalsIgnoreCase(id)) {
                    return theme;
                }
            }
        }
        return defaultTheme();
    }

    static BazzarTheme defaultTheme() {
        for (BazzarTheme theme : ALL) {
            if (theme.id.equals("indigo")) {
                return theme;
            }
        }
        return ALL.get(0);
    }

    int accent() {
        return 0xFF000000 | rgb;
    }

    int accentDim() {
        return 0xCC000000 | rgb;
    }

    int soft(int alpha) {
        return (alpha << 24) | rgb;
    }

    int dark() {
        return BazzarUi.lerpColor(accent(), 0xFF000000, 0.45f);
    }

    int deep() {
        return BazzarUi.lerpColor(accent(), 0xFF000000, 0.70f);
    }

    int light() {
        return BazzarUi.lerpColor(accent(), 0xFFFFFFFF, 0.35f);
    }

    int buttonBg() {
        return BazzarUi.lerpColor(0xFF14181E, accent(), 0.08f);
    }

    int buttonBgHover() {
        return BazzarUi.lerpColor(0xFF14181E, accent(), 0.16f);
    }

    int buttonBgActive() {
        return BazzarUi.lerpColor(0xFF14181E, accent(), 0.26f);
    }

    int textOnAccent() {
        return BazzarUi.lerpColor(accent(), 0xFF000000, 0.85f);
    }

    int readableOnAccent() {
        return luminance() > 0.2 ? textOnAccent() : 0xFFFFFFFF;
    }

    private double luminance() {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
