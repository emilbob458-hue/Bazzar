package bazaar.bazzar.mixin;

import bazaar.bazzar.client.CookieController;
//? if >=26.1 {
/*import net.minecraft.client.gui.Hud;
*///?} else
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.1 {
/*@Mixin(Hud.class)
*///?} else
@Mixin(Gui.class)
public class GuiTitleMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void bazzar$captureTitle(Component title, CallbackInfo ci) {
        if (title != null) {
            CookieController.onTitle(title.getString());
        }
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void bazzar$captureSubtitle(Component subtitle, CallbackInfo ci) {
        if (subtitle != null) {
            CookieController.onTitle(subtitle.getString());
        }
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void bazzar$captureActionBar(Component message, boolean animateColor, CallbackInfo ci) {
        if (message != null) {
            CookieController.onTitle(message.getString());
        }
    }
}
