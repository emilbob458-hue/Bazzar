package bazaar.bazzar.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

final class ScoreboardReader {
    record Line(String normalizedText, int score) {
    }

    private ScoreboardReader() {
    }

    static List<Line> readSidebar(Minecraft client) {
        List<Line> lines = new ArrayList<>();
        if (client == null || client.level == null || client.player == null) {
            return lines;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = null;
        PlayerTeam playerTeam = scoreboard.getPlayersTeam(client.player.getScoreboardName());
        if (playerTeam != null) {
            DisplaySlot slot = McCompat.teamSlot(playerTeam);
            if (slot != null) {
                objective = scoreboard.getDisplayObjective(slot);
            }
        }
        if (objective == null) {
            objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        }
        if (objective == null) {
            return lines;
        }
        NumberFormat numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            if (entry.isHidden()) {
                continue;
            }
            PlayerTeam ownerTeam = scoreboard.getPlayersTeam(entry.owner());
            String nameText = PlayerTeam.formatNameForTeam(ownerTeam, entry.ownerName()).getString();
            String valueText = entry.formatValue(numberFormat).getString();
            lines.add(new Line(SbText.normalizeText(SbText.stripSprites(nameText + " " + valueText)), entry.value()));
        }
        return lines;
    }
}
