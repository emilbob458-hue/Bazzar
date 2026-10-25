package bazaar.bazzar.mixin;

import bazaar.bazzar.client.BlockBreakCounter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelBlockMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void bazzar$countServerBreaks(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        BlockBreakCounter.onServerBlockChange(pos, ((ClientLevel) (Object) this).getBlockState(pos), state);
    }
}
