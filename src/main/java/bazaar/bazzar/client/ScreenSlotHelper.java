package bazaar.bazzar.client;

import java.lang.reflect.Field;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;

final class ScreenSlotHelper {
    private static Field hoveredSlotField;
    private static Field[] slotFields;
    private static Field leftPosField;
    private static Field topPosField;

    private ScreenSlotHelper() {
    }

    static Slot getHoveredSlot(AbstractContainerScreen<?> screen) {
        Slot slot = getSlotFromKnownField(screen);
        if (slot != null) {
            return slot;
        }

        try {
            if (slotFields == null) {
                slotFields = AbstractContainerScreen.class.getDeclaredFields();
                for (Field field : slotFields) {
                    if (Slot.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                    }
                }
            }

            for (Field field : slotFields) {
                if (!Slot.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object value = field.get(screen);
                if (value instanceof Slot candidate && !candidate.getItem().isEmpty()) {
                    return candidate;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    static int getLeft(AbstractContainerScreen<?> screen) {
        int left = getScreenPos(screen, true);
        return left != 0 ? left : (screen.width - 176) / 2;
    }

    static int getTop(AbstractContainerScreen<?> screen) {
        int top = getScreenPos(screen, false);
        if (top != 0) {
            return top;
        }
        int rows = screen.getMenu() instanceof ChestMenu chest ? chest.getRowCount() : 3;
        return (screen.height - (114 + rows * 18)) / 2;
    }

    static Slot getSlotAt(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        int left = getScreenPos(screen, true);
        int top = getScreenPos(screen, false);
        for (Slot slot : screen.getMenu().slots) {
            int slotX = left + slot.x;
            int slotY = top + slot.y;
            if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                return slot;
            }
        }
        return null;
    }

    private static Slot getSlotFromKnownField(AbstractContainerScreen<?> screen) {
        try {
            if (hoveredSlotField == null) {
                hoveredSlotField = findKnownHoveredField();
            }
            if (hoveredSlotField == null) {
                return null;
            }
            Object value = hoveredSlotField.get(screen);
            if (value instanceof Slot slot) {
                return slot;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Field findKnownHoveredField() {
        String[] candidates = {"hoveredSlot", "slotUnderMouse", "focusedSlot"};
        for (String name : candidates) {
            try {
                Field field = AbstractContainerScreen.class.getDeclaredField(name);
                if (Slot.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static int getScreenPos(AbstractContainerScreen<?> screen, boolean left) {
        try {
            if (left && leftPosField == null) {
                leftPosField = findScreenPosField(true);
            }
            if (!left && topPosField == null) {
                topPosField = findScreenPosField(false);
            }
            Field field = left ? leftPosField : topPosField;
            if (field != null) {
                return field.getInt(screen);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return 0;
    }

    private static Field findScreenPosField(boolean left) {
        String[] names = left ? new String[]{"leftPos", "x"} : new String[]{"topPos", "y"};
        for (String name : names) {
            try {
                Field field = AbstractContainerScreen.class.getDeclaredField(name);
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
