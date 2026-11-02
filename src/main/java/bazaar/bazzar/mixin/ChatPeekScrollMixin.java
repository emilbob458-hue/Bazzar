package bazaar.bazzar.mixin;

import bazaar.bazzar.client.ChatPeek;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// while chat peek is held the mouse wheel scrolls the chat, not the hotbar
@Mixin(MouseHandler.class)
public abstract class ChatPeekScrollMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void bazzar$peekScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (window == this.minecraft.getWindow().handle() && ChatPeek.scroll(this.minecraft, yOffset)) {
            ci.cancel();
        }
    }
}
