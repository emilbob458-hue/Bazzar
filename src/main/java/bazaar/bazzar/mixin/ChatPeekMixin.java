package bazaar.bazzar.mixin;

import bazaar.bazzar.client.ChatPeek;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

// while chat peek is held the hud chat draws like the chat box is open: full height, nothing faded
@Mixin(ChatComponent.class)
public abstract class ChatPeekMixin {
    //? if >=26.1 {
    /*@ModifyVariable(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at = @At("HEAD"), argsOnly = true)
    private ChatComponent.DisplayMode bazzar$peekMode(ChatComponent.DisplayMode mode) {
        return ChatPeek.active() && !mode.foreground ? ChatComponent.DisplayMode.FOREGROUND : mode;
    }
    *///?} else {
    @ModifyVariable(method = "render(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private boolean bazzar$peekFocused(boolean focused) {
        return focused || ChatPeek.active();
    }
    //?}

    @Redirect(method = "getHeight()I", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z"))
    private boolean bazzar$peekHeight(ChatComponent chat) {
        return chat.isChatFocused() || ChatPeek.active();
    }
}
