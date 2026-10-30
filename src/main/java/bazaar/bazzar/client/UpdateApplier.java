package bazaar.bazzar.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

// Swaps the downloaded jar over the installed one once Minecraft has let go of it. This runs as its
// own bare JVM started while Minecraft is shutting down, so nothing in here may touch Minecraft,
// Fabric or any other class from the mod jar: by the time it does its work that jar is being replaced.
public final class UpdateApplier {
    // Windows holds the jar open until Minecraft has fully exited, and a big world can take a while
    // to save, so keep trying long past the point where the old sixty seconds gave up
    static final long SWAP_DEADLINE_MS = 10L * 60_000L;
    static final long RETRY_GAP_MS = 1_000L;
    private static final long EXIT_WAIT_MS = 5L * 60_000L;
    private static final long EXIT_POLL_MS = 500L;

    private UpdateApplier() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 4) {
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            return;
        }
        Path source = Path.of(args[1]);
        Path target = Path.of(args[2]);
        Path versionFile = Path.of(args[3]);
        Path log = source.resolveSibling("update.log");

        awaitExit(pid);
        String failure = swap(source, target, SWAP_DEADLINE_MS, RETRY_GAP_MS);
        write(log, failure == null ? "installed " + target.getFileName() : "could not replace the jar, " + failure);
        if (failure != null) {
            // the download stays put so the next shutdown can have another go at it
            return;
        }
        try {
            Files.deleteIfExists(source);
            Files.deleteIfExists(versionFile);
        } catch (IOException ignored) {
        }
    }

    // copies the new jar over the old one, retrying while Minecraft still has the file open
    static String swap(Path source, Path target, long deadlineMs, long retryGapMs) throws InterruptedException {
        Path staged = target.resolveSibling(target.getFileName() + ".new");
        long giveUpAt = System.currentTimeMillis() + deadlineMs;
        String lastError = "nothing to install";
        while (true) {
            try {
                long wanted = Files.size(source);
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                // the swap only counts once the installed jar really is the new one
                long landed = Files.size(target);
                if (landed == wanted) {
                    return null;
                }
                lastError = "it ended up " + landed + " bytes instead of " + wanted;
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            if (System.currentTimeMillis() >= giveUpAt) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                }
                return lastError;
            }
            Thread.sleep(retryGapMs);
        }
    }

    // the old version gave up waiting the moment anything went wrong and started copying while
    // Minecraft was still running, so fall back to watching the pid instead of pressing on
    private static void awaitExit(long pid) throws InterruptedException {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle != null) {
            try {
                handle.onExit().get(EXIT_WAIT_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (Exception ignored) {
            }
        }
        long giveUpAt = System.currentTimeMillis() + EXIT_WAIT_MS;
        while (System.currentTimeMillis() < giveUpAt && alive(pid)) {
            Thread.sleep(EXIT_POLL_MS);
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    // one line, replaced every attempt, so the mod can say what happened the last time it tried
    static void write(Path log, String line) {
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, Instant.now() + " " + line);
        } catch (IOException ignored) {
        }
    }
}
