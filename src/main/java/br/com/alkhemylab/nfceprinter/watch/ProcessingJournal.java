package br.com.alkhemylab.nfceprinter.watch;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class ProcessingJournal {
    private static final Logger LOGGER = Logger.getLogger(ProcessingJournal.class.getName());
    private static final String FORMAT_VERSION = "1";
    private static final String HEADER = "# nfce-printer-processing-journal-v1";

    private final Path journalFile;
    private final Set<String> completedNames = new HashSet<>();
    private final Set<String> completedHashes = new HashSet<>();
    private final Set<String> uncertainNames = new HashSet<>();
    private final Set<String> uncertainHashes = new HashSet<>();
    private long validEntries;
    private long invalidEntries;

    public ProcessingJournal(Path journalFile) throws IOException {
        this.journalFile = journalFile.toAbsolutePath().normalize();
        Path parent = this.journalFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ensureJournalFile();
        repairIncompleteTail();
        load();
    }

    public synchronized Disposition disposition(DocumentIdentity identity) {
        String name = normalizedName(identity.sourceName());
        String hash = identity.sha256();
        if (completedNames.contains(name) || completedHashes.contains(hash)) {
            return Disposition.ALREADY_COMPLETED;
        }
        if (uncertainNames.contains(name) || uncertainHashes.contains(hash)) {
            return Disposition.PRINT_STATUS_UNCERTAIN;
        }
        return Disposition.NEW;
    }

    public synchronized void append(Status status, DocumentIdentity identity, String detail) throws IOException {
        String encodedName = encode(identity.sourceName());
        String encodedDetail = encode(detail == null ? "" : detail);
        String line = String.join(
                "\t",
                FORMAT_VERSION,
                Instant.now().toString(),
                status.name(),
                encodedName,
                identity.sha256(),
                encodedDetail) + System.lineSeparator();

        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                journalFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }

        apply(status, identity);
        validEntries++;
    }

    public synchronized boolean isEmpty() {
        return validEntries == 0;
    }

    public synchronized boolean hasInvalidEntries() {
        return invalidEntries > 0;
    }

    public Path path() {
        return journalFile;
    }

    public static boolean hasValidHeader(Path journalFile) throws IOException {
        Path normalized = journalFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            return HEADER.equals(reader.readLine());
        }
    }

    private void ensureJournalFile() throws IOException {
        if (Files.isRegularFile(journalFile) && Files.size(journalFile) > 0) {
            return;
        }

        byte[] bytes = (HEADER + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                journalFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void repairIncompleteTail() throws IOException {
        if (!Files.isRegularFile(journalFile) || Files.size(journalFile) == 0) {
            return;
        }

        try (FileChannel channel = FileChannel.open(
                journalFile,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            long size = channel.size();
            ByteBuffer lastByte = ByteBuffer.allocate(1);
            channel.position(size - 1);
            channel.read(lastByte);
            if (lastByte.array()[0] == '\n') {
                return;
            }

            long truncateAt = 0;
            ByteBuffer block = ByteBuffer.allocate(8 * 1024);
            long end = size;
            while (end > 0 && truncateAt == 0) {
                int length = (int) Math.min(block.capacity(), end);
                long start = end - length;
                block.clear();
                block.limit(length);
                channel.position(start);
                int read = channel.read(block);
                for (int index = read - 1; index >= 0; index--) {
                    if (block.array()[index] == '\n') {
                        truncateAt = start + index + 1;
                        break;
                    }
                }
                end = start;
            }

            channel.truncate(truncateAt);
            channel.force(true);
            long removedBytes = size - truncateAt;
            LOGGER.warning(() -> "Trecho incompleto removido do fim do diario " + journalFile
                    + "; bytes=" + removedBytes);
        }
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(journalFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                try {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 6 || !FORMAT_VERSION.equals(fields[0])) {
                        throw new IllegalArgumentException("formato nao reconhecido");
                    }
                    Instant.parse(fields[1]);
                    Status status = Status.valueOf(fields[2]);
                    DocumentIdentity identity = new DocumentIdentity(decode(fields[3]), fields[4]);
                    decode(fields[5]);
                    apply(status, identity);
                    validEntries++;
                } catch (RuntimeException exception) {
                    invalidEntries++;
                    long invalidLine = lineNumber;
                    LOGGER.warning(() -> "Linha invalida ignorada no diario " + journalFile
                            + ": " + invalidLine + " (" + exception.getMessage() + ")");
                }
            }
        }

        LOGGER.info(() -> "Diario de processamento carregado: " + journalFile
                + "; registros=" + validEntries
                + "; invalidos=" + invalidEntries);
    }

    private void apply(Status status, DocumentIdentity identity) {
        String name = normalizedName(identity.sourceName());
        String hash = identity.sha256();
        switch (status) {
            case PROCESSING -> {
                // Uma nova aparicao do PDF representa uma nova solicitacao. A tentativa
                // anterior deixa de ser o estado atual para que esta nova submissao seja
                // acompanhada de forma independente.
                completedNames.remove(name);
                completedHashes.remove(hash);
            }
            case PREVIEW_READY, SPOOL_ACCEPTED -> {
                completedNames.add(name);
                completedHashes.add(hash);
                uncertainNames.remove(name);
                uncertainHashes.remove(hash);
            }
            case PRINT_SUBMITTING, PRINT_UNCERTAIN -> {
                if (!completedNames.contains(name)) {
                    uncertainNames.add(name);
                }
                if (!completedHashes.contains(hash)) {
                    uncertainHashes.add(hash);
                }
            }
            case FAILED, DUPLICATE_ARCHIVED, MANUAL_REVIEW -> {
                // Esses estados nao tornam o documento elegivel nem concluido por si so.
            }
        }
    }

    private static String normalizedName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public enum Disposition {
        NEW,
        ALREADY_COMPLETED,
        PRINT_STATUS_UNCERTAIN
    }

    public enum Status {
        PROCESSING,
        PREVIEW_READY,
        PRINT_SUBMITTING,
        SPOOL_ACCEPTED,
        FAILED,
        PRINT_UNCERTAIN,
        DUPLICATE_ARCHIVED,
        MANUAL_REVIEW
    }
}
