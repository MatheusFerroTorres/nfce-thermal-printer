package br.com.alkhemylab.nfceprinter.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WatcherInstanceLockTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSecondWatcherAndAllowsReacquisitionAfterClose() throws IOException {
        Path lockFile = temporaryDirectory.resolve("watcher.lock");

        try (WatcherInstanceLock ignored = WatcherInstanceLock.acquire(lockFile)) {
            assertThrows(IOException.class, () -> WatcherInstanceLock.acquire(lockFile));
        }

        assertDoesNotThrow(() -> {
            try (WatcherInstanceLock ignored = WatcherInstanceLock.acquire(lockFile)) {
                // A liberacao do primeiro lock permite uma nova instancia.
            }
        });
    }
}
