package bazaar.bazzar.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

final class ConfigPresets {
    private static final Path DIR = Path.of(System.getProperty("user.home"), ".bazzar", "presets");
    private static final Path OLD_DIR = FabricLoader.getInstance().getConfigDir().resolve("bazzar").resolve("presets");
    private static boolean migrated;

    record Result(boolean ok, String message) {
    }

    private ConfigPresets() {
    }

    static void registerCommands() {
        SuggestionProvider<FabricClientCommandSource> presetNames = (ctx, builder) -> {
            names().forEach(builder::suggest);
            return builder.buildFuture();
        };
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("config")
                        .requires(source -> !BazzarGuard.isBlocked())
                        .executes(ConfigPresets::runList)
                        .then(literal("list").executes(ConfigPresets::runList))
                        .then(literal("save").then(argument("name", StringArgumentType.word())
                                .executes(ctx -> reply(ctx, save(StringArgumentType.getString(ctx, "name"))))))
                        .then(literal("set").then(argument("name", StringArgumentType.word())
                                .suggests(presetNames)
                                .executes(ctx -> reply(ctx, load(StringArgumentType.getString(ctx, "name"))))))
                        .then(literal("delete").then(argument("name", StringArgumentType.word())
                                .suggests(presetNames)
                                .executes(ConfigPresets::runDelete)))
                        .then(PublicPresets.command())));
    }

    static Result save(String rawName) {
        migrate();
        String name = clean(rawName);
        if (name.isEmpty()) {
            return new Result(false, "Use only letters, numbers and _ in the name.");
        }
        BazzarConfig.save(BazzarConfig.load());
        try {
            Files.createDirectories(DIR);
            Files.copy(BazzarConfig.path(), fileFor(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return new Result(false, "Could not save preset " + name + ".");
        }
        return new Result(true, "Saved preset " + name + ".");
    }

    static Result load(String rawName) {
        migrate();
        String name = clean(rawName);
        Path source = fileFor(name);
        if (name.isEmpty() || !Files.exists(source)) {
            return new Result(false, "No preset called " + rawName + ".");
        }
        try {
            Files.copy(source, BazzarConfig.path(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return new Result(false, "Could not load preset " + name + ".");
        }
        BazzarConfig.reload();
        BazzarClient.applyConfig(BazzarConfig.load());
        return new Result(true, "Loaded preset " + name + ".");
    }

    static Result delete(String rawName) {
        migrate();
        String name = clean(rawName);
        try {
            if (name.isEmpty() || !Files.deleteIfExists(fileFor(name))) {
                return new Result(false, "No preset called " + rawName + ".");
            }
        } catch (IOException e) {
            return new Result(false, "Could not delete preset " + name + ".");
        }
        return new Result(true, "Deleted preset " + name + ".");
    }

    // The saved preset file, or null when there is none.
    static Path file(String rawName) {
        migrate();
        String name = clean(rawName);
        Path path = fileFor(name);
        return !name.isEmpty() && Files.exists(path) ? path : null;
    }

    private static void migrate() {
        if (migrated) {
            return;
        }
        migrated = true;
        if (!Files.isDirectory(OLD_DIR)) {
            return;
        }
        try (Stream<Path> files = Files.list(OLD_DIR)) {
            Files.createDirectories(DIR);
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                Path target = DIR.resolve(file.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.move(file, target);
                }
            }
        } catch (IOException ignored) {
        }
    }

    static List<String> names() {
        migrate();
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return names;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            files.map(path -> path.getFileName().toString())
                    .filter(file -> file.endsWith(".json"))
                    .map(file -> file.substring(0, file.length() - 5))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException ignored) {
        }
        return names;
    }

    private static int runList(CommandContext<FabricClientCommandSource> context) {
        List<String> names = names();
        context.getSource().sendFeedback(prefix()
                .append(Component.literal(names.isEmpty() ? "No presets yet." : "Presets: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.join(", ", names)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  (/config set|save|delete <name>, /config public)").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static int runDelete(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Result result = delete(name);
        if (result.ok()) {
            PublicPresets.unshare(name, removed -> {
                if (removed > 0) {
                    context.getSource().sendFeedback(prefix().append(Component.literal("Also removed it from public presets.")
                            .withStyle(ChatFormatting.GREEN)));
                }
            }, error -> context.getSource().sendFeedback(prefix().append(Component.literal(
                    "Could not remove it from public presets: " + error + ".").withStyle(ChatFormatting.RED))));
        }
        return reply(context, result);
    }

    private static int reply(CommandContext<FabricClientCommandSource> context, Result result) {
        context.getSource().sendFeedback(prefix().append(Component.literal(result.message())
                .withStyle(result.ok() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return result.ok() ? 1 : 0;
    }

    private static String clean(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    private static Path fileFor(String name) {
        return DIR.resolve(name + ".json");
    }

    private static MutableComponent prefix() {
        return BazaarChat.prefix();
    }
}
