package br.com.alkhemylab.nfceprinter.watch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpoolFileMatcherTest {
    private final SpoolFileMatcher matcher =
            new SpoolFileMatcher("impressão nfc-e_*.pdf", "_termico");

    @Test
    void acceptsProtheusFilenameIgnoringCase() {
        assertTrue(matcher.matches(Path.of("IMPRESSÃO NFC-E_3526.pdf")));
    }

    @Test
    void acceptsEquivalentUnicodeFilename() {
        String decomposed = Normalizer.normalize(
                "impressão nfc-e_3526.pdf",
                Normalizer.Form.NFD);

        assertTrue(matcher.matches(Path.of(decomposed)));
    }

    @Test
    void rejectsThermalOutputAndUnrelatedPdfs() {
        assertFalse(matcher.matches(Path.of("impressão nfc-e_3526_termico.pdf")));
        assertFalse(matcher.matches(Path.of("relatorio.pdf")));
    }
}
