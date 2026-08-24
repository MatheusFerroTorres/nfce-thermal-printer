package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeasurementUtilsTest {
    @Test
    void convertsMillimetersToPoints() {
        assertEquals(226.7717f, MeasurementUtils.mmToPoints(80), 0.001f);
    }

    @Test
    void convertsMillimetersToPrinterDots() {
        assertEquals(639, MeasurementUtils.mmToDots(80, 203));
        assertEquals(575, MeasurementUtils.mmToDots(72, 203));
    }
}
