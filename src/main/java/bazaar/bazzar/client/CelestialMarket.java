package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

// Celestial Boost market: sellers list a price, buyers pay first, and the seller's mod boosts once the payment
// arrives. Pool members get free requests, and the server lets only the first accept through.
final class CelestialMarket {
    static final String[] CURRENCIES = {"tokens", "gems", "shards", "cobble"};

    private static final long HEARTBEAT_MS = 30_000L;
    private static final long PAYMENT_MEMORY_MS = 3 * 60_000L;
    private static final long CLAIM_MEMORY_MS = 30_000L;
    private static final long PAYMENT_POLL_GAP_MS = 5_000L;
    private static final long PAY_FAIL_WINDOW_MS = 8_000L;
    // "You received [item/gold_ingot@items]1.00M Tokens from RuinedBySeagulls." once sprites are stripped
    private static final Pattern[] PAYMENT_PATTERNS = {
            Pattern.compile("(?i)^\\s*(\\w{3,16})\\s+(?:has\\s+)?(?:sent|paid|gave)\\s+you\\s+\\S*?([\\d,.]+[a-z]{0,4})\\s+(shards?|gems?|cobble|tokens?)\\b"),
            Pattern.compile("(?i)^\\s*(\\w{3,16})\\s+sent\\s+\\S*?([\\d,.]+[a-z]{0,4})\\s+(shards?|gems?|cobble|tokens?)\\s+to\\s+(\\w{3,16})"),
            Pattern.compile("(?i)you\\s+(?:have\\s+)?(?:received|got)\\s+\\S*?([\\d,.]+[a-z]{0,4})\\s+(shards?|gems?|cobble|tokens?)\\s+from\\s+(\\w{3,16})")
    };
    private static final Pattern PAY_FAILED = Pattern.compile(
            "(?i)not enough|don'?t have enough|do not have enough|insufficient|can'?t afford|cannot afford");
    private static final String GEM_SYMBOLS = "❖✦◆♦";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Bazzar-CelestialMarket");
        thread.setDaemon(true);
        return thread;
    });
    private static final Queue<Runnable> MAIN_THREAD = new ConcurrentLinkedQueue<>();

    // one row per owner: alts paying into the same owner share its prices, stock is how many can boost now
    record Listing(String owner, Map<String, Double> prices, int stock, String backend, long readyAtLocal, boolean online) {
        boolean ready() {
            return stock > 0;
        }

        Double price(String currency) {
            return prices.get(currency);
        }
    }

    record Sale(String buyer, String seller, double price, String currency, long atLocal) {
    }

    private record Order(int id, String buyer, double price, String currency) {
    }

    record Payment(String sender, String currency, double amount, long atMs) {
    }

    private record PendingBuy(int orderId, String seller, long paidAtMs) {
    }

    private static final Map<Integer, Order> openOrders = new LinkedHashMap<>();
    private static final Map<Integer, Double> paidSoFar = new LinkedHashMap<>();
    private static final ArrayDeque<Payment> recentPayments = new ArrayDeque<>();
    // payments that went to a Celestial Boost order, so auto pay knows not to pay for them
    private static final ArrayDeque<Payment> claimedPayments = new ArrayDeque<>();
    private static final Set<Integer> promptedRequests = new HashSet<>();
    private static List<Listing> listings = List.of();
    private static List<Sale> sales = List.of();
    private static int poolReady = 0;
    private static long listingsFetchedMs = 0L;
    private static String lastMessage = "";
    private static PendingBuy pendingBuy = null;
    private static final long BUY_COOLDOWN_MS = 5_000L;
    private static long lastBuyMs = 0L;
    private static long nextHeartbeatMs = 0L;
    private static long lastPaymentPollMs = 0L;
    private static int lastPromptedRequest = -1;
    private static CelestialPanel.View openTabNextTick = null;
    private static boolean tutorialNextTick = false;

    private CelestialMarket() {
    }

    static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("cbshop")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(ctx -> {
                        openScreen(CelestialPanel.View.MARKET);
                        return 1;
                    }));
            dispatcher.register(literal("cbsell")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(ctx -> {
                        openScreen(CelestialPanel.View.MY_LISTING);
                        return 1;
                    })
                    .then(literal("off").executes(ctx -> {
                        deleteListing(ctx.getSource().getClient());
                        return 1;
                    }))
                    .then(argument("price", StringArgumentType.word())
                            .then(argument("currency", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        for (String currency : CURRENCIES) {
                                            builder.suggest(currency);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(CelestialMarket::runSetPrice))));
            dispatcher.register(literal("cbtutorial")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .executes(ctx -> {
                        tutorialNextTick = true;
                        return 1;
                    }));
            dispatcher.register(literal("cbpool")
                    .requires(source -> !BazzarGuard.isBlocked())
                    .then(literal("on").executes(ctx -> {
                        setPool(ctx.getSource().getClient(), true);
                        return 1;
                    }))
                    .then(literal("off").executes(ctx -> {
                        setPool(ctx.getSource().getClient(), false);
                        return 1;
                    })));
        });
    }

    // commands run while chat is still open, so the screen opens on the next tick
    static void openScreen(CelestialPanel.View tab) {
        openTabNextTick = tab;
    }

    // ---- what the market screen reads ----

    static List<Listing> listings() {
        return listings;
    }

    static List<Sale> sales() {
        return sales;
    }

    static int poolReady() {
        return poolReady;
    }

    static boolean loaded() {
        return listingsFetchedMs > 0L;
    }

    static String lastMessage() {
        return lastMessage;
    }

    static boolean waitingForPayment() {
        return pendingBuy != null;
    }

    static boolean coolingDown() {
        return BazzarConfig.load().cbBuyCooldown && System.currentTimeMillis() - lastBuyMs < BUY_COOLDOWN_MS;
    }

    static long cooldownLeftMs() {
        return coolingDown() ? BUY_COOLDOWN_MS - (System.currentTimeMillis() - lastBuyMs) : 0L;
    }

    static long cooldownMs() {
        return BUY_COOLDOWN_MS;
    }

    static void refresh(Minecraft client) {
        get("/cb/sellers", (code, json) -> {
            if (code != 200 || json == null) {
                lastMessage = "Couldn't reach the Bazzar server.";
                return;
            }
            long offset = json.has("now") ? System.currentTimeMillis() - json.get("now").getAsLong() : 0L;
            List<Listing> fresh = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("sellers")) {
                JsonObject seller = element.getAsJsonObject();
                Map<String, Double> prices = new LinkedHashMap<>();
                JsonObject rawPrices = seller.getAsJsonObject("prices");
                for (String currency : CURRENCIES) {
                    if (rawPrices.has(currency)) {
                        prices.put(currency, rawPrices.get(currency).getAsDouble());
                    }
                }
                long readyAt = seller.get("readyAt").getAsLong();
                fresh.add(new Listing(seller.get("owner").getAsString(), prices, seller.get("stock").getAsInt(),
                        seller.get("backend").getAsString(), readyAt <= 0L ? 0L : readyAt + offset,
                        seller.get("online").getAsBoolean()));
            }
            listings = sorted(fresh);
            poolReady = json.has("pool") ? json.get("pool").getAsInt() : 0;
            listingsFetchedMs = System.currentTimeMillis();
        });
        String name = selfName(client);
        if (name != null && BazzarConfig.load().cbSellEnabled) {
            post("/cb/sales", baseBody(name), (code, json) -> {
                if (code != 200 || json == null || !json.has("sales")) {
                    return;
                }
                List<Sale> fresh = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray("sales")) {
                    JsonObject sale = element.getAsJsonObject();
                    fresh.add(new Sale(sale.get("buyer").getAsString(), sale.get("seller").getAsString(),
                            sale.get("price").getAsDouble(), sale.get("currency").getAsString(), sale.get("at").getAsLong()));
                }
                sales = fresh;
            });
        }
    }

    // reads your money from the sidebar: purse is tokens, the gem symbol next to it is gems, plus the cobble and shard lines
    static double balance(Minecraft client, String currency) {
        for (ScoreboardReader.Line line : ScoreboardReader.readSidebar(client)) {
            String text = line.normalizedText();
            // the sidebar often shows the currency's icon instead of writing its name out
            if (currency.equals(CurrencyIcons.currencyForSprite(line.rawText()))) {
                double amount = SbText.firstAmount(text);
                if (amount >= 0d) {
                    return amount;
                }
            }
            switch (currency) {
                case "tokens" -> {
                    if (text.contains("purse")) {
                        return SbText.amountAfterLabel(text, "purse");
                    }
                }
                case "gems" -> {
                    for (int i = 0; i < GEM_SYMBOLS.length(); i++) {
                        int idx = text.indexOf(GEM_SYMBOLS.charAt(i));
                        if (idx >= 0) {
                            return SbText.firstAmount(text.substring(idx + 1));
                        }
                    }
                    if (text.contains("gem")) {
                        return SbText.amountAfterLabel(text, "gem");
                    }
                }
                case "cobble" -> {
                    if (text.contains("cobble")) {
                        return SbText.amountAfterLabel(text, "cobble");
                    }
                }
                default -> {
                    if (text.contains("shard")) {
                        return SbText.amountAfterLabel(text, "shard");
                    }
                }
            }
        }
        return -1d;
    }

    static boolean canAfford(Minecraft client, String currency, double price) {
        double have = balance(client, currency);
        return have < 0d || have >= price;
    }

    // listings with stock first, then whoever comes off cooldown soonest
    static List<Listing> sorted(List<Listing> fresh) {
        List<Listing> ordered = new ArrayList<>(fresh);
        ordered.sort((a, b) -> {
            if (a.ready() != b.ready()) {
                return a.ready() ? -1 : 1;
            }
            return Long.compare(a.readyAtLocal(), b.readyAtLocal());
        });
        return ordered;
    }

    static boolean isOwnListing(Minecraft client, Listing listing) {
        return ownedBy(groupName(client), listing);
    }

    static boolean ownedBy(String group, Listing listing) {
        return group != null && group.equalsIgnoreCase(listing.owner());
    }

    // the cheapest listing with stock in the quick-buy currency, for the HUD and the buy hotkey
    static Listing cheapest(Minecraft client, String currency) {
        return cheapest(listings, currency, groupName(client));
    }

    static Listing cheapest(List<Listing> from, String currency, String group) {
        Listing best = null;
        for (Listing listing : from) {
            Double price = listing.price(currency);
            if (price == null || !listing.ready() || ownedBy(group, listing)) {
                continue;
            }
            if (best == null || price < best.price(currency)) {
                best = listing;
            }
        }
        return best;
    }

    // displayed amounts are rounded (1.00M), so a payment a hair under the exact price still counts
    static boolean covers(double paid, double price) {
        return paid >= price * 0.995d;
    }

    // the server's wording when a pay command bounces for want of money
    static boolean payFailed(String text) {
        return text != null && PAY_FAILED.matcher(text).find();
    }

    // "RuinedBySeagulls sent you 1.00M Tokens" and the other wordings a transfer shows up as
    static Payment parsePayment(String text, String self, long atMs) {
        if (text == null || self == null) {
            return null;
        }
        for (int i = 0; i < PAYMENT_PATTERNS.length; i++) {
            Matcher matcher = PAYMENT_PATTERNS[i].matcher(text);
            if (!matcher.find()) {
                continue;
            }
            String sender = i == 2 ? matcher.group(3) : matcher.group(1);
            String amountRaw = i == 2 ? matcher.group(1) : matcher.group(2);
            String currency = currencyOf(i == 2 ? matcher.group(2) : matcher.group(3));
            // the second wording is a public "X sent N to Y" line, which only counts when Y is us
            if (i == 1 && !matcher.group(4).equalsIgnoreCase(self)) {
                return null;
            }
            Double amount = ShardPayoutController.parseCompactNumber(amountRaw);
            if (amount == null || currency == null || sender.equalsIgnoreCase(self)) {
                return null;
            }
            return new Payment(sender, currency, amount, atMs);
        }
        return null;
    }

    static void buyCheapest(Minecraft client) {
        String currency = BazzarConfig.load().cbQuickCurrency;
        Listing best = cheapest(client, currency);
        if (best == null) {
            lastMessage = "No Celestial Boost for sale in " + currency + " right now.";
            status(client, lastMessage);
            return;
        }
        buy(client, best, currency);
    }

    static void buy(Minecraft client, Listing listing, String currency) {
        String name = selfName(client);
        Double price = listing.price(currency);
        if (name == null || price == null || pendingBuy != null) {
            return;
        }
        // a short gap between purchases so a double click or held key can't buy twice
        long sinceLast = System.currentTimeMillis() - lastBuyMs;
        if (BazzarConfig.load().cbBuyCooldown && sinceLast < BUY_COOLDOWN_MS) {
            lastMessage = "Wait " + ((BUY_COOLDOWN_MS - sinceLast + 999L) / 1000L) + "s before buying again.";
            status(client, lastMessage);
            return;
        }
        double have = balance(client, currency);
        if (have >= 0d && have < price) {
            lastMessage = "You need " + SbText.formatCompact(price - have) + " more " + currency + ".";
            status(client, lastMessage);
            return;
        }
        JsonObject body = baseBody(name);
        body.addProperty("group", listing.owner());
        body.addProperty("price", price);
        body.addProperty("currency", currency);
        lastMessage = "Placing your order...";
        lastBuyMs = System.currentTimeMillis();
        post("/cb/order", body, (code, json) -> {
            if (code == 200 && json != null && json.has("ok")) {
                String payTo = json.get("seller").getAsString();
                CommandSender.send(client, payCommand(currency) + " " + payAmount(currency, price) + " " + payTo);
                pendingBuy = new PendingBuy(json.get("id").getAsInt(), payTo, System.currentTimeMillis());
                lastMessage = "Paid " + payTo + " " + SbText.formatCompact(price) + " " + currency
                        + ", their boost fires as soon as their mod sees it.";
                status(client, lastMessage);
                return;
            }
            String error = json != null && json.has("error") ? json.get("error").getAsString() : "";
            lastMessage = switch (error) {
                case "price changed" -> listing.owner() + " changed their price, check it again. Nothing was paid.";
                case "seller busy" -> listing.owner() + " is filling other orders, try again soon. Nothing was paid.";
                case "not selling" -> listing.owner() + " has no boost ready right now. Nothing was paid.";
                default -> "Couldn't place the order. Nothing was paid.";
            };
            status(client, lastMessage);
            refresh(client);
        });
    }

    static void setPrices(Minecraft client, Map<String, Double> prices) {
        BazzarConfig.State state = BazzarConfig.load();
        state.cbPrices = new java.util.HashMap<>(prices);
        state.cbSellEnabled = true;
        BazzarConfig.save(state);
        lastMessage = prices.isEmpty()
                ? "Listed, using your main's prices."
                : "Listed your Celestial Boost for " + priceText(prices) + ".";
        heartbeatNow(client);
    }

    static String priceText(Map<String, Double> prices) {
        StringBuilder text = new StringBuilder();
        for (String currency : CURRENCIES) {
            Double price = prices.get(currency);
            if (price != null && price > 0d) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(SbText.formatCompact(price)).append(' ').append(currency);
            }
        }
        return text.toString();
    }

    // alts that pay into a shard pay owner list under that owner, everyone else lists under their own name
    static String groupName(Minecraft client) {
        String self = selfName(client);
        String owner = ShardPayController.getOwner();
        if (owner != null && owner.trim().matches("[A-Za-z0-9_]{3,16}")) {
            return owner.trim();
        }
        return self;
    }

    static void deleteListing(Minecraft client) {
        BazzarConfig.State state = BazzarConfig.load();
        state.cbSellEnabled = false;
        BazzarConfig.save(state);
        lastMessage = "Your listing is removed.";
        status(client, lastMessage);
        heartbeatNow(client);
    }

    static void setPool(Minecraft client, boolean enable) {
        BazzarConfig.State state = BazzarConfig.load();
        state.cbPoolEnabled = enable;
        BazzarConfig.save(state);
        lastMessage = enable
                ? "You're in the free pool, requests show an [Accept] button."
                : "You left the free pool.";
        status(client, lastMessage);
        heartbeatNow(client);
    }

    // ---- free pool ----

    static void request(Minecraft client) {
        String name = selfName(client);
        if (name == null) {
            return;
        }
        post("/cb/request", baseBody(name), (code, json) -> {
            if (code != 200 || json == null) {
                lastMessage = "Couldn't reach the Bazzar server, try again in a bit.";
            } else if (json.has("already")) {
                lastMessage = "Your request is still open, waiting for a Celestial to accept.";
            } else {
                int pool = json.has("pool") ? json.get("pool").getAsInt() : 0;
                lastMessage = pool == 0
                        ? "Request sent, but nobody in the free pool is ready right now."
                        : "Request sent to " + pool + " Celestial" + (pool == 1 ? "" : "s") + " in the free pool.";
            }
            status(client, lastMessage);
        });
    }

    static boolean accept(Minecraft client, int requestId) {
        String name = selfName(client);
        if (name == null) {
            return false;
        }
        if (requestId < 0) {
            CommandSender.send(client, "celestialbooster");
            return true;
        }
        JsonObject body = baseBody(name);
        body.addProperty("request", requestId);
        post("/cb/claim", body, (code, json) -> {
            if (code == 200 && json != null && json.has("ok") && json.get("ok").getAsBoolean()) {
                CommandSender.send(client, "celestialbooster");
            } else {
                status(client, "Someone else already accepted that request.");
            }
        });
        return true;
    }

    static int lastPromptedRequest() {
        return lastPromptedRequest;
    }

    // ---- ticking, heartbeat and payments ----

    static void tick(Minecraft client) {
        Runnable task;
        while ((task = MAIN_THREAD.poll()) != null) {
            task.run();
        }
        if (openTabNextTick != null && McCompat.screen(client) == null) {
            CelestialPanel.View tab = openTabNextTick;
            openTabNextTick = null;
            McCompat.setScreen(client, CelestialTutorial.seen()
                    ? BazaarTrackerScreen.openCelestial(tab)
                    : new CelestialTutorialScreen(tab));
        }
        if (tutorialNextTick) {
            tutorialNextTick = false;
            if (McCompat.screen(client) == null) {
                McCompat.setScreen(client, new CelestialTutorialScreen(null));
            }
        }
        long now = System.currentTimeMillis();
        recentPayments.removeIf(payment -> now - payment.atMs() > PAYMENT_MEMORY_MS);
        claimedPayments.removeIf(payment -> now - payment.atMs() > CLAIM_MEMORY_MS);
        if (pendingBuy != null && now - pendingBuy.paidAtMs() > PAY_FAIL_WINDOW_MS) {
            pendingBuy = null;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (!state.cbSellEnabled && !state.cbPoolEnabled) {
            openOrders.clear();
            return;
        }
        if (client.player == null || client.getCurrentServer() == null || now < nextHeartbeatMs) {
            return;
        }
        heartbeatNow(client);
    }

    private static void heartbeatNow(Minecraft client) {
        nextHeartbeatMs = System.currentTimeMillis() + HEARTBEAT_MS;
        String name = selfName(client);
        if (name == null) {
            return;
        }
        BazzarConfig.State state = BazzarConfig.load();
        JsonObject body = baseBody(name);
        body.addProperty("readyAt", CelestialTracker.readyAt(name));
        body.addProperty("selling", state.cbSellEnabled);
        body.addProperty("group", groupName(client));
        JsonObject prices = new JsonObject();
        if (state.cbPrices != null) {
            state.cbPrices.forEach((currency, price) -> {
                if (price != null && price > 0d) {
                    prices.addProperty(currency, price);
                }
            });
        }
        body.add("prices", prices);
        body.addProperty("pool", state.cbPoolEnabled);
        post("/cb/status", body, (code, json) -> {
            if (code == 409) {
                lastMessage = "Another Bazzar install is using your name on the market, it frees up in 15 minutes.";
                return;
            }
            if (code != 200 || json == null) {
                return;
            }
            onOrders(client, json.getAsJsonArray("orders"));
            onRequests(client, json.getAsJsonArray("requests"));
            if (McCompat.screen(client) instanceof BazaarTrackerScreen screen && screen.showingCelestial()) {
                refresh(client);
            }
        });
    }

    private static void onOrders(Minecraft client, JsonArray orders) {
        Set<Integer> live = new HashSet<>();
        if (orders != null) {
            for (JsonElement element : orders) {
                JsonObject object = element.getAsJsonObject();
                Order order = new Order(object.get("id").getAsInt(), object.get("requester").getAsString(),
                        object.get("price").getAsDouble(), object.get("currency").getAsString());
                live.add(order.id());
                if (openOrders.putIfAbsent(order.id(), order) == null) {
                    status(client, order.buyer() + " ordered your Celestial Boost for " + SbText.formatCompact(order.price())
                            + " " + order.currency() + ". It fires as soon as they pay.");
                }
            }
        }
        openOrders.keySet().removeIf(id -> !live.contains(id));
        paidSoFar.keySet().removeIf(id -> !live.contains(id));
        // a payment can land before the next heartbeat brings the order in
        for (Payment payment : new ArrayDeque<>(recentPayments)) {
            applyPayment(client, payment);
        }
    }

    private static void onRequests(Minecraft client, JsonArray requests) {
        if (requests == null || client.player == null) {
            return;
        }
        String name = selfName(client);
        if (name == null || CelestialTracker.readyAt(name) > System.currentTimeMillis()) {
            return;
        }
        for (JsonElement element : requests) {
            JsonObject object = element.getAsJsonObject();
            int id = object.get("id").getAsInt();
            if (!promptedRequests.add(id)) {
                continue;
            }
            lastPromptedRequest = id;
            String requester = object.get("requester").getAsString();
            MutableComponent accept = Component.literal("[Accept]").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withBold(true)
                    .withClickEvent(new ClickEvent.RunCommand("/acceptcelestial " + id))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Runs /celestialbooster if nobody beat you to it"))));
            McCompat.chat(client.player, BazaarChat.prefix()
                    .append(Component.literal(requester + " is asking the pool for a Celestial Boost. ").withStyle(ChatFormatting.AQUA))
                    .append(accept)
                    .append(Component.literal(" or type \"accept celestial\"").withStyle(ChatFormatting.GRAY)));
            client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.4f);
        }
    }

    static void onChatLine(Minecraft client, String line) {
        if (line == null || client.player == null) {
            return;
        }
        CurrencyIcons.learn(line);
        String text = SbText.stripFormattingCodes(SbText.stripSprites(line)).trim();
        PendingBuy buying = pendingBuy;
        if (buying != null && !text.contains("»") && payFailed(text)) {
            // the pay command bounced, so free the seller and tell the buyer
            pendingBuy = null;
            String name = selfName(client);
            if (name != null) {
                JsonObject body = baseBody(name);
                body.addProperty("request", buying.orderId());
                post("/cb/cancel", body, (code, json) -> {
                });
            }
            lastMessage = "The payment to " + buying.seller() + " didn't go through, so the order was cancelled.";
            status(client, lastMessage);
            return;
        }
        if (!BazzarConfig.load().cbSellEnabled) {
            return;
        }
        Payment payment = parsePayment(text, client.player.getName().getString(), System.currentTimeMillis());
        if (payment == null) {
            return;
        }
        if (!applyPayment(client, payment)) {
            recentPayments.addLast(payment);
            pollForOrder(payment);
        }
    }

    // a payment that could be someone buying a Celestial Boost, so auto pay holds it instead of paying straight away
    static boolean mayPayForBoost(String sender, String currency, double amount) {
        if (sender == null || currency == null) {
            return false;
        }
        BazzarConfig.State state = BazzarConfig.load();
        if (state == null || !state.cbSellEnabled) {
            return false;
        }
        for (Order order : openOrders.values()) {
            if (order.buyer().equalsIgnoreCase(sender) && order.currency().equals(currency)) {
                return true;
            }
        }
        // the order often lands a moment after the payment, so anything covering the listed price counts too
        Double price = state.cbPrices == null ? null : state.cbPrices.get(currency);
        return price != null && price > 0d && covers(amount, price);
    }

    // the payment was taken by a Celestial Boost order, so auto pay should leave it alone
    static boolean claimedForBoost(String sender, String currency, double amount) {
        Iterator<Payment> it = claimedPayments.iterator();
        while (it.hasNext()) {
            Payment payment = it.next();
            if (payment.sender().equalsIgnoreCase(sender) && payment.currency().equals(currency)
                    && Math.abs(payment.amount() - amount) < 0.001d) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // a payment with no order behind it yet: ask the server now instead of waiting out the heartbeat
    private static void pollForOrder(Payment payment) {
        if (payment.atMs() - lastPaymentPollMs < PAYMENT_POLL_GAP_MS
                || !mayPayForBoost(payment.sender(), payment.currency(), payment.amount())) {
            return;
        }
        lastPaymentPollMs = payment.atMs();
        nextHeartbeatMs = 0L;
    }

    private static boolean applyPayment(Minecraft client, Payment payment) {
        Iterator<Order> it = openOrders.values().iterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (!order.buyer().equalsIgnoreCase(payment.sender()) || !order.currency().equals(payment.currency())) {
                continue;
            }
            double paid = paidSoFar.merge(order.id(), payment.amount(), Double::sum);
            recentPayments.remove(payment);
            claimedPayments.addLast(payment);
            if (!covers(paid, order.price())) {
                status(client, order.buyer() + " paid " + SbText.formatCompact(paid) + " of "
                        + SbText.formatCompact(order.price()) + " " + order.currency() + ".");
                return true;
            }
            it.remove();
            paidSoFar.remove(order.id());
            CommandSender.send(client, "celestialbooster");
            status(client, "Sold your Celestial Boost to " + order.buyer() + " for "
                    + SbText.formatCompact(order.price()) + " " + order.currency() + ".");
            String name = selfName(client);
            if (name != null) {
                JsonObject body = baseBody(name);
                body.addProperty("request", order.id());
                post("/cb/done", body, (code, json) -> refresh(client));
            }
            return true;
        }
        return false;
    }

    // ---- chat command for setting a price ----

    private static int runSetPrice(CommandContext<FabricClientCommandSource> context) {
        Double price = ShardPayoutController.parseCompactNumber(StringArgumentType.getString(context, "price"));
        String currency = currencyOf(StringArgumentType.getString(context, "currency"));
        if (price == null || price <= 0d || currency == null) {
            status(context.getSource().getClient(), "Use /cbsell <price> <tokens|gems|shards|cobble>, e.g. /cbsell 1m tokens");
            return 0;
        }
        Map<String, Double> prices = new LinkedHashMap<>(BazzarConfig.load().cbPrices);
        prices.put(currency, price);
        setPrices(context.getSource().getClient(), prices);
        status(context.getSource().getClient(), lastMessage);
        return 1;
    }

    static String payCommand(String currency) {
        return switch (currency) {
            case "gems" -> "gempay";
            case "cobble" -> "cobblepay";
            case "tokens" -> "paytokens";
            default -> "shardpay";
        };
    }

    // same amount formats the shard pay and payout features already send
    static String payAmount(String currency, double amount) {
        return switch (currency) {
            case "gems", "cobble" -> ShardPayController.formatWhole(amount);
            case "tokens" -> ShardPayoutController.formatPayAmount(amount);
            default -> ShardPayController.formatAmount(amount);
        };
    }

    static String currencyOf(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("shard")) {
            return "shards";
        }
        if (lower.startsWith("gem")) {
            return "gems";
        }
        if (lower.startsWith("cobble")) {
            return "cobble";
        }
        if (lower.startsWith("token")) {
            return "tokens";
        }
        return null;
    }

    static String selfName(Minecraft client) {
        return client.player == null ? null : client.player.getName().getString();
    }

    static JsonObject baseBody(String name) {
        JsonObject body = new JsonObject();
        try {
            body.addProperty("id", UsageStats.installId());
        } catch (Exception ignored) {
        }
        body.addProperty("name", name);
        return body;
    }

    private static void status(Minecraft client, String text) {
        if (client.player != null && !(McCompat.screen(client) instanceof BazaarTrackerScreen screen && screen.showingCelestial())) {
            McCompat.chat(client.player, BazaarChat.prefix().append(Component.literal(text).withStyle(ChatFormatting.AQUA)));
        }
    }

    interface Reply {
        void accept(int code, JsonObject json);
    }

    static void post(String path, JsonObject body, Reply reply) {
        send(HttpRequest.newBuilder(URI.create(UsageStats.ENDPOINT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), reply);
    }

    static void get(String path, Reply reply) {
        send(HttpRequest.newBuilder(URI.create(UsageStats.ENDPOINT + path)).GET(), reply);
    }

    // network work stays off the game thread, the reply runs back on it during the next tick
    private static void send(HttpRequest.Builder builder, Reply reply) {
        HttpRequest request = builder.timeout(Duration.ofSeconds(15)).header("User-Agent", "Bazzar-Market").build();
        EXECUTOR.execute(() -> {
            int code = 0;
            JsonObject json = null;
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                code = response.statusCode();
                JsonElement parsed = JsonParser.parseString(response.body());
                json = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (Exception ignored) {
            }
            int finalCode = code;
            JsonObject finalJson = json;
            MAIN_THREAD.add(() -> reply.accept(finalCode, finalJson));
        });
    }
}
