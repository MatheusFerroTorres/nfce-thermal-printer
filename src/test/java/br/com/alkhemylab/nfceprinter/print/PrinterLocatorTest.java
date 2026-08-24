package br.com.alkhemylab.nfceprinter.print;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrinterLocatorTest {
    @Test
    void prefersExactPrinterName() {
        int selected = PrinterLocator.selectIndex(
                List.of("elgin i9(usb)", "ELGIN i9(USB)"),
                "ELGIN i9(USB)");

        assertEquals(1, selected);
    }

    @Test
    void acceptsCaseDifferenceOnlyWhenExactNameIsAbsent() {
        int selected = PrinterLocator.selectIndex(
                List.of("Microsoft Print to PDF", "elgin i9(usb)"),
                "ELGIN i9(USB)");

        assertEquals(1, selected);
    }

    @Test
    void rejectsPartialPrinterName() {
        int selected = PrinterLocator.selectIndex(
                List.of("ELGIN i9", "ELGIN i9(USB) Copia"),
                "ELGIN i9(USB)");

        assertEquals(-1, selected);
    }
}
