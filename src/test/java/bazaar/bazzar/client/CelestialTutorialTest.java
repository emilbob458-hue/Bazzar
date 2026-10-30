package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// the Celestial Market walkthrough: its wording has to match what the mod actually does, and
// stepping through it must not run off either end
class CelestialTutorialTest {
    @Test
    void everyCardHasSomethingToSay() {
        assertTrue(CelestialTutorial.count() >= 3);
        for (CelestialTutorial.Step step : CelestialTutorial.steps()) {
            assertFalse(step.title().isBlank(), "a card with no title");
            assertFalse(step.lines().isEmpty(), step.title());
            // a card of nothing but blank lines would render as an empty box
            assertTrue(step.lines().stream().anyMatch(line -> !line.isBlank()), step.title());
        }
    }

    @Test
    void theCardsFitTheBoxTheyAreDrawnIn() {
        for (CelestialTutorial.Step step : CelestialTutorial.steps()) {
            assertTrue(step.lines().size() <= 10, step.title() + " has " + step.lines().size() + " lines");
            for (String line : step.lines()) {
                // the panel is 300 wide, so about 60 characters before it runs over the edge
                assertTrue(line.length() <= 60, step.title() + ": " + line);
            }
        }
    }

    @Test
    void theCommandsItShowsAreRealOnes() {
        List<String> shown = CelestialTutorial.steps().stream()
                .map(CelestialTutorial.Step::command)
                .filter(command -> command != null)
                .toList();
        assertFalse(shown.isEmpty());
        for (String command : shown) {
            assertTrue(command.startsWith("/"), command);
            String name = command.substring(1).split(" ")[0];
            assertTrue(List.of("cbshop", "cbsell", "cbpool", "cbtutorial").contains(name), command);
        }
    }

    @Test
    void theSellExampleIsAPriceTheMarketWouldTake() {
        // "/cbsell 1m tokens" is the shape runSetPrice parses, so the example has to survive it
        String example = CelestialTutorial.steps().stream()
                .map(CelestialTutorial.Step::command)
                .filter(command -> command != null && command.startsWith("/cbsell "))
                .findFirst()
                .orElseThrow();
        String[] parts = example.substring("/cbsell ".length()).split(" ");
        assertEquals(2, parts.length, example);
        assertNotNull(ShardPayoutController.parseCompactNumber(parts[0]), example);
        assertNotNull(CelestialMarket.currencyOf(parts[1]), example);
    }

    @Test
    void steppingStopsAtBothEnds() {
        assertEquals(0, CelestialTutorial.clamp(-3));
        assertEquals(CelestialTutorial.count() - 1, CelestialTutorial.clamp(CelestialTutorial.count() + 5));
        assertTrue(CelestialTutorial.isLast(CelestialTutorial.count() - 1));
        assertFalse(CelestialTutorial.isLast(0));
    }

    @Test
    void everyCardCanBeReachedByStepping() {
        int index = 0;
        for (int i = 1; i < CelestialTutorial.count(); i++) {
            index = CelestialTutorial.clamp(index + 1);
            assertEquals(i, index);
            assertNotNull(CelestialTutorial.step(index));
        }
        assertTrue(CelestialTutorial.isLast(index));
    }
}
