package br.com.alkhemylab.nfceprinter.watch;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class SpoolLayout {
    private static final DateTimeFormatter COLLISION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path spoolDirectory;
    private final Path originalDirectory;
    private final Path previewDirectory;
    private final Path processedDirectory;
    private final Path printedDirectory;
    private final Path errorDirectory;
    private final Path duplicateDirectory;
    private final Path journalFile;
    private final Path initializedFile;
    private final Path lockFile;

    public SpoolLayout(ApplicationConfig config) {
        spoolDirectory = config.spoolDirectory().toAbsolutePath().normalize();
        originalDirectory = resolve(config.watchOriginalDirectory());
        previewDirectory = resolve(config.watchPreviewDirectory());
        processedDirectory = resolve(config.watchProcessedDirectory());
        printedDirectory = resolve(config.watchPrintedDirectory());
        errorDirectory = resolve(config.watchErrorDirectory());
        duplicateDirectory = resolve(config.watchDuplicateDirectory());
        journalFile = resolve(config.watchJournalFile());
        initializedFile = resolve(config.watchInitializedFile());
        lockFile = resolve(config.watchLockFile());
    }

    public void initialize() throws IOException {
        Files.createDirectories(spoolDirectory);
        Files.createDirectories(originalDirectory);
        Files.createDirectories(previewDirectory);
        Files.createDirectories(processedDirectory);
        Files.createDirectories(printedDirectory);
        Files.createDirectories(errorDirectory);
        Files.createDirectories(duplicateDirectory);
        createParent(journalFile);
        createParent(initializedFile);
        createParent(lockFile);
    }

    public Path newThermalOutput(Path source, String suffix, boolean printing) throws IOException {
        String filename = source.getFileName().toString();
        String lower = filename.toLowerCase(Locale.ROOT);
        String base = lower.endsWith(".pdf")
                ? filename.substring(0, filename.length() - 4)
                : filename;
        Path directory = printing ? processedDirectory : previewDirectory;
        return uniquePath(directory, base + suffix + ".pdf");
    }

    public Path archiveOriginal(Path source) throws IOException {
        return moveUnique(source, originalDirectory);
    }

    public Path stageOriginal(Path source) throws IOException {
        return moveUnique(source, processedDirectory);
    }

    public Path archivePrinted(Path source) throws IOException {
        return moveUnique(source, printedDirectory);
    }

    public Path archiveDuplicate(Path source) throws IOException {
        return moveUnique(source, duplicateDirectory);
    }

    public Path archiveError(Path source) throws IOException {
        return moveUnique(source, errorDirectory);
    }

    public Path writeErrorReport(String sourceName, String text) throws IOException {
        String safeName = Path.of(sourceName).getFileName().toString();
        String lower = safeName.toLowerCase(Locale.ROOT);
        String base = lower.endsWith(".pdf")
                ? safeName.substring(0, safeName.length() - 4)
                : safeName;
        Path report = uniquePath(errorDirectory, base + ".error.txt");
        return Files.writeString(
                report,
                text,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
    }

    public Path spoolDirectory() {
        return spoolDirectory;
    }

    public Path processedDirectory() {
        return processedDirectory;
    }

    public Path errorDirectory() {
        return errorDirectory;
    }

    public Path journalFile() {
        return journalFile;
    }

    public Path initializedFile() {
        return initializedFile;
    }

    public Path lockFile() {
        return lockFile;
    }

    private Path resolve(Path relative) {
        Path resolved = spoolDirectory.resolve(relative).normalize();
        if (!resolved.startsWith(spoolDirectory)) {
            throw new IllegalArgumentException("Caminho do monitor fora de spool.directory: " + relative);
        }
        return resolved;
    }

    private Path uniquePath(Path directory, String filename) throws IOException {
        Files.createDirectories(directory);
        Path first = directory.resolve(Path.of(filename).getFileName());
        if (!Files.exists(first)) {
            return first;
        }

        String timestamp = COLLISION_TIMESTAMP.format(LocalDateTime.now());
        String name = first.getFileName().toString();
        int extensionIndex = name.lastIndexOf('.');
        String base = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        String extension = extensionIndex > 0 ? name.substring(extensionIndex) : "";
        for (int counter = 1; counter <= 10_000; counter++) {
            Path candidate = directory.resolve(base + "-" + timestamp + "-" + counter + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Nao foi possivel reservar um nome unico em: " + directory);
    }

    private Path moveUnique(Path source, Path directory) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource)) {
            throw new IOException("Arquivo nao encontrado para arquivamento: " + normalizedSource);
        }

        for (int attempt = 0; attempt < 10_000; attempt++) {
            Path destination = uniquePath(directory, normalizedSource.getFileName().toString());
            try {
                return Files.move(normalizedSource, destination);
            } catch (FileAlreadyExistsException exception) {
                // Outro processo ocupou o nome entre a verificacao e o move; tenta outro.
            }
        }
        throw new IOException("Nao foi possivel arquivar o arquivo com nome unico: " + normalizedSource);
    }

    private void createParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
