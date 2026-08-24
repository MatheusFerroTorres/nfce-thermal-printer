package br.com.alkhemylab.nfceprinter.watch;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.logging.Logger;

public final class FileStabilityWaiter {
    private static final Logger LOGGER = Logger.getLogger(FileStabilityWaiter.class.getName());

    private final Duration interval;
    private final int requiredStableChecks;
    private final Duration timeout;

    public FileStabilityWaiter(Duration interval, int requiredStableChecks, Duration timeout) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("O intervalo de estabilidade deve ser positivo.");
        }
        if (requiredStableChecks <= 0) {
            throw new IllegalArgumentException("A quantidade de verificacoes estaveis deve ser positiva.");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("O timeout de estabilidade deve ser positivo.");
        }
        this.interval = interval;
        this.requiredStableChecks = requiredStableChecks;
        this.timeout = timeout;
    }

    public boolean waitUntilStable(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Snapshot previous = snapshot(file);
        int stableChecks = 0;

        while (System.nanoTime() < deadline) {
            Thread.sleep(interval.toMillis());
            Snapshot current = snapshot(file);
            if (current != null && current.equals(previous) && isReadable(file, current)) {
                stableChecks++;
                if (stableChecks >= requiredStableChecks) {
                    LOGGER.info(() -> "Arquivo estavel e pronto para processamento: " + file
                            + "; bytes=" + current.size());
                    return true;
                }
            } else {
                stableChecks = 0;
            }
            previous = current;
        }

        LOGGER.warning(() -> "Tempo esgotado aguardando o termino da gravacao: " + file);
        return false;
    }

    private Snapshot snapshot(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return null;
            }
            return new Snapshot(attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean isReadable(Path file, Snapshot snapshot) {
        if (snapshot.size() <= 0) {
            return false;
        }
        try (SeekableByteChannel ignored = Files.newByteChannel(file, StandardOpenOption.READ)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private record Snapshot(long size, long lastModifiedMillis) {
    }
}
