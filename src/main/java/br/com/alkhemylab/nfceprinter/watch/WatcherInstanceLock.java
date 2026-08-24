package br.com.alkhemylab.nfceprinter.watch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class WatcherInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WatcherInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static WatcherInstanceLock acquire(Path lockFile) throws IOException {
        Path normalized = lockFile.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(
                normalized,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("Ja existe outra instancia monitorando esta pasta: " + normalized);
            }

            String owner = "pid=" + ProcessHandle.current().pid()
                    + System.lineSeparator()
                    + "started=" + Instant.now()
                    + System.lineSeparator();
            channel.truncate(0);
            channel.position(0);
            channel.write(ByteBuffer.wrap(owner.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return new WatcherInstanceLock(channel, lock);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
