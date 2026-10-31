package bazaar.bazzar.client;

public final class PrefixPalette {
    public static final int SENTINEL = 0xFFD54B;
    private static final int GOLD = 0xFFD54A;
    private static final int ORANGE = 0xFF7A3D;
    private static final long PERIOD_MS = 2600L;
    private static final double SPREAD = 0.11;

    private PrefixPalette() {
    }

    public static int animate(int index) {
        double phase = (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
        double wave = phase - index * SPREAD;
        double t = 0.5 - 0.5 * Math.cos(wave * 2.0 * Math.PI);
        return blend(GOLD, ORANGE, (float) t);
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
