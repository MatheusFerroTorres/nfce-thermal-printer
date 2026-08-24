package br.com.alkhemylab.nfceprinter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {
    @Test
    void derivesThermalOutputBesideOriginal() {
        Path input = Path.of("C:\\Spool\\impressao nfc-e_123.pdf");
        Path output = Main.deriveOutputPath(input, "_termico");
        assertEquals("C:\\Spool\\impressao nfc-e_123_termico.pdf", output.toString());
    }
}
