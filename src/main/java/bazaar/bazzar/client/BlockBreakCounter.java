package bazaar.bazzar.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

public final class BlockBreakCounter {
    private static final long CHAIN_WINDOW_MS = 1_500L;
    private static final double CHAIN_RADIUS_SQ = 6.0d * 6.0d;

    private static BlockPos lastOwnBreak = null;
    private static long lastOwnBreakMs = 0L;

    private BlockBreakCounter() {
    }

    static void onOwnBreak(BlockPos pos) {
        lastOwnBreak = pos.immutable();
        lastOwnBreakMs = System.currentTimeMillis();
        count();
    }

    public static void onServerBlockChange(BlockPos pos, BlockState before, BlockState after) {
        if (lastOwnBreak == null || System.currentTimeMillis() - lastOwnBreakMs > CHAIN_WINDOW_MS) {
            return;
        }
        if (before.isAir() || !before.getFluidState().isEmpty() || pos.distSqr(lastOwnBreak) > CHAIN_RADIUS_SQ) {
            return;
        }
        if (after.isAir() || wasHarvested(before, after)) {
            count();
        }
    }

    private static boolean wasHarvested(BlockState before, BlockState after) {
        if (before.getBlock() != after.getBlock()) {
            return false;
        }
        for (Property<?> property : before.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(age.getName())) {
                return after.getValue(age) < before.getValue(age);
            }
        }
        return false;
    }

    private static void count() {
        SessionProfitHud.onBlockBroken();
        PlayerStats.onBlockBroken();
    }
}
