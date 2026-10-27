package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class FarmWarpScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 380;
    private static final int TITLE_H = 28;
    private static final int SECTION_HEADER_H = 14;
    private static final int ICON_ROW_H = 22;
    private static final int SECTION_PAD = 6;
    private static final int SECTION_H = SECTION_HEADER_H + ICON_ROW_H + SECTION_PAD;
    private static final int ICON_STEP = 22;
    private static final int BOTTOM_PAD = 8;
    private static final int HINT_H = 14;
    private static final String WORLD_HINT = "Warp to world ▶";
    private static final String FAVORITE_HINT = "Right click a crop to favorite it";
    private static final String SCAN_LABEL = "Scan mobs";
    private static final String RESCAN_LABEL = "Rescan mobs";
    private static final int FAVORITE_GOLD = 0xFFD75A;
    private static final int TARGET_ROW_H = 18;
    private static final int MAX_TARGETS = 8;
    private static final int TARGET_REFRESH_TICKS = 20;
    private static final int MUTED = 0xFF8E97A6;
    private static final int DONE_GREEN = 0xFF86EFAC;
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("([\\d.,]+\\s*[kmbt]?)\\s*/\\s*([\\d.,]+\\s*[kmbt]?)");

    private static final int HEADER_TEXT = 0xFFFFFFFF;

    private final long openedAtMs = System.currentTimeMillis();
    private long closingSinceMs = -1L;
    private int left;
    private int top;
    private int panelW;
    private int scroll;
    private Component[] worldLabels;
    private Component favoritesLabel;
    private Component targetsLabel;
    private int refreshTicks;
    private final Map<WorldTab, List<FarmEntry>> worldCrops = new EnumMap<>(WorldTab.class);
    private final List<FavEntry> favorites = new ArrayList<>();
    private final Set<String> favoriteCommands = new HashSet<>();
    private final List<TargetEntry> targets = new ArrayList<>();

    FarmWarpScreen() {
        super(Component.literal("Farm Warps"));
    }

    @Override
    protected void init() {
        if (worldCrops.isEmpty()) {
            buildCrops();
        }
        WorldTab[] worlds = WorldTab.values();
        worldLabels = new Component[worlds.length];
        for (int i = 0; i < worlds.length; i++) {
            worldLabels[i] = BazzarUi.gradientText(worlds[i].displayName, worlds[i].gradFrom, worlds[i].gradTo, true);
        }
        favoritesLabel = BazzarUi.gradientText("★ Favorites", FAVORITE_GOLD, 0xE39B3A, true);
        targetsLabel = BazzarUi.gradientText("⚑ Quests & Pinned", 0x7DD3FC, 0xB28CFF, true);
        reloadFavorites();
        reloadTargets();
    }

    private void updatePanelWidth() {
        int needed = targets.isEmpty() ? contentWidth() : Math.max(contentWidth(), 260);
        panelW = Math.min(Math.min(needed, MAX_PANEL_WIDTH), Math.max(240, width - 8));
    }

    private void reloadTargets() {
        targets.clear();
        Set<String> seen = new HashSet<>();
        for (GuildQuestScanner.QuestEntry quest : GuildQuestScanner.getLastEntries()) {
            String text = SbText.normalizeText(quest.name());
            double progress = amount(quest.leftValue());
            double required = amount(quest.rightValue());
            if (required < 0d) {
                required = SbText.firstAmount(text);
                progress = -1d;
            }
            addTarget(text, "GQ", progress, required, seen);
        }

        List<ScoreboardReader.Line> lines = new ArrayList<>(ScoreboardReader.readSidebar(Minecraft.getInstance()));
        lines.sort(Comparator.comparingInt(ScoreboardReader.Line::score).reversed());
        boolean inPinned = false;
        for (ScoreboardReader.Line line : lines) {
            String text = line.normalizedText().trim();
            int idx = text.indexOf("pinned");
            if (idx >= 0) {
                inPinned = true;
                text = text.substring(idx + "pinned".length());
            } else if (!inPinned) {
                continue;
            } else if (compact(text).isEmpty()) {
                break;
            }
            Matcher matcher = PROGRESS_PATTERN.matcher(text);
            if (matcher.find()) {
                addTarget(text, "Pin", SbText.firstAmount(matcher.group(1)), SbText.firstAmount(matcher.group(2)), seen);
            } else {
                addTarget(text, "Pin", -1d, SbText.firstAmount(text), seen);
            }
        }
        updatePanelWidth();
    }

    private void addTarget(String text, String source, double progress, double required, Set<String> seen) {
        if (targets.size() >= MAX_TARGETS) {
            return;
        }
        FarmMatch match = matchFarm(text);
        if (match == null || !seen.add(source + "|" + match.farm().command() + "|" + match.key())) {
            return;
        }
        TargetType type = match.type();
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("kill") || lower.startsWith("slay")) {
            type = TargetType.COMBAT;
        } else if (lower.startsWith("mine")) {
            type = TargetType.MINING;
        }
        targets.add(new TargetEntry(match.label(), source, type, progress, required, match.farm(), match.world()));
    }

    // Longest name wins, so "Ochre Froglight" beats "Froglight".
    private FarmMatch matchFarm(String text) {
        String wanted = compact(text);
        if (wanted.isEmpty()) {
            return null;
        }
        FarmMatch best = null;
        for (WorldTab world : WorldTab.values()) {
            for (FarmEntry farm : worldCrops.getOrDefault(world, List.of())) {
                best = betterMatch(best, wanted, farm.name(), farm, world, TargetType.FARMING);
                FarmMobScanner.MobInfo info = FarmMobScanner.get(farm.name());
                if (info != null) {
                    best = betterMatch(best, wanted, info.drop, farm, world, TargetType.COMBAT);
                    best = betterMatch(best, wanted, info.bossDrop, farm, world, TargetType.COMBAT);
                    best = betterMatch(best, wanted, info.mob, farm, world, TargetType.COMBAT);
                }
            }
        }
        return best;
    }

    private static FarmMatch betterMatch(FarmMatch best, String wanted, String label, FarmEntry farm, WorldTab world, TargetType type) {
        String key = compact(label);
        if (key.length() < 3 || !wanted.contains(key)) {
            return best;
        }
        if (best != null && best.key().length() >= key.length()) {
            return best;
        }
        return new FarmMatch(farm, world, type, label, key);
    }

    private static String compact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static double amount(String value) {
        return value == null || "N/A".equals(value) ? -1d : SbText.firstAmount(value.toLowerCase(Locale.ROOT));
    }

    private void reloadFavorites() {
        favorites.clear();
        favoriteCommands.clear();
        List<String> saved = BazzarConfig.load().farmFavorites;
        if (saved == null) {
            return;
        }
        for (String command : saved) {
            FavEntry entry = findFarm(command);
            if (entry != null && favoriteCommands.add(command)) {
                favorites.add(entry);
            }
        }
    }

    private FavEntry findFarm(String command) {
        for (WorldTab world : WorldTab.values()) {
            for (FarmEntry farm : worldCrops.getOrDefault(world, List.of())) {
                if (farm.command().equals(command)) {
                    return new FavEntry(farm, world);
                }
            }
        }
        return null;
    }

    private void toggleFavorite(String command) {
        BazzarConfig.State state = BazzarConfig.load();
        if (state.farmFavorites == null) {
            state.farmFavorites = new ArrayList<>();
        }
        if (!state.farmFavorites.remove(command)) {
            state.farmFavorites.add(command);
        }
        BazzarConfig.save(state);
        reloadFavorites();
    }

    private int contentWidth() {
        int hintW = font.width(WORLD_HINT);
        int needed = font.width(getTitle()) + 48;
        needed = Math.max(needed, 16 + font.width(FAVORITE_HINT) + 12 + font.width(RESCAN_LABEL) + 10);
        WorldTab[] worlds = WorldTab.values();
        for (int i = 0; i < worlds.length; i++) {
            int nameRowW = 16 + font.width(worldLabels[i]) + 12 + hintW + 10;
            int crops = worldCrops.getOrDefault(worlds[i], List.of()).size();
            int iconRowW = crops == 0 ? 0 : 16 + (crops - 1) * ICON_STEP + 20 + 8;
            needed = Math.max(needed, Math.max(nameRowW, iconRowW));
        }
        return needed;
    }

    private int panelHeight() {
        return TITLE_H + topSectionsH() + WorldTab.values().length * SECTION_H + HINT_H + BOTTOM_PAD;
    }

    private int topSectionsH() {
        return favSectionH() + targetSectionH();
    }

    private int targetSectionH() {
        return targets.isEmpty() ? 0 : SECTION_HEADER_H + targets.size() * TARGET_ROW_H + SECTION_PAD;
    }

    private int targetSectionY() {
        return top + TITLE_H + favSectionH() + 4;
    }

    private int targetRowY(int index) {
        return targetSectionY() + SECTION_HEADER_H + index * TARGET_ROW_H;
    }

    private int favPerRow() {
        return Math.max(1, (panelW - 16 - 10) / ICON_STEP);
    }

    private int favRows() {
        return favorites.isEmpty() ? 0 : (favorites.size() + favPerRow() - 1) / favPerRow();
    }

    private int favSectionH() {
        return favorites.isEmpty() ? 0 : SECTION_HEADER_H + favRows() * ICON_ROW_H + SECTION_PAD;
    }

    private int favSectionY() {
        return top + TITLE_H + 4;
    }

    private int favIconX(int index) {
        return left + 16 + (index % favPerRow()) * ICON_STEP;
    }

    private int favIconY(int index) {
        return favSectionY() + SECTION_HEADER_H + 2 + (index / favPerRow()) * ICON_ROW_H;
    }

    private void reposition() {
        int panelH = panelHeight();
        left = Math.max(4, (width - panelW) / 2);
        if (panelH <= height - 8) {
            top = (height - panelH) / 2;
            scroll = 0;
        } else {
            int maxScroll = panelH - (height - 8);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            top = 4 - scroll;
        }
    }

    private void buildCrops() {
        worldCrops.put(WorldTab.OVERWORLD, List.of(
                farm("Wheat", "farms wheat", new ItemStack(Items.WHEAT)),
                farm("Carrot", "farms carrot", new ItemStack(Items.CARROT)),
                farm("Potato", "farms potato", new ItemStack(Items.POTATO)),
                farm("Beetroot", "farms beetroot", new ItemStack(Items.BEETROOT)),
                farm("Cabbage", "farms cabbage", new ItemStack(Items.DRIED_KELP)),
                farm("Pumpkin", "farms pumpkin", new ItemStack(Items.PUMPKIN)),
                farm("Melon", "farms melon", new ItemStack(Items.MELON)),
                farm("Pineapple", "farms pineapple", new ItemStack(Items.YELLOW_GLAZED_TERRACOTTA))
        ));
        worldCrops.put(WorldTab.WORLD_2, List.of(
                farm("Froglight", "farm froglight", new ItemStack(Items.PEARLESCENT_FROGLIGHT)),
                farm("Pale Moss", "farm palemoss", new ItemStack(Items.PALE_MOSS_BLOCK)),
                farm("Ice", "farm ice", new ItemStack(Items.ICE)),
                farm("Bone", "farm bone", new ItemStack(Items.BONE)),
                farm("Crying Obsidian", "farm cryingobsidian", new ItemStack(Items.CRYING_OBSIDIAN)),
                farm("Ochre Froglight", "farm ochrefroglight", new ItemStack(Items.OCHRE_FROGLIGHT)),
                farm("Lapis", "farm lapis", new ItemStack(Items.LAPIS_LAZULI)),
                farm("Quartz", "farm quartz", new ItemStack(Items.QUARTZ))
        ));
        worldCrops.put(WorldTab.WORLD_3, List.of(
                farm("Clay", "farm clay", new ItemStack(Items.CLAY)),
                farm("Mud Bricks", "farm mudbricks", new ItemStack(Items.MUD_BRICKS)),
                farm("Dried Kelp", "farm driedkelp", new ItemStack(Items.DRIED_KELP_BLOCK)),
                farm("Mangrove", "farm mangrove", new ItemStack(Items.MANGROVE_LOG)),
                farm("Bamboo", "farm bamboo", new ItemStack(Items.BAMBOO)),
                farm("Red Mushroom", "farm redmushroom", new ItemStack(Items.RED_MUSHROOM)),
                farm("Wet Sponge", "farm wetsponge", new ItemStack(Items.WET_SPONGE)),
                farm("Verdant Froglight", "farm verdantfroglight", new ItemStack(Items.VERDANT_FROGLIGHT))
        ));
        worldCrops.put(WorldTab.WORLD_4, List.of(
                farm("Hay Bale", "farm haybale", new ItemStack(Items.HAY_BLOCK)),
                farm("Flowering Azalea", "farm floweringazalea", new ItemStack(Items.FLOWERING_AZALEA)),
                farm("Cherry Blossom", "farm cherryblossom", new ItemStack(Items.CHERRY_LEAVES)),
                farm("Bee Nest", "farm beenest", new ItemStack(Items.BEE_NEST)),
                farm("Beehive", "farm beehive", new ItemStack(Items.BEEHIVE)),
                farm("Resin", "farm resin", new ItemStack(Items.RESIN_BLOCK)),
                farm("Glowstone", "farm glowstone", new ItemStack(Items.GLOWSTONE)),
                farm("Honeycomb", "farm honeycomb", new ItemStack(Items.HONEYCOMB_BLOCK))
        ));
        worldCrops.put(WorldTab.WORLD_5, List.of(
                farm("Red Paint", "farm redpaint", new ItemStack(Items.RED_DYE)),
                farm("Blue Paint", "farm bluepaint", new ItemStack(Items.BLUE_DYE)),
                farm("Green Paint", "farm greenpaint", new ItemStack(Items.GREEN_DYE)),
                farm("Purple Paint", "farm purplepaint", new ItemStack(Items.PURPLE_DYE)),
                farm("Magenta Paint", "farm magentapaint", new ItemStack(Items.MAGENTA_DYE)),
                farm("Brown Paint", "farm brownpaint", new ItemStack(Items.BROWN_DYE)),
                farm("Pink Paint", "farm pinkpaint", new ItemStack(Items.PINK_DYE)),
                farm("Black Paint", "farm blackpaint", new ItemStack(Items.BLACK_DYE))
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        reposition();
        BazzarTheme theme = BazzarTheme.current();
        int panelH = panelHeight();

        float open = BazzarUi.screenAnim(openedAtMs, closingSinceMs);
        graphics.fill(0, 0, width, height, (int) (0xAA * open) << 24);
        float openScale = 0.95f + 0.05f * open;
        graphics.pose().pushMatrix();
        graphics.pose().translate(left + panelW / 2.0f, top + panelH / 2.0f);
        graphics.pose().scale(openScale, openScale);
        graphics.pose().translate(-(left + panelW / 2.0f), -(top + panelH / 2.0f));
        BazzarUi.drawRoundedGlowBorder(graphics, left, top, panelW, panelH, 8);

        Component title = BazzarUi.gradientText("Farm Warps", theme.light() & 0xFFFFFF, theme.rgb, true);
        graphics.drawCenteredString(font, title, left + panelW / 2, top + 10, 0xFFFFFFFF);
        graphics.fill(left + 8, top + TITLE_H - 3, left + panelW - 8, top + TITLE_H - 2, theme.accentDim());

        WorldTab[] worlds = WorldTab.values();
        Component hoveredCropLabel = null;
        FarmEntry hoveredFarm = null;

        if (!favorites.isEmpty()) {
            int fy = favSectionY();
            graphics.fill(left + 8, fy, left + 10, fy + favSectionH() - SECTION_PAD - 2, 0xCC000000 | FAVORITE_GOLD);
            graphics.drawString(font, favoritesLabel, left + 16, fy, 0xFFFFFFFF, false);
            for (int i = 0; i < favorites.size(); i++) {
                FavEntry fav = favorites.get(i);
                int ix = favIconX(i);
                int iy = favIconY(i);
                if (isInside(mouseX, mouseY, ix - 2, iy - 2, 20, 20)) {
                    BazzarUi.fillRoundedRect(graphics, ix - 2, iy - 2, 20, 20, 3, theme.soft(0x40));
                    hoveredCropLabel = BazzarUi.gradientText(fav.farm().name(), fav.world().gradFrom, fav.world().gradTo, false);
                    hoveredFarm = fav.farm();
                }
                graphics.renderItem(fav.farm().icon(), ix, iy);
            }
            graphics.fill(left + 8, fy + favSectionH() - 4, left + panelW - 8, fy + favSectionH() - 3, theme.soft(0x44));
        }

        if (!targets.isEmpty()) {
            int ty = targetSectionY();
            graphics.fill(left + 8, ty, left + 10, ty + targetSectionH() - SECTION_PAD - 2, 0xCC7DD3FC);
            graphics.drawString(font, targetsLabel, left + 16, ty, 0xFFFFFFFF, false);
            int right = left + panelW - 10;
            for (int i = 0; i < targets.size(); i++) {
                TargetEntry target = targets.get(i);
                int ry = targetRowY(i);
                if (isInside(mouseX, mouseY, left + 12, ry, panelW - 20, TARGET_ROW_H)) {
                    BazzarUi.fillRoundedRect(graphics, left + 12, ry, panelW - 20, TARGET_ROW_H, 3, theme.soft(0x40));
                    hoveredCropLabel = BazzarUi.gradientText(target.farm().name() + " farm", target.world().gradFrom, target.world().gradTo, false);
                    hoveredFarm = target.farm();
                }
                graphics.renderItem(target.farm().icon(), left + 16, ry + 1);

                int textY = ry + 5;
                String amount = target.amountText();
                boolean done = target.progress() >= 0d && target.required() > 0d && target.progress() >= target.required();
                int amountX = right - font.width(amount);
                graphics.drawString(font, amount, amountX, textY, done ? DONE_GREEN : 0xFFFFFFFF, false);
                String type = target.type().label;
                int typeX = amountX - 8 - font.width(type);
                graphics.drawString(font, type, typeX, textY, 0xFF000000 | target.type().color, false);
                int sourceX = typeX - 6 - font.width(target.source());
                graphics.drawString(font, target.source(), sourceX, textY, MUTED, false);
                int nameX = left + 38;
                String name = font.plainSubstrByWidth(target.label(), Math.max(0, sourceX - nameX - 6));
                graphics.drawString(font, name, nameX, textY, 0xFFFFFFFF, false);
            }
            int dy = ty + targetSectionH() - 4;
            graphics.fill(left + 8, dy, left + panelW - 8, dy + 1, theme.soft(0x44));
        }

        for (int i = 0; i < worlds.length; i++) {
            WorldTab world = worlds[i];
            int sy = sectionY(i);
            List<FarmEntry> crops = worldCrops.getOrDefault(world, List.of());

            graphics.fill(left + 8, sy, left + 10, sy + SECTION_H - SECTION_PAD - 2, theme.soft(0xCC));

            graphics.drawString(font, worldLabels[i], left + 16, sy, 0xFFFFFFFF, false);
            if (!world.warpCommand.isEmpty() && isInside(mouseX, mouseY, nameBounds(i))) {
                Component hint = BazzarUi.gradientText(WORLD_HINT, world.gradFrom, world.gradTo, false);
                graphics.drawString(font, hint, left + panelW - 10 - font.width(hint), sy, 0xFFFFFFFF, false);
            }

            for (int j = 0; j < crops.size(); j++) {
                int ix = iconX(j);
                int iy = iconY(i);
                if (isInside(mouseX, mouseY, ix - 2, iy - 2, 20, 20)) {
                    BazzarUi.fillRoundedRect(graphics, ix - 2, iy - 2, 20, 20, 3, theme.soft(0x40));
                    hoveredCropLabel = BazzarUi.gradientText(crops.get(j).name(), world.gradFrom, world.gradTo, false);
                    hoveredFarm = crops.get(j);
                }
                graphics.renderItem(crops.get(j).icon(), ix, iy);
                if (favoriteCommands.contains(crops.get(j).command())) {
                    BazzarUi.fillCircle(graphics, ix + 16, iy, 2, 0xFF000000 | FAVORITE_GOLD);
                }
            }

            if (i < worlds.length - 1) {
                graphics.fill(left + 8, sy + SECTION_H - 4, left + panelW - 8, sy + SECTION_H - 3, theme.soft(0x44));
            }
        }

        graphics.drawString(font, FAVORITE_HINT, left + 16, hintY(), 0xFF8E97A6, false);
        ButtonBounds scan = scanBounds();
        String scanLabel = FarmMobScanner.hasData() ? RESCAN_LABEL : SCAN_LABEL;
        int scanColor = isInside(mouseX, mouseY, scan) ? 0xFF000000 | (theme.light() & 0xFFFFFF) : 0xFF8E97A6;
        graphics.drawString(font, scanLabel, scan.x(), hintY(), scanColor, false);

        if (hoveredCropLabel != null) {
            drawMiniTooltip(graphics, hoveredCropLabel, mobLines(hoveredFarm), mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, delta);
        graphics.pose().popMatrix();
    }

    private List<Component> mobLines(FarmEntry farm) {
        FarmMobScanner.MobInfo info = farm == null ? null : FarmMobScanner.get(farm.name());
        if (info == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        Component mob = info.mobText() != null ? info.mobText() : Component.literal(info.mob).withColor(0xFFFFFF);
        lines.add(Component.literal("Mob: ").withColor(0x8E97A6).append(mob));
        if (info.dropText() != null) {
            lines.add(Component.literal("Drop: ").withColor(0x8E97A6).append(info.dropText()));
        } else if (info.drop != null && !info.drop.isBlank()) {
            lines.add(Component.literal("Drop: ").withColor(0x8E97A6).append(Component.literal(info.drop).withColor(0xFFD75A)));
        }
        if (info.bossDropText() != null) {
            lines.add(Component.literal("Boss: ").withColor(0x8E97A6).append(info.bossDropText()));
        } else if (info.bossDrop != null) {
            lines.add(Component.literal("Boss: ").withColor(0x8E97A6).append(Component.literal(info.bossDrop).withColor(0xB28CFF)));
        }
        return lines;
    }

    private void drawMiniTooltip(GuiGraphics graphics, Component text, List<Component> extra, int mouseX, int mouseY) {
        int textW = font.width(text);
        for (Component line : extra) {
            textW = Math.max(textW, font.width(line));
        }
        int textH = 10 + extra.size() * 10;
        int tx = Math.max(4, Math.min(mouseX + 8, width - textW - 8));
        int ty = Math.max(4, Math.min(mouseY - 14, height - textH - 4));
        graphics.fill(tx - 3, ty - 3, tx + textW + 3, ty + textH, 0xE6000000);
        graphics.fill(tx - 3, ty - 3, tx + textW + 3, ty - 2, BazzarTheme.current().accentDim());
        graphics.drawString(font, text, tx, ty, 0xFFFFFFFF, false);
        for (int i = 0; i < extra.size(); i++) {
            graphics.drawString(font, extra.get(i), tx, ty + 10 + i * 10, 0xFFFFFFFF, false);
        }
    }

    @Override
    public void onClose() {
        if (closingSinceMs < 0L) {
            closingSinceMs = System.currentTimeMillis();
        }
    }

    @Override
    public void tick() {
        if (closingSinceMs < 0L && FarmMobScanner.shouldAutoScan()
                && FarmMobScanner.start(Minecraft.getInstance(), true)) {
            return;
        }
        if (closingSinceMs >= 0L && System.currentTimeMillis() - closingSinceMs >= 130L) {
            McCompat.setScreen(Minecraft.getInstance(), null);
            return;
        }
        if (++refreshTicks >= TARGET_REFRESH_TICKS) {
            refreshTicks = 0;
            reloadTargets();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (closingSinceMs >= 0L) {
            return true;
        }
        reposition();
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        boolean rightClick = event.button() == 1;

        if (!rightClick && isInside(mouseX, mouseY, scanBounds())) {
            FarmMobScanner.start(Minecraft.getInstance(), true);
            return true;
        }

        for (int i = 0; i < favorites.size(); i++) {
            if (isInside(mouseX, mouseY, favIconX(i) - 2, favIconY(i) - 2, 20, 20)) {
                if (rightClick) {
                    toggleFavorite(favorites.get(i).farm().command());
                } else {
                    warpAndClose(favorites.get(i).farm().command());
                }
                return true;
            }
        }

        for (int i = 0; i < targets.size(); i++) {
            if (isInside(mouseX, mouseY, left + 12, targetRowY(i), panelW - 20, TARGET_ROW_H)) {
                if (rightClick) {
                    toggleFavorite(targets.get(i).farm().command());
                } else {
                    warpAndClose(targets.get(i).farm().command());
                }
                return true;
            }
        }

        WorldTab[] worlds = WorldTab.values();
        for (int i = 0; i < worlds.length; i++) {
            if (!worlds[i].warpCommand.isEmpty() && isInside(mouseX, mouseY, nameBounds(i))) {
                warpAndClose(worlds[i].warpCommand);
                return true;
            }
            List<FarmEntry> crops = worldCrops.getOrDefault(worlds[i], List.of());
            for (int j = 0; j < crops.size(); j++) {
                if (isInside(mouseX, mouseY, iconX(j) - 2, iconY(i) - 2, 20, 20)) {
                    if (rightClick) {
                        toggleFavorite(crops.get(j).command());
                    } else {
                        warpAndClose(crops.get(j).command());
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, panelHeight() - (height - 8));
        if (maxScroll > 0) {
            scroll = Math.max(0, Math.min((int) (scroll - scrollY * 20.0d), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void warpAndClose(String command) {
        Minecraft client = Minecraft.getInstance();
        CommandSender.send(client, command);
        McCompat.setScreen(client, null);
    }

    private int hintY() {
        return top + TITLE_H + topSectionsH() + WorldTab.values().length * SECTION_H + 2;
    }

    private ButtonBounds scanBounds() {
        int w = font.width(FarmMobScanner.hasData() ? RESCAN_LABEL : SCAN_LABEL);
        return new ButtonBounds(left + panelW - 10 - w, hintY() - 1, w, 11);
    }

    private int sectionY(int index) {
        return top + TITLE_H + topSectionsH() + index * SECTION_H + 4;
    }

    private int iconX(int cropIndex) {
        return left + 16 + cropIndex * ICON_STEP;
    }

    private int iconY(int sectionIndex) {
        return sectionY(sectionIndex) + SECTION_HEADER_H + 2;
    }

    private ButtonBounds nameBounds(int index) {
        return new ButtonBounds(left + 16, sectionY(index) - 1, font.width(worldLabels[index]), 11);
    }

    private boolean isInside(int mouseX, int mouseY, ButtonBounds bounds) {
        return isInside(mouseX, mouseY, bounds.x(), bounds.y(), bounds.w(), bounds.h());
    }

    private boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static FarmEntry farm(String name, String command, ItemStack icon) {
        return new FarmEntry(name, command, icon);
    }

    private static ItemStack itemStack(Item fallback, String... ids) {
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            var ref = BuiltInRegistries.ITEM.get(Identifier.parse(id));
            if (ref.isPresent()) {
                Item item = ref.get().value();
                if (item != Items.AIR) return new ItemStack(item);
            }
        }
        return new ItemStack(fallback);
    }

    private record ButtonBounds(int x, int y, int w, int h) {}
    private record FarmEntry(String name, String command, ItemStack icon) {}
    private record FavEntry(FarmEntry farm, WorldTab world) {}
    private record FarmMatch(FarmEntry farm, WorldTab world, TargetType type, String label, String key) {}

    private record TargetEntry(String label, String source, TargetType type, double progress, double required,
                               FarmEntry farm, WorldTab world) {
        String amountText() {
            if (required <= 0d) {
                return "?";
            }
            if (progress < 0d) {
                return SbText.formatCompact(required);
            }
            return SbText.formatCompact(progress) + " / " + SbText.formatCompact(required);
        }
    }

    private enum TargetType {
        FARMING("Farming", 0x86EFAC),
        MINING("Mining", 0x7DD3FC),
        COMBAT("Combat", 0xFF6B6B);

        final String label;
        final int color;

        TargetType(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private enum WorldTab {

        OVERWORLD("Overworld", "warp overworld", 0x26AC09, 0x99A524),
        WORLD_2("Frostcastle", "warp frostcastle", 0x1EF5DA, 0x2437A5),
        WORLD_3("Swamp", "warp swamp", 0x6A8F3A, 0x2E5E4E),
        WORLD_4("Honey", "warp honey", 0xFFC42E, 0xE3801A),
        WORLD_5("Library", "warps library", 0xE0457B, 0x5B6CFF);

        final String displayName;
        final String warpCommand;
        final int gradFrom;
        final int gradTo;

        WorldTab(String displayName, String warpCommand, int gradFrom, int gradTo) {
            this.displayName = displayName;
            this.warpCommand = warpCommand;
            this.gradFrom = gradFrom;
            this.gradTo = gradTo;
        }
    }
}
