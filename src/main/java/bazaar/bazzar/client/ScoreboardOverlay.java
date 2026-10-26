package bazaar.bazzar.client;

import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public final class ScoreboardOverlay {
    private static final Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER =
            Comparator.comparing(PlayerScoreEntry::value).reversed()
                    .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    private static final long CAPTURE_FRESH_MS = 500L;

    private ScoreboardOverlay() {
    }

    private static long lastComputeMs;
    private static boolean dragging;
    private static int dragGrabX;
    private static int dragGrabY;
    private static boolean boxValid;
    private static int boxX;
    private static int boxY;
    private static int boxW;
    private static int boxH;
    private static int drawX;
    private static int drawY;
    private static int drawW;
    private static int drawH;

    private static boolean capturing;
    private static int accMinX;
    private static int accMinY;
    private static int accMaxX;
    private static int accMaxY;
    private static long lastCaptureMs;
    private static int capX;
    private static int capY;
    private static int capW;
    private static int capH;

    public static void beginScoreboardCapture() {
        capturing = true;
        accMinX = Integer.MAX_VALUE;
        accMinY = Integer.MAX_VALUE;
        accMaxX = Integer.MIN_VALUE;
        accMaxY = Integer.MIN_VALUE;
    }

    public static void recordScoreboardFill(int x1, int y1, int x2, int y2) {
        if (!capturing) {
            return;
        }
        accMinX = Math.min(accMinX, Math.min(x1, x2));
        accMinY = Math.min(accMinY, Math.min(y1, y2));
        accMaxX = Math.max(accMaxX, Math.max(x1, x2));
        accMaxY = Math.max(accMaxY, Math.max(y1, y2));
    }

    public static void endScoreboardCapture() {
        capturing = false;
        if (accMaxX > accMinX && accMaxY > accMinY) {
            capX = accMinX;
            capY = accMinY;
            capW = accMaxX - accMinX;
            capH = accMaxY - accMinY;
            lastCaptureMs = System.currentTimeMillis();
        }
    }

    static void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null || McCompat.hudHidden(client)) {
            return;
        }
        if (!BazzarConfig.load().scoreboardGlowEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCaptureMs < CAPTURE_FRESH_MS) {
            boxX = capX;
            boxY = capY;
            boxW = capW;
            boxH = capH;
            boxValid = capW > 0 && capH > 0;
        } else if (now - lastComputeMs >= 250L) {
            lastComputeMs = now;
            computeBox(client, graphics);
        }
        if (boxValid) {
            BazzarConfig.State state = BazzarConfig.load();
            float scale = glowScale();
            int scaledW = Math.round(boxW * scale);
            int scaledH = Math.round(boxH * scale);
            int right = boxX + boxW;
            int centerY = boxY + boxH / 2;
            drawX = right - scaledW + state.scoreboardGlowOffsetX;
            drawY = centerY - scaledH / 2 + state.scoreboardGlowOffsetY;
            drawW = scaledW;
            drawH = scaledH;
            BazzarUi.drawRoundedGlowBorder(graphics, drawX, drawY, drawW, drawH, 3);
        }
    }

    static void onMouseClick(Screen screen, MouseButtonEvent event) {
        if (!boxValid || !(screen instanceof ChatScreen)) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.scoreboardGlowEnabled) {
            return;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (mouseX < drawX - 4 || mouseX > drawX + drawW + 4 || mouseY < drawY - 4 || mouseY > drawY + drawH + 4) {
            return;
        }
        if (event.button() == 1) {
            state.scoreboardGlowOffsetX = 0;
            state.scoreboardGlowOffsetY = 0;
            BazzarConfig.save(state);
            return;
        }
        dragging = true;
        dragGrabX = mouseX - state.scoreboardGlowOffsetX;
        dragGrabY = mouseY - state.scoreboardGlowOffsetY;
    }

    static void onMouseDrag(Screen screen, MouseButtonEvent event, double deltaX, double deltaY) {
        if (!dragging) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        state.scoreboardGlowOffsetX = (int) event.x() - dragGrabX;
        state.scoreboardGlowOffsetY = (int) event.y() - dragGrabY;
    }

    static void onMouseRelease(Screen screen, MouseButtonEvent event) {
        if (!dragging) {
            return;
        }
        dragging = false;
        BazzarConfig.save(BazzarConfig.load());
    }

    private static float glowScale() {
        BazzarConfig.State state = BazzarConfig.load();
        float scale = state == null ? 1.0f : state.scoreboardGlowScale;
        return Math.max(0.5f, Math.min(2.0f, scale));
    }

    static void onMouseScroll(double mouseX, double mouseY, double verticalAmount) {
        if (!boxValid || verticalAmount == 0.0d) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.scoreboardGlowEnabled) {
            return;
        }
        if (mouseX < drawX - 4 || mouseX > drawX + drawW + 4 || mouseY < drawY - 4 || mouseY > drawY + drawH + 4) {
            return;
        }
        float step = (float) (Math.signum(verticalAmount) * 0.05f);
        float clamped = Math.max(0.5f, Math.min(2.0f, glowScale() + step));
        if (Math.abs(clamped - state.scoreboardGlowScale) > 0.0001f) {
            state.scoreboardGlowScale = clamped;
            BazzarConfig.save(state);
        }
    }

    private static void computeBox(Minecraft client, GuiGraphics graphics) {
        boxValid = false;
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = null;
        PlayerTeam playerTeam = scoreboard.getPlayersTeam(client.player.getScoreboardName());
        if (playerTeam != null) {
            DisplaySlot slot = McCompat.teamSlot(playerTeam);
            if (slot != null) {
                objective = scoreboard.getDisplayObjective(slot);
            }
        }
        if (objective == null) {
            objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        }
        if (objective == null) {
            return;
        }

        NumberFormat numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        List<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective).stream()
                .filter(entry -> !entry.isHidden())
                .sorted(SCORE_DISPLAY_ORDER)
                .limit(15L)
                .toList();
        if (entries.isEmpty()) {
            return;
        }

        var font = client.font;
        int maxWidth = font.width(objective.getDisplayName());
        int separatorWidth = font.width(": ");
        for (PlayerScoreEntry entry : entries) {
            PlayerTeam ownerTeam = scoreboard.getPlayersTeam(entry.owner());
            Component name = PlayerTeam.formatNameForTeam(ownerTeam, entry.ownerName());
            int scoreWidth = font.width(entry.formatValue(numberFormat));
            maxWidth = Math.max(maxWidth, font.width(name) + (scoreWidth > 0 ? separatorWidth + scoreWidth : 0));
        }

        int count = entries.size();
        int bottom = graphics.guiHeight() / 2 + count * 9 / 3;
        int linesTop = bottom - count * 9;
        int boxTop = linesTop - 9 - 1;
        int boxLeft = graphics.guiWidth() - maxWidth - 3 - 2;
        int boxRight = graphics.guiWidth() - 3 + 2;
        boxX = boxLeft;
        boxY = boxTop;
        boxW = boxRight - boxLeft;
        boxH = bottom - boxTop;
        boxValid = true;
    }
}
