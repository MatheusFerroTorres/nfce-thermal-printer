package br.com.alkhemylab.nfceprinter.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStabilityWaiterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsReadableNonEmptyFileAfterConsecutiveStableChecks()
            throws IOException, InterruptedException {
        Path file = temporaryDirectory.resolve("nota.pdf");
        Files.write(file, new byte[]{1, 2, 3});
        FileStabilityWaiter waiter = new FileStabilityWaiter(
                Duration.ofMillis(5),
                2,
                Duration.ofMillis(200));

        assertTrue(waiter.waitUntilStable(file));
    }

    @Test
    void rejectsEmptyFileAtTimeout() throws IOException, InterruptedException {
        Path file = temporaryDirectory.resolve("nota.pdf");
        Files.createFile(file);
        FileStabilityWaiter waiter = new FileStabilityWaiter(
                Duration.ofMillis(5),
                1,
                Duration.ofMillis(30));

        assertFalse(waiter.waitUntilStable(file));
    }
}
