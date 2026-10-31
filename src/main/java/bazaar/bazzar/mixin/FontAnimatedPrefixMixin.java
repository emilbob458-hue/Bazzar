package bazaar.bazzar.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import bazaar.bazzar.client.PrefixPalette;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public class FontAnimatedPrefixMixin {
    @WrapOperation(
            method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Style;getColor()Lnet/minecraft/network/chat/TextColor;"),
            require = 0)
    private TextColor bazzar$animatePrefix(Style style, Operation<TextColor> original, @Local(argsOnly = true, ordinal = 0) int index) {
        TextColor color = original.call(style);
        if (color != null && (color.getValue() & 0xFFFFFF) == PrefixPalette.SENTINEL) {
            return TextColor.fromRgb(PrefixPalette.animate(index));
        }
        return color;
    }
}
