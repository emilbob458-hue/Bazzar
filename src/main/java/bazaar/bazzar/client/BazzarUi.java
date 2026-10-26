package bazaar.bazzar.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
//? if >=26.1 {
/*import com.mojang.blaze3d.GpuFormat;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
*///?} else {
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
//?}
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

final class BazzarUi {

    //? if >=26.1 {
    /*private static final VertexFormat ROUNDED_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build();

    private static final RenderPipeline ROUNDED_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("bazzar", "pipeline/rounded"))
            .withVertexShader(Identifier.fromNamespaceAndPath("bazzar", "core/rounded"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("bazzar", "core/rounded"))
            .withVertexBinding(0, ROUNDED_FORMAT)
            .build();
    *///?} else {
    private static final VertexFormat ROUNDED_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .build();

    private static final RenderPipeline ROUNDED_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("bazzar", "pipeline/rounded"))
            .withVertexShader(Identifier.fromNamespaceAndPath("bazzar", "core/rounded"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("bazzar", "core/rounded"))
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(ROUNDED_FORMAT, VertexFormat.Mode.QUADS)
            .build();
    //?}

    private static final int MODE_FILL = 0;
    private static final int MODE_BORDER = 1;
    private static final int MODE_FILL_LEFT_ROUNDED = 3;

    private BazzarUi() {
    }

    private record RoundedQuad(
            Matrix3x2f pose,
            float x0,
            float y0,
            float x1,
            float y1,
            float sdfHalfW,
            float sdfHalfH,
            float radius,
            int mode,
            int c00,
            int c10,
            int c11,
            int c01,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds
    ) implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer vc) {
            float cx = (x0 + x1) / 2.0f;
            float cy = (y0 + y1) / 2.0f;
            int hw = Math.round(sdfHalfW * 16.0f);
            int hh = Math.round(sdfHalfH * 16.0f);
            int r = Math.round(radius * 16.0f);
            vc.addVertexWith2DPose(pose, x0, y0).setUv(x0 - cx, y0 - cy).setUv1(hw, hh).setUv2(r, mode).setColor(c00);
            vc.addVertexWith2DPose(pose, x0, y1).setUv(x0 - cx, y1 - cy).setUv1(hw, hh).setUv2(r, mode).setColor(c01);
            vc.addVertexWith2DPose(pose, x1, y1).setUv(x1 - cx, y1 - cy).setUv1(hw, hh).setUv2(r, mode).setColor(c11);
            vc.addVertexWith2DPose(pose, x1, y0).setUv(x1 - cx, y0 - cy).setUv1(hw, hh).setUv2(r, mode).setColor(c10);
        }

        @Override
        public RenderPipeline pipeline() {
            return ROUNDED_PIPELINE;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }

    private static void submitRounded(GuiGraphics graphics, float x0, float y0, float x1, float y1,
            float sdfHalfW, float sdfHalfH, float radius, int mode, int c00, int c10, int c11, int c01) {
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle(
                (int) Math.floor(x0), (int) Math.floor(y0),
                (int) Math.ceil(x1 - x0), (int) Math.ceil(y1 - y0)).transformMaxBounds(pose);
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
        }
        if (bounds == null) {
            return;
        }
        //? if >=26.1 {
        /*graphics.guiRenderState.addGuiElement(new RoundedQuad(
        *///?} else
        graphics.guiRenderState.submitGuiElement(new RoundedQuad(
                pose, x0, y0, x1, y1, sdfHalfW, sdfHalfH, radius, mode, c00, c10, c11, c01, scissor, bounds));
    }

    static void fillRoundedRect(GuiGraphics graphics, int x, int y, int w, int h, int radius, int color) {
        float r = Math.min(radius, Math.min(w, h) / 2.0f);
        submitRounded(graphics, x, y, x + w, y + h, w / 2.0f, h / 2.0f, r, MODE_FILL, color, color, color, color);
    }

    static void fillRoundedGradient(GuiGraphics graphics, int x, int y, int w, int h, int radius, int colorTop, int colorBottom) {
        float r = Math.min(radius, Math.min(w, h) / 2.0f);
        submitRounded(graphics, x, y, x + w, y + h, w / 2.0f, h / 2.0f, r, MODE_FILL, colorTop, colorTop, colorBottom, colorBottom);
    }

    static void fillLeftRoundedRect(GuiGraphics graphics, int x, int y, int w, int h, int radius, int color) {
        float r = Math.min(radius, Math.min(w, h) / 2.0f);
        submitRounded(graphics, x, y, x + w, y + h, w / 2.0f, h / 2.0f, r, MODE_FILL_LEFT_ROUNDED, color, color, color, color);
    }

    static void fillPill(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        fillRoundedRect(graphics, x, y, w, h, h / 2, color);
    }

    static void fillPillSmooth(GuiGraphics graphics, float x, float y, float w, float h, int color) {
        submitRounded(graphics, x, y, x + w, y + h, w / 2.0f, h / 2.0f, Math.min(w, h) / 2.0f, MODE_FILL, color, color, color, color);
    }

    static void fillCircleSmooth(GuiGraphics graphics, float cx, float cy, float radius, int color) {
        submitRounded(graphics, cx - radius, cy - radius, cx + radius, cy + radius, radius, radius, radius, MODE_FILL, color, color, color, color);
    }

    static void fillCircle(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        fillCircleSmooth(graphics, cx, cy, radius, color);
    }

    static void drawRoundedGlowBorder(GuiGraphics graphics, int x, int y, int w, int h, int radius) {
        BazzarTheme theme = BazzarTheme.current();
        int[] palette = {
                theme.light() & 0xFFFFFF,
                theme.rgb,
                theme.dark() & 0xFFFFFF,
                theme.deep() & 0xFFFFFF,
                theme.rgb
        };
        float time = (System.currentTimeMillis() % 9000L) / 9000.0f;
        float perimeter = 2.0f * (w + h);
        int c00 = colorAt(palette, time, 0.0f, 0xFF);
        int c10 = colorAt(palette, time, w / perimeter, 0xFF);
        int c11 = colorAt(palette, time, (w + h) / perimeter, 0xFF);
        int c01 = colorAt(palette, time, (2.0f * w + h) / perimeter, 0xFF);
        submitRounded(graphics, x - 6, y - 6, x + w + 6, y + h + 6,
                (w + 2) / 2.0f, (h + 2) / 2.0f, radius + 1, MODE_BORDER, c00, c10, c11, c01);
    }

    static float openProgress(long openedAtMs) {
        float t = (System.currentTimeMillis() - openedAtMs) / 140.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }

    static float screenAnim(long openedAtMs, long closingSinceMs) {
        float p = openProgress(openedAtMs);
        if (closingSinceMs >= 0L) {
            float t = (System.currentTimeMillis() - closingSinceMs) / 130.0f;
            t = Math.max(0.0f, Math.min(1.0f, t));
            p *= 1.0f - t * t;
        }
        return p;
    }

    static MutableComponent gradientText(String text, int fromRgb, int toRgb, boolean bold) {
        MutableComponent out = Component.empty();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            float t = n <= 1 ? 0.0f : (float) i / (n - 1);
            int rgb = lerpColor(0xFF000000 | fromRgb, 0xFF000000 | toRgb, t) & 0xFFFFFF;
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(bold)));
        }
        return out;
    }

    static int lerpColor(int from, int to, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int fa = (from >> 24) & 0xFF;
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int ta = (to >> 24) & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int a = fa + Math.round((ta - fa) * t);
        int r = fr + Math.round((tr - fr) * t);
        int g = fg + Math.round((tg - fg) * t);
        int b = fb + Math.round((tb - fb) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int colorAt(int[] palette, float time, float perimeterFraction, int alpha) {
        float t = perimeterFraction + time;
        t -= (float) Math.floor(t);
        float scaled = t * palette.length;
        int index = (int) scaled;
        float local = scaled - index;
        int from = palette[index % palette.length];
        int to = palette[(index + 1) % palette.length];
        int rgb = lerpColor(0xFF000000 | from, 0xFF000000 | to, local) & 0xFFFFFF;
        return (alpha << 24) | rgb;
    }
}
