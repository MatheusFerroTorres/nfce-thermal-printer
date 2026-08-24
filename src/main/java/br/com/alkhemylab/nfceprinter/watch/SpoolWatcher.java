package br.com.alkhemylab.nfceprinter.watch;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.watch.WatchedPdfProcessor.ProcessOutcome;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class SpoolWatcher {
    private static final Logger LOGGER = Logger.getLogger(SpoolWatcher.class.getName());
    private static final String INITIALIZATION_HEADER = "# nfce-printer-initialized-v1";

    private final ApplicationConfig config;
    private final SpoolLayout layout;
    private final SpoolFileMatcher matcher;

    public SpoolWatcher(ApplicationConfig config) {
        this.config = config;
        this.layout = new SpoolLayout(config);
        this.matcher = new SpoolFileMatcher(config.watchFileGlob(), config.outputSuffix());
    }

    public void run() throws IOException, InterruptedException {
        layout.initialize();

        try (WatcherInstanceLock ignored = WatcherInstanceLock.acquire(layout.lockFile())) {
            validateSafeStartup();
            recoverInterruptedWork();

            ProcessingJournal journal = new ProcessingJournal(layout.journalFile());
            if (config.autoPrint() && journal.hasInvalidEntries()) {
                throw new IOException(
                        "Impressao automatica bloqueada: o diario possui registros invalidos. "
                                + "Preserve .nfce-printer e revise o log antes de continuar.");
            }
            FileStabilityWaiter stabilityWaiter = new FileStabilityWaiter(
                    Duration.ofMillis(config.watchStabilityIntervalMillis()),
                    config.watchStabilityRequiredChecks(),
                    Duration.ofSeconds(config.watchStabilityTimeoutSeconds()));
            WatchedPdfProcessor processor = new WatchedPdfProcessor(
                    config,
                    layout,
                    journal,
                    stabilityWaiter);

            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                layout.spoolDirectory().register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                if (config.watchInitialScan()) {
                    scanExisting(processor, true);
                }

                if (!config.autoPrint() && !isInitialized()) {
                    if (!config.watchInitialScan()) {
                        throw new IOException("A primeira execucao do monitor exige watch.initial.scan=true.");
                    }
                    createInitializationMarker();
                }

                announceReady(journal);
                watchLoop(watchService, processor);
            }
        }
    }

    private void validateSafeStartup() throws IOException {
        if (config.autoPrint()
                && (!isInitialized() || !ProcessingJournal.hasValidHeader(layout.journalFile()))) {
            throw new IOException(
                    "Impressao automatica bloqueada na primeira execucao. "
                            + "Defina auto.print=false, execute --watch uma vez e valide a pasta Preview. "
                            + "O marcador e o diario persistente precisam estar integros.");
        }
    }

    private void watchLoop(WatchService watchService, WatchedPdfProcessor processor)
            throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key = watchService.take();
            Set<Path> candidates = new LinkedHashSet<>();
            boolean overflow = false;

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    overflow = true;
                    continue;
                }
                Object context = event.context();
                if (context instanceof Path relative) {
                    candidates.add(layout.spoolDirectory().resolve(relative).normalize());
                }
            }

            if (!key.reset()) {
                throw new IOException("O registro do WatchService para a pasta deixou de ser valido.");
            }

            if (overflow) {
                LOGGER.warning("WatchService informou OVERFLOW; executando nova varredura da pasta.");
                scanExisting(processor, false);
            }
            for (Path candidate : candidates) {
                processCandidate(processor, candidate, false);
            }
        }
    }

    private void scanExisting(WatchedPdfProcessor processor, boolean failWhenDeferred)
            throws IOException, InterruptedException {
        List<Path> existing;
        try (Stream<Path> files = Files.list(layout.spoolDirectory())) {
            existing = files
                    .filter(Files::isRegularFile)
                    .filter(matcher::matches)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        if (!existing.isEmpty()) {
            LOGGER.info(() -> "Varredura inicial encontrou " + existing.size() + " PDF(s) candidato(s).");
        }
        for (Path candidate : existing) {
            ProcessOutcome outcome = processCandidate(processor, candidate, failWhenDeferred);
            if (failWhenDeferred && outcome == ProcessOutcome.DEFERRED) {
                throw new IOException(
                        "Nao foi possivel concluir a varredura segura; arquivo permaneceu no spool: " + candidate);
            }
        }
    }

    private ProcessOutcome processCandidate(
            WatchedPdfProcessor processor,
            Path candidate,
            boolean failWhenDeferred) throws IOException, InterruptedException {
        if (!candidate.getParent().equals(layout.spoolDirectory()) || !matcher.matches(candidate)) {
            return ProcessOutcome.NOT_FOUND;
        }

        ProcessOutcome outcome = processor.process(candidate);
        if (outcome == ProcessOutcome.DEFERRED && !failWhenDeferred) {
            LOGGER.warning(() -> "Processamento adiado; uma nova alteracao sera aguardada: " + candidate);
        }
        return outcome;
    }

    private void recoverInterruptedWork() throws IOException {
        try (Stream<Path> files = Files.list(layout.processedDirectory())) {
            List<Path> interrupted = files.filter(Files::isRegularFile).toList();
            for (Path file : interrupted) {
                Path archived = layout.archiveError(file);
                String report = "horario=" + Instant.now() + System.lineSeparator()
                        + "arquivo=" + file.getFileName() + System.lineSeparator()
                        + "estado=ORPHANED_PROCESSED_FILE" + System.lineSeparator()
                        + "mensagem=Arquivo encontrado em Processado durante a inicializacao; revisar manualmente."
                        + System.lineSeparator();
                layout.writeErrorReport(file.getFileName().toString(), report);
                LOGGER.warning(() -> "Arquivo de trabalho de uma execucao interrompida movido para Erro: " + archived);
            }
        }
    }

    private boolean isInitialized() throws IOException {
        if (!Files.isRegularFile(layout.initializedFile())) {
            return false;
        }
        try (java.io.BufferedReader reader = Files.newBufferedReader(
                layout.initializedFile(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            return INITIALIZATION_HEADER.equals(reader.readLine());
        }
    }

    private void createInitializationMarker() throws IOException {
        if (Files.exists(layout.initializedFile())) {
            Path archived = layout.archiveError(layout.initializedFile());
            LOGGER.warning(() -> "Marcador de inicializacao invalido preservado em Erro: " + archived);
        }
        String content = INITIALIZATION_HEADER + System.lineSeparator()
                + "initialized=" + Instant.now() + System.lineSeparator()
                + "mode=preview" + System.lineSeparator()
                + "spool=" + layout.spoolDirectory() + System.lineSeparator();
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                layout.initializedFile(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        LOGGER.info(() -> "Linha de base segura criada: " + layout.initializedFile());
    }

    private void announceReady(ProcessingJournal journal) {
        String mode = config.autoPrint() ? "IMPRESSAO AUTOMATICA" : "PREVIEW (SEM IMPRESSAO)";
        LOGGER.info(() -> "Monitor ativo: pasta=" + layout.spoolDirectory()
                + "; filtro=" + config.watchFileGlob()
                + "; modo=" + mode
                + "; reimpressao_concluida="
                + (config.watchAllowCompletedReprint() ? "permitida" : "bloqueada")
                + "; diario=" + journal.path());
        System.out.println("Monitor de NFC-e ativo.");
        System.out.println("Pasta: " + layout.spoolDirectory());
        System.out.println("Filtro: " + config.watchFileGlob());
        System.out.println("Modo: " + mode);
        System.out.println("Reimpressao de nota concluida: "
                + (config.watchAllowCompletedReprint() ? "PERMITIDA" : "BLOQUEADA"));
        System.out.println("Pressione Ctrl+C para encerrar.");
    }
}
