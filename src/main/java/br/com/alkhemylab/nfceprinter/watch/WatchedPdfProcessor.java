package br.com.alkhemylab.nfceprinter.watch;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.pdf.PdfThermalConverter;
import br.com.alkhemylab.nfceprinter.print.PdfWindowsPrinter;
import br.com.alkhemylab.nfceprinter.watch.ProcessingJournal.Disposition;
import br.com.alkhemylab.nfceprinter.watch.ProcessingJournal.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WatchedPdfProcessor {
    private static final Logger LOGGER = Logger.getLogger(WatchedPdfProcessor.class.getName());

    private final ApplicationConfig config;
    private final SpoolLayout layout;
    private final ProcessingJournal journal;
    private final FileStabilityWaiter stabilityWaiter;
    private final PdfThermalConverter converter;
    private final PdfWindowsPrinter printer;

    public WatchedPdfProcessor(
            ApplicationConfig config,
            SpoolLayout layout,
            ProcessingJournal journal,
            FileStabilityWaiter stabilityWaiter) {
        this(config, layout, journal, stabilityWaiter, new PdfThermalConverter(), new PdfWindowsPrinter());
    }

    WatchedPdfProcessor(
            ApplicationConfig config,
            SpoolLayout layout,
            ProcessingJournal journal,
            FileStabilityWaiter stabilityWaiter,
            PdfThermalConverter converter,
            PdfWindowsPrinter printer) {
        this.config = config;
        this.layout = layout;
        this.journal = journal;
        this.stabilityWaiter = stabilityWaiter;
        this.converter = converter;
        this.printer = printer;
    }

    public ProcessOutcome process(Path source) throws IOException, InterruptedException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            return ProcessOutcome.NOT_FOUND;
        }
        if (!stabilityWaiter.waitUntilStable(normalizedSource)) {
            return ProcessOutcome.DEFERRED;
        }

        DocumentIdentity identity = DocumentIdentity.from(normalizedSource);
        Disposition disposition = journal.disposition(identity);
        boolean completedReprint = disposition == Disposition.ALREADY_COMPLETED;
        if (completedReprint && !config.watchAllowCompletedReprint()) {
            journal.append(Status.DUPLICATE_ARCHIVED, identity, "Documento ja concluido anteriormente.");
            Path archived = layout.archiveDuplicate(normalizedSource);
            LOGGER.warning(() -> "Documento duplicado nao foi reimpresso: " + identity.sourceName()
                    + "; destino=" + archived);
            return ProcessOutcome.DUPLICATE;
        }
        if (disposition == Disposition.PRINT_STATUS_UNCERTAIN) {
            journal.append(
                    Status.MANUAL_REVIEW,
                    identity,
                    "Reenvio bloqueado porque uma tentativa anterior pode ter chegado ao spooler.");
            quarantine(
                    normalizedSource,
                    null,
                    identity,
                    "PRINT_STATUS_UNCERTAIN",
                    "A impressao anterior pode ter sido enviada. Conferir fisicamente antes de reimprimir.");
            return ProcessOutcome.MANUAL_REVIEW;
        }

        journal.append(
                Status.PROCESSING,
                identity,
                completedReprint
                        ? "Inicio de reimpressao solicitada pelo usuario."
                        : "Inicio da conversao automatica.");
        if (completedReprint) {
            LOGGER.info(() -> "REPRINT_REQUEST_ACCEPTED: arquivo=" + identity.sourceName());
        }
        Path thermalOutput = layout.newThermalOutput(
                normalizedSource,
                config.outputSuffix(),
                config.autoPrint());

        PdfThermalConverter.ConversionResult conversion;
        try {
            conversion = converter.convert(normalizedSource, thermalOutput, config);
        } catch (Exception exception) {
            recordDocumentFailure(identity, normalizedSource, thermalOutput, "CONVERSION_FAILED", exception);
            return Files.exists(normalizedSource) ? ProcessOutcome.DEFERRED : ProcessOutcome.FAILED;
        }

        if (!config.autoPrint()) {
            try {
                Path archivedOriginal = layout.archiveOriginal(normalizedSource);
                journal.append(
                        Status.PREVIEW_READY,
                        identity,
                        "preview=" + conversion.output() + "; original=" + archivedOriginal);
                LOGGER.info(() -> "PREVIEW_READY: arquivo=" + identity.sourceName()
                        + "; dimensoes=" + conversion.dimensionsSummary()
                        + "; preview=" + conversion.output());
                return ProcessOutcome.PREVIEW_READY;
            } catch (Exception exception) {
                recordDocumentFailure(identity, normalizedSource, thermalOutput, "PREVIEW_ARCHIVE_FAILED", exception);
                return Files.exists(normalizedSource) ? ProcessOutcome.DEFERRED : ProcessOutcome.FAILED;
            }
        }

        Path stagedOriginal;
        try {
            stagedOriginal = layout.stageOriginal(normalizedSource);
            LOGGER.info(() -> "ORIGINAL_STAGED_FOR_PRINT: arquivo=" + identity.sourceName()
                    + "; destino=" + stagedOriginal);
        } catch (Exception exception) {
            recordDocumentFailure(
                    identity,
                    normalizedSource,
                    thermalOutput,
                    "ORIGINAL_STAGING_FAILED",
                    exception);
            return Files.exists(normalizedSource) ? ProcessOutcome.DEFERRED : ProcessOutcome.FAILED;
        }

        return printAndArchive(identity, stagedOriginal, conversion);
    }

    private ProcessOutcome printAndArchive(
            DocumentIdentity identity,
            Path original,
            PdfThermalConverter.ConversionResult conversion) throws IOException {
        AtomicBoolean submissionStarted = new AtomicBoolean(false);
        PdfWindowsPrinter.PrintResult printResult;
        try {
            printResult = printer.print(conversion.output(), config, () -> {
                journal.append(
                        Status.PRINT_SUBMITTING,
                        identity,
                        "printer=" + config.printerName() + "; pdf=" + conversion.output());
                submissionStarted.set(true);
            });
        } catch (Exception exception) {
            Status failureStatus = submissionStarted.get() ? Status.PRINT_UNCERTAIN : Status.FAILED;
            String state = submissionStarted.get() ? "PRINT_UNCERTAIN" : "PRINT_NOT_SUBMITTED";
            journal.append(failureStatus, identity, exceptionSummary(exception));
            quarantine(original, conversion.output(), identity, state, exceptionSummary(exception));
            LOGGER.log(Level.SEVERE, "Falha na impressao automatica de " + identity.sourceName(), exception);
            return Files.exists(original) && !submissionStarted.get()
                    ? ProcessOutcome.DEFERRED
                    : ProcessOutcome.FAILED;
        }

        try {
            journal.append(
                    Status.SPOOL_ACCEPTED,
                    identity,
                    "printer=" + printResult.printerName() + "; job=" + printResult.jobName());
        } catch (IOException journalException) {
            quarantine(
                    original,
                    conversion.output(),
                    identity,
                    "SPOOL_ACCEPTED_JOURNAL_FAILED",
                    "O spooler aceitou o trabalho, mas nao foi possivel confirmar no diario: "
                            + exceptionSummary(journalException));
            LOGGER.log(
                    Level.SEVERE,
                    "O spooler aceitou a nota, mas o registro persistente falhou. Reimpressao automatica bloqueada.",
                    journalException);
            return ProcessOutcome.MANUAL_REVIEW;
        }

        try {
            Path printed = layout.archivePrinted(conversion.output());
            Path archivedOriginal = layout.archiveOriginal(original);
            LOGGER.info(() -> "SPOOL_ACCEPTED_AND_ARCHIVED: arquivo=" + identity.sourceName()
                    + "; impressora=" + printResult.printerName()
                    + "; impresso=" + printed
                    + "; original=" + archivedOriginal);
        } catch (IOException archiveException) {
            LOGGER.log(
                    Level.SEVERE,
                    "Impressao aceita, mas houve falha no arquivamento. Arquivos enviados para revisao.",
                    archiveException);
            quarantine(
                    original,
                    conversion.output(),
                    identity,
                    "SPOOL_ACCEPTED_ARCHIVE_FAILED",
                    "A impressao foi aceita; nao reimprimir automaticamente. " + exceptionSummary(archiveException));
        }
        return ProcessOutcome.PRINTED;
    }

    private void recordDocumentFailure(
            DocumentIdentity identity,
            Path original,
            Path thermal,
            String state,
            Exception exception) throws IOException {
        journal.append(Status.FAILED, identity, state + ": " + exceptionSummary(exception));
        quarantine(original, thermal, identity, state, exceptionSummary(exception));
        LOGGER.log(Level.SEVERE, "Documento enviado para Erro: " + identity.sourceName(), exception);
    }

    private void quarantine(
            Path original,
            Path thermal,
            DocumentIdentity identity,
            String state,
            String message) {
        moveToErrorBestEffort(thermal);
        moveToErrorBestEffort(original);
        writeReportBestEffort(identity.sourceName(), state, message);
    }

    private void moveToErrorBestEffort(Path file) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            layout.archiveError(file);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Falha ao mover arquivo para a pasta Erro: " + file, exception);
        }
    }

    private void writeReportBestEffort(String sourceName, String state, String message) {
        String report = "horario=" + Instant.now() + System.lineSeparator()
                + "arquivo=" + sourceName + System.lineSeparator()
                + "estado=" + state + System.lineSeparator()
                + "mensagem=" + message + System.lineSeparator();
        try {
            layout.writeErrorReport(sourceName, report);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Falha ao gravar relatorio de erro para " + sourceName, exception);
        }
    }

    private static String exceptionSummary(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public enum ProcessOutcome {
        NOT_FOUND,
        DEFERRED,
        PREVIEW_READY,
        PRINTED,
        DUPLICATE,
        MANUAL_REVIEW,
        FAILED
    }
}
