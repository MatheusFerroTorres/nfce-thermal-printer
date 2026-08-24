package br.com.alkhemylab.nfceprinter.watch;

import br.com.alkhemylab.nfceprinter.watch.ProcessingJournal.Disposition;
import br.com.alkhemylab.nfceprinter.watch.ProcessingJournal.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingJournalTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsCompletedDocumentsByFilenameAndHash() throws IOException {
        Path journalPath = temporaryDirectory.resolve("state/journal.tsv");
        DocumentIdentity completed = new DocumentIdentity("impressão nfc-e_1.pdf", HASH_A);
        ProcessingJournal journal = new ProcessingJournal(journalPath);
        assertTrue(ProcessingJournal.hasValidHeader(journalPath));
        journal.append(Status.PREVIEW_READY, completed, "ok");

        ProcessingJournal reloaded = new ProcessingJournal(journalPath);

        assertEquals(
                Disposition.ALREADY_COMPLETED,
                reloaded.disposition(new DocumentIdentity("IMPRESSÃO NFC-E_1.PDF", HASH_B)));
        assertEquals(
                Disposition.ALREADY_COMPLETED,
                reloaded.disposition(new DocumentIdentity("outro-nome.pdf", HASH_A)));
    }

    @Test
    void failedConversionRemainsEligibleForControlledRetry() throws IOException {
        ProcessingJournal journal = new ProcessingJournal(temporaryDirectory.resolve("journal.tsv"));
        DocumentIdentity identity = new DocumentIdentity("nota.pdf", HASH_A);

        journal.append(Status.PROCESSING, identity, "inicio");
        journal.append(Status.FAILED, identity, "pdf invalido");

        assertEquals(Disposition.NEW, journal.disposition(identity));
    }

    @Test
    void submissionWithoutConfirmationBlocksAutomaticRetryAfterRestart() throws IOException {
        Path journalPath = temporaryDirectory.resolve("journal.tsv");
        DocumentIdentity identity = new DocumentIdentity("nota.pdf", HASH_A);
        ProcessingJournal journal = new ProcessingJournal(journalPath);
        journal.append(Status.PRINT_SUBMITTING, identity, "printer=ELGIN i9(USB)");

        ProcessingJournal reloaded = new ProcessingJournal(journalPath);

        assertEquals(Disposition.PRINT_STATUS_UNCERTAIN, reloaded.disposition(identity));
    }

    @Test
    void spoolConfirmationTurnsUncertainDocumentIntoCompleted() throws IOException {
        Path journalPath = temporaryDirectory.resolve("journal.tsv");
        DocumentIdentity identity = new DocumentIdentity("nota.pdf", HASH_A);
        ProcessingJournal journal = new ProcessingJournal(journalPath);
        journal.append(Status.PRINT_SUBMITTING, identity, "inicio");
        journal.append(Status.SPOOL_ACCEPTED, identity, "aceito");

        assertEquals(Disposition.ALREADY_COMPLETED, journal.disposition(identity));
    }

    @Test
    void newAttemptReopensCompletedDocumentAndTracksItsOwnSubmission() throws IOException {
        Path journalPath = temporaryDirectory.resolve("journal.tsv");
        DocumentIdentity identity = new DocumentIdentity("nota.pdf", HASH_A);
        ProcessingJournal journal = new ProcessingJournal(journalPath);
        journal.append(Status.PRINT_SUBMITTING, identity, "primeira tentativa");
        journal.append(Status.SPOOL_ACCEPTED, identity, "primeira aceita");
        assertEquals(Disposition.ALREADY_COMPLETED, journal.disposition(identity));

        journal.append(Status.PROCESSING, identity, "reimpressao solicitada");
        assertEquals(Disposition.NEW, journal.disposition(identity));

        journal.append(Status.PRINT_SUBMITTING, identity, "segunda tentativa");
        assertEquals(Disposition.PRINT_STATUS_UNCERTAIN, journal.disposition(identity));

        journal.append(Status.SPOOL_ACCEPTED, identity, "segunda aceita");
        assertEquals(Disposition.ALREADY_COMPLETED, journal.disposition(identity));
    }

    @Test
    void ignoresMalformedLinesWithoutDiscardingValidHistory() throws IOException {
        Path journalPath = temporaryDirectory.resolve("journal.tsv");
        Files.writeString(journalPath, "linha-invalida\n", StandardCharsets.UTF_8);
        ProcessingJournal journal = new ProcessingJournal(journalPath);
        assertTrue(journal.hasInvalidEntries());
        assertFalse(ProcessingJournal.hasValidHeader(journalPath));
        DocumentIdentity identity = new DocumentIdentity("nota.pdf", HASH_A);
        journal.append(Status.SPOOL_ACCEPTED, identity, "aceito");

        ProcessingJournal reloaded = new ProcessingJournal(journalPath);

        assertEquals(Disposition.ALREADY_COMPLETED, reloaded.disposition(identity));
    }

    @Test
    void removesIncompleteTailLeftByAbruptShutdown() throws IOException {
        Path journalPath = temporaryDirectory.resolve("journal.tsv");
        DocumentIdentity completed = new DocumentIdentity("nota.pdf", HASH_A);
        ProcessingJournal journal = new ProcessingJournal(journalPath);
        journal.append(Status.SPOOL_ACCEPTED, completed, "aceito");
        Files.writeString(
                journalPath,
                "1\tregistro-parcial-sem-quebra",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        ProcessingJournal repaired = new ProcessingJournal(journalPath);
        repaired.append(
                Status.PREVIEW_READY,
                new DocumentIdentity("outra.pdf", HASH_B),
                "ok");

        ProcessingJournal reloaded = new ProcessingJournal(journalPath);
        assertEquals(Disposition.ALREADY_COMPLETED, reloaded.disposition(completed));
        assertEquals(
                Disposition.ALREADY_COMPLETED,
                reloaded.disposition(new DocumentIdentity("outra.pdf", HASH_B)));
    }
}
