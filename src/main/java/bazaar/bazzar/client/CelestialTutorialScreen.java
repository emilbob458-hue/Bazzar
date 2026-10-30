package bazaar.bazzar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// the first look at the Celestial Market: a few cards with Back, Next and Skip, shown once and
// then only when asked for with /cbtutorial
final class CelestialTutorialScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int TITLE_H = 34;
    private static final int LINE_H = 10;
    private static final int FOOTER_H = 34;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 16;

    private static final int PANEL_BG = 0xF00C0E12;
    private static final int PANEL_EDGE = 0x1FFFFFFF;
    private static final int TEXT = 0xFFE6E9EE;
    private static final int MUTED = 0xFF8E97A6;
    private static final int GOLD = 0xFFFFD75A;

    private final CelestialPanel.View openAfter;
    private final long openedAtMs = System.currentTimeMillis();
    private int index;
    private int left;
    private int top;
    private int panelH;

    CelestialTutorialScreen(CelestialPanel.View openAfter) {
        super(Component.literal("Celestial Market"));
        this.openAfter = openAfter;
    }

    @Override
    protected void init() {
        reposition();
    }

    private void reposition() {
        CelestialTutorial.Step step = CelestialTutorial.step(index);
        int bodyH = step.lines().size() * LINE_H;
        if (step.command() != null) {
            bodyH += 18;
        }
        panelH = TITLE_H + bodyH + FOOTER_H;
        left = (width - PANEL_W) / 2;
        top = Math.max(8, (height - panelH) / 2);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        reposition();
        BazzarTheme theme = BazzarTheme.current();
        float open = BazzarUi.openProgress(openedAtMs);
        graphics.fill(0, 0, width, height, (int) (0xC0 * open) << 24);

        BazzarUi.fillRoundedRect(graphics, left - 1, top - 1, PANEL_W + 2, panelH + 2, 8, PANEL_EDGE);
        BazzarUi.fillRoundedRect(graphics, left, top, PANEL_W, panelH, 7, PANEL_BG);

        CelestialTutorial.Step step = CelestialTutorial.step(index);
        Component title = BazzarUi.gradientText(step.title(), theme.light() & 0xFFFFFF, theme.rgb, true);
        graphics.drawString(font, title, left + 14, top + 13, 0xFFFFFFFF, false);
        String counter = (index + 1) + " / " + CelestialTutorial.count();
        graphics.drawString(font, counter, left + PANEL_W - 14 - font.width(counter), top + 13, MUTED, false);
        graphics.fill(left + 12, top + TITLE_H - 8, left + PANEL_W - 12, top + TITLE_H - 7, theme.accentDim());

        int y = top + TITLE_H;
        for (String line : step.lines()) {
            graphics.drawString(font, line, left + 14, y, TEXT, false);
            y += LINE_H;
        }
        if (step.command() != null) {
            int w = font.width(step.command()) + 12;
            BazzarUi.fillRoundedRect(graphics, left + 14, y + 3, w, 13, 3, theme.soft(0x33));
            graphics.drawString(font, step.command(), left + 20, y + 6, GOLD, false);
        }

        // a dot per card, the one you are on filled in
        int dots = CelestialTutorial.count();
        int dotsW = dots * 8;
        for (int i = 0; i < dots; i++) {
            BazzarUi.fillCircle(graphics, left + (PANEL_W - dotsW) / 2 + i * 8 + 3, top + panelH - 21, 2,
                    i == index ? theme.accent() : 0xFF2A3039);
        }

        drawButton(graphics, backBounds(), index == 0 ? "Skip" : "Back", mouseX, mouseY, false);
        drawButton(graphics, nextBounds(), CelestialTutorial.isLast(index) ? "Got it" : "Next", mouseX, mouseY, true);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawButton(GuiGraphics graphics, int[] bounds, String label, int mouseX, int mouseY, boolean primary) {
        BazzarTheme theme = BazzarTheme.current();
        boolean hovered = inside(mouseX, mouseY, bounds);
        int bg = primary ? (hovered ? theme.accent() : theme.accentDim()) : (hovered ? theme.soft(0x40) : theme.soft(0x22));
        BazzarUi.fillRoundedRect(graphics, bounds[0], bounds[1], bounds[2], bounds[3], 4, bg);
        int color = primary ? theme.readableOnAccent() : hovered ? TEXT : MUTED;
        graphics.drawString(font, label, bounds[0] + (bounds[2] - font.width(label)) / 2, bounds[1] + 4, color, false);
    }

    private int[] backBounds() {
        return new int[]{left + 14, top + panelH - 24, BUTTON_W, BUTTON_H};
    }

    private int[] nextBounds() {
        return new int[]{left + PANEL_W - 14 - BUTTON_W, top + panelH - 24, BUTTON_W, BUTTON_H};
    }

    private static boolean inside(int mouseX, int mouseY, int[] bounds) {
        return mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (inside(mouseX, mouseY, nextBounds())) {
            if (CelestialTutorial.isLast(index)) {
                finish();
            } else {
                index = CelestialTutorial.clamp(index + 1);
            }
            return true;
        }
        if (inside(mouseX, mouseY, backBounds())) {
            if (index == 0) {
                finish();
            } else {
                index = CelestialTutorial.clamp(index - 1);
            }
            return true;
        }
        return super.mouseClicked(event, bl);
    }

    // reading it once is enough; it only comes back if you ask for it
    private void finish() {
        CelestialTutorial.markSeen();
        Minecraft client = Minecraft.getInstance();
        McCompat.setScreen(client, openAfter == null ? null : BazaarTrackerScreen.openCelestial(openAfter));
    }

    @Override
    public void onClose() {
        finish();
    }
}
