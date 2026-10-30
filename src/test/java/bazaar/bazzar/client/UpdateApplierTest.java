package bazaar.bazzar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// the jar swap that runs after Minecraft closes: it has to survive the file still being held open,
// and it must never throw away the download unless the new jar really did land
class UpdateApplierTest {
    private static final String OLD_JAR = "Bazzar_v1.23_1.21.11.jar";

    private static Path write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    @Test
    void swapsTheNewJarOverTheInstalledOne(@TempDir Path dir) throws Exception {
        Path source = write(dir.resolve("update/Bazzar.jar"), "the new build");
        Path target = write(dir.resolve("mods/" + OLD_JAR), "the old build");

        assertNull(UpdateApplier.swap(source, target, 1_000L, 10L));
        assertEquals("the new build", Files.readString(target));
        // the download is still there; only main() clears it, once the swap has reported back
        assertTrue(Files.isRegularFile(source));
    }

    @Test
    void leavesNoHalfWrittenFileBehind(@TempDir Path dir) throws Exception {
        Path source = write(dir.resolve("update/Bazzar.jar"), "the new build");
        Path target = write(dir.resolve("mods/" + OLD_JAR), "the old build");

        UpdateApplier.swap(source, target, 1_000L, 10L);
        assertFalse(Files.exists(target.resolveSibling(OLD_JAR + ".new")));
    }

    @Test
    void installsOverAJarThatIsNotThereAnyMore(@TempDir Path dir) throws Exception {
        // the player deleted the old jar by hand between the download and the restart
        Path source = write(dir.resolve("update/Bazzar.jar"), "the new build");
        Path target = dir.resolve("mods/" + OLD_JAR);
        Files.createDirectories(target.getParent());

        assertNull(UpdateApplier.swap(source, target, 1_000L, 10L));
        assertEquals("the new build", Files.readString(target));
    }

    @Test
    void givesUpWithAReasonWhenTheJarCannotBeWritten(@TempDir Path dir) throws Exception {
        Path source = write(dir.resolve("update/Bazzar.jar"), "the new build");
        // a directory where the jar should be can never be replaced by a file
        Path target = dir.resolve("mods/" + OLD_JAR);
        Files.createDirectories(target);
        Files.writeString(target.resolve("keeps it busy"), "x");

        String failure = UpdateApplier.swap(source, target, 150L, 10L);
        assertNotNull(failure);
        assertFalse(failure.isBlank());
        // the download survives, so the next shutdown can try again
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.exists(target.resolveSibling(OLD_JAR + ".new")));
    }

    @Test
    void keepsTryingUntilTheDeadline(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("update/Bazzar.jar");
        Files.createDirectories(source.getParent());
        Path target = write(dir.resolve("mods/" + OLD_JAR), "the old build");

        long started = System.currentTimeMillis();
        String failure = UpdateApplier.swap(source, target, 200L, 20L);
        long took = System.currentTimeMillis() - started;

        // nothing to copy from, so it should have retried rather than bailing on the first miss
        assertNotNull(failure);
        assertTrue(took >= 200L, "gave up after " + took + "ms");
        assertEquals("the old build", Files.readString(target));
    }

    @Test
    void missingSourceDoesNotWipeTheInstalledJar(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("update/Bazzar.jar");
        Files.createDirectories(source.getParent());
        Path target = write(dir.resolve("mods/" + OLD_JAR), "the old build");

        assertNotNull(UpdateApplier.swap(source, target, 100L, 10L));
        assertTrue(Files.isRegularFile(target));
        assertEquals("the old build", Files.readString(target));
    }

    @Test
    void theLogKeepsOnlyTheLastAttempt(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("update/update.log");
        UpdateApplier.write(log, "the updater was started but never reported back");
        UpdateApplier.write(log, "installed " + OLD_JAR);

        String written = Files.readString(log);
        assertTrue(written.endsWith("installed " + OLD_JAR), written);
        assertFalse(written.contains("never reported back"));
        // a timestamp, a space, then the part the mod reads back to the player
        assertTrue(written.indexOf(' ') > 0);
    }

    @Test
    void theLogSurvivesAMissingFolder(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gone/update.log");
        UpdateApplier.write(log, "installed something");
        assertTrue(Files.isRegularFile(log));
    }
}
