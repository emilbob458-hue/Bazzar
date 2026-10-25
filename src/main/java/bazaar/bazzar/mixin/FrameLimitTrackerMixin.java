package bazaar.bazzar.mixin;

import bazaar.bazzar.client.DisableFramerateLimiter;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class FrameLimitTrackerMixin {
    @Shadow
    private int framerateLimit;

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void bazzar$getFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        if (DisableFramerateLimiter.disableFrameLimit) {
            cir.setReturnValue(this.framerateLimit);
        }
    }
}
