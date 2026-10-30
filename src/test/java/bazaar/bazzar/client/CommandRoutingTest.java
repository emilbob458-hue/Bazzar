package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

// Reads the mod's own source, because this is the one mistake the other tests here cannot see.
//
// There are two ways to send a command. connection.send(new ServerboundChatCommandPacket(..)) puts
// it on the wire. connection.sendCommand(..) runs the client command dispatcher first, and this mod
// registers client commands of its own: /cookie is both a command you can type and a command the
// mod sends. Routed the second way, "cookie cobbled" comes back into the mod's own /cookie handler,
// which sends it again, once a second, for ever.
//
// Both forms compile, both read as "send this command", and every unit test passes either way,
// because nothing here has a connection to watch. Only the source says which one is in use.
class CommandRoutingTest {
    private static final String DISPATCHER_CALL = "connection.sendCommand(";
    private static final String WIRE_CALL = "ServerboundChatCommandPacket";
    private static final String SENDER = "CommandSender.java";

    private static Path mainSources() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("src/main/java/bazaar/bazzar/client");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find the mod sources from " + Path.of("").toAbsolutePath());
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(mainSources())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSourcesAreWhereWeThinkTheyAre() {
        List<Path> files = javaFiles();
        assertTrue(files.size() > 20, "only found " + files.size() + " source files");
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals(SENDER)));
    }

    @Test
    void nothingSendsACommandThroughTheClientDispatcher() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (read(file).contains(DISPATCHER_CALL)) {
                offenders.add(file.getFileName().toString());
            }
        }
        if (!offenders.isEmpty()) {
            fail("These call " + DISPATCHER_CALL + "), which runs the mod's own client commands and can"
                    + " loop back on itself. Send through CommandSender instead: " + offenders);
        }
    }

    @Test
    void theOneSenderPutsCommandsStraightOnTheWire() {
        Path sender = javaFiles().stream()
                .filter(p -> p.getFileName().toString().equals(SENDER))
                .findFirst()
                .orElseThrow();
        String source = read(sender);
        assertTrue(source.contains(WIRE_CALL),
                SENDER + " must send " + WIRE_CALL + ", or every command it sends goes through the"
                        + " client dispatcher first");
        assertFalse(source.contains(DISPATCHER_CALL),
                SENDER + " must not use " + DISPATCHER_CALL + "), that is the loop");
    }

    @Test
    void everyCommandLeavesThroughTheOneSender() {
        // the rate limit is only a rate limit if nothing can go around it
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (name.equals(SENDER)) {
                continue;
            }
            if (read(file).contains(WIRE_CALL)) {
                offenders.add(name);
            }
        }
        if (!offenders.isEmpty()) {
            fail("These send command packets themselves, skipping the rate limit that stops the server"
                    + " kicking for commands sent too fast: " + offenders);
        }
    }

    @Test
    void theReasonIsWrittenDownNextToTheCode() {
        // this one exists because the original code was right and got "tidied" into the bug: the
        // raw packet looked like an arbitrary choice with nothing saying otherwise
        String source = read(javaFiles().stream()
                .filter(p -> p.getFileName().toString().equals(SENDER))
                .findFirst()
                .orElseThrow());
        String comments = Stream.of(source.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("//"))
                .map(line -> line.toLowerCase(Locale.ROOT))
                .reduce("", (a, b) -> a + " " + b);
        assertTrue(comments.contains("sendcommand") && comments.contains("dispatcher"),
                SENDER + " should say why it sends a packet rather than calling sendCommand");
    }
}
