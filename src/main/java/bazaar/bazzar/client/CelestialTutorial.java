package bazaar.bazzar.client;

import java.util.List;

// what the Celestial Market tutorial says, kept apart from the screen that draws it so the wording
// and the stepping can be read and tested on their own
final class CelestialTutorial {
    record Step(String title, List<String> lines, String command) {
    }

    private static final List<Step> STEPS = List.of(
            new Step("What this is", List.of(
                    "A Celestial Boost is worth more to someone else",
                    "than it is sitting on your cooldown.",
                    "",
                    "The market lets players sell theirs to each other.",
                    "Everything here runs through the Bazzar server,",
                    "so both sides need the mod installed."
            ), null),
            new Step("Buying one", List.of(
                    "Open the market and click a seller's price.",
                    "You pay them first, then their mod sees the",
                    "payment land and boosts you automatically.",
                    "",
                    "Prices show in tokens, gems, shards or cobble.",
                    "Pick whichever you have; each seller sets their own."
            ), "/cbshop"),
            new Step("If a payment goes wrong", List.of(
                    "If your pay command bounces, the order cancels",
                    "itself and nothing is taken.",
                    "",
                    "There is a five second wait between buys so a",
                    "double click can't pay twice. You can turn that",
                    "off in the market's settings once you trust it."
            ), null),
            new Step("Selling yours", List.of(
                    "List your boost with a price and a currency.",
                    "When someone pays, your mod runs",
                    "/celestialbooster for you, wherever you are.",
                    "",
                    "/cbsell off takes your listing back down."
            ), "/cbsell 1m tokens"),
            new Step("The free pool", List.of(
                    "The pool is for giving boosts away.",
                    "",
                    "/cbpool on puts you in it: when somebody asks,",
                    "you get an [Accept] button in chat and the first",
                    "to press it boosts them.",
                    "",
                    "/requestcelestial asks the pool for a free one."
            ), "/cbpool on"),
            new Step("Buying without the menu", List.of(
                    "The market HUD sits on your screen and shows",
                    "the cheapest boost going.",
                    "",
                    "Toggle it with the Celestial Market HUD key,",
                    "then the buy key takes the cheapest one without",
                    "opening anything. Both are in Controls.",
                    "",
                    "Run /cbtutorial any time to read this again."
            ), null)
    );

    private CelestialTutorial() {
    }

    static List<Step> steps() {
        return STEPS;
    }

    static int count() {
        return STEPS.size();
    }

    static Step step(int index) {
        return STEPS.get(clamp(index));
    }

    static int clamp(int index) {
        return Math.max(0, Math.min(STEPS.size() - 1, index));
    }

    static boolean isLast(int index) {
        return clamp(index) == STEPS.size() - 1;
    }

    static boolean seen() {
        return BazzarConfig.load().cbTutorialSeen;
    }

    static void markSeen() {
        BazzarConfig.State state = BazzarConfig.load();
        if (state.cbTutorialSeen) {
            return;
        }
        state.cbTutorialSeen = true;
        BazzarConfig.save(state);
    }
}
