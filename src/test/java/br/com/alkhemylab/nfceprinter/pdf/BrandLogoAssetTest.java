package br.com.alkhemylab.nfceprinter.pdf;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandLogoAssetTest {
    @Test
    void thermalLogoContainsOnlyOpaqueBlackAndTransparentPixels() throws Exception {
        try (InputStream input = BrandLogoAssetTest.class
                .getResourceAsStream("/assets/alkhemylab-logo-thermal.png")) {
            assertNotNull(input);
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image);
            assertEquals(392, image.getWidth());
            assertEquals(99, image.getHeight());

            int opaquePixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xff;
                    assertTrue(alpha == 0 || alpha == 255);
                    if (alpha == 255) {
                        opaquePixels++;
                        assertEquals(0, argb & 0x00ffffff);
                    }
                }
            }
            assertTrue(opaquePixels > 0);
        }
    }
}
