package bazaar.bazzar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.Items;

// while a fishing rod is out nothing of ours pops up or opens a menu on its own: the automatic
// commands, scans and titles wait until the rod is put away. A server menu that's already open
// still gets handled, so something caught halfway through isn't left stuck on it.
final class FishingPause {
    private FishingPause() {
    }

    static boolean active(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        Screen screen = McCompat.screen(client);
        if (screen instanceof AbstractContainerScreen<?> && !(screen instanceof InventoryScreen)) {
            return false;
        }
        return client.player.getMainHandItem().is(Items.FISHING_ROD) || client.player.getOffhandItem().is(Items.FISHING_ROD);
    }
}
