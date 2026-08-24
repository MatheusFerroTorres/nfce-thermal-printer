package br.com.alkhemylab.nfceprinter.print;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfWindowsPrinterTest {
    @Test
    void buildsReadableJobNameWithoutPdfExtension() {
        String jobName = PdfWindowsPrinter.buildJobName(Path.of("nota_termico.pdf"));

        assertEquals("NFC-e - nota_termico", jobName);
    }

    @Test
    void limitsJobNameLengthForWindowsDrivers() {
        String jobName = PdfWindowsPrinter.buildJobName(Path.of("x".repeat(150) + ".pdf"));

        assertEquals(96, jobName.length());
        assertTrue(jobName.startsWith("NFC-e - "));
    }
}
