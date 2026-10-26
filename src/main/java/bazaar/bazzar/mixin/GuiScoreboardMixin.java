package bazaar.bazzar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bazaar.bazzar.client.ScoreboardOverlay;
//? if >=26.1 {
/*import net.minecraft.client.gui.Hud;
*///?} else
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.Objective;

//? if >=26.1 {
/*@Mixin(Hud.class)
*///?} else
@Mixin(Gui.class)
public class GuiScoreboardMixin {

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"))
    private void bazzar$beginScoreboard(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
        ScoreboardOverlay.beginScoreboardCapture();
    }

    @Redirect(method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void bazzar$captureScoreboardFill(GuiGraphics instance, int x1, int y1, int x2, int y2, int color) {
        instance.fill(x1, y1, x2, y2, color);
        ScoreboardOverlay.recordScoreboardFill(x1, y1, x2, y2);
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("TAIL"))
    private void bazzar$endScoreboard(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
        ScoreboardOverlay.endScoreboardCapture();
    }
}
