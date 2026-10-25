package bazaar.bazzar.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

public final class UpdateApplier {
    private UpdateApplier() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 4) {
            return;
        }
        long pid = Long.parseLong(args[0]);
        Path source = Path.of(args[1]);
        Path target = Path.of(args[2]);
        Path versionFile = Path.of(args[3]);
        ProcessHandle.of(pid).ifPresent(handle -> {
            try {
                handle.onExit().get(3, TimeUnit.MINUTES);
            } catch (Exception ignored) {
            }
        });
        Path staged = target.resolveSibling(target.getFileName() + ".new");
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source);
                Files.deleteIfExists(versionFile);
                return;
            } catch (IOException e) {
                Thread.sleep(1000L);
            }
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException ignored) {
        }
    }
}
