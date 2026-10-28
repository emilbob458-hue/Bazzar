package bazaar.bazzar.client;

import java.util.LinkedHashMap;
import java.util.Map;

final class HudBoxes {
    private static final int GAP = 2;
    private static final Map<String, int[]> BOXES = new LinkedHashMap<>();

    private HudBoxes() {
    }

    static void hide(String id) {
        BOXES.remove(id);
    }

    static int place(String id, int x, int y, int w, int h, int screenH) {
        int placedY = y;
        for (int tries = 0; tries <= BOXES.size(); tries++) {
            int[] hit = hit(id, x, placedY, w, h);
            if (hit == null) {
                break;
            }
            placedY = hit[1] + hit[3] + GAP;
        }
        if (placedY + h > screenH) {
            placedY = y;
        }
        BOXES.put(id, new int[]{x, placedY, w, h});
        return placedY;
    }

    static int[] drag(String id, int oldX, int oldY, int newX, int newY) {
        int[] box = BOXES.get(id);
        if (box == null) {
            return new int[]{newX, newY};
        }
        int w = box[2];
        int h = box[3];
        if (hit(id, newX, newY, w, h) == null) {
            return new int[]{newX, newY};
        }
        if (hit(id, newX, oldY, w, h) == null) {
            return new int[]{newX, oldY};
        }
        if (hit(id, oldX, newY, w, h) == null) {
            return new int[]{oldX, newY};
        }
        return new int[]{oldX, oldY};
    }

    private static int[] hit(String id, int x, int y, int w, int h) {
        for (Map.Entry<String, int[]> entry : BOXES.entrySet()) {
            int[] b = entry.getValue();
            if (!entry.getKey().equals(id)
                    && x < b[0] + b[2] + GAP && x + w + GAP > b[0] && y < b[1] + b[3] + GAP && y + h + GAP > b[1]) {
                return b;
            }
        }
        return null;
    }
}
