package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public final class BrandLogoLoader {
    public Optional<BrandLogo> load(PDDocument document, ApplicationConfig config) throws IOException {
        if (!config.logoEnabled()) {
            return Optional.empty();
        }

        BufferedImage source;
        try (InputStream input = BrandLogoLoader.class.getResourceAsStream(config.logoResource())) {
            if (input == null) {
                throw new IOException("Logo nao encontrada no JAR: " + config.logoResource());
            }
            source = ImageIO.read(input);
        }
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IOException("Logo invalida: " + config.logoResource());
        }

        validateThermalAsset(source, config.logoResource());
        BufferedImage printable = compositeOnWhite(source);
        PDImageXObject image = LosslessFactory.createFromImage(document, printable);
        image.setInterpolate(false);
        return Optional.of(new BrandLogo(image, ImageFingerprint.sha256(image)));
    }

    private void validateThermalAsset(BufferedImage source, String resource) throws IOException {
        boolean hasOpaquePixel = false;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha != 0 && alpha != 255) {
                    throw new IOException("Logo deve conter transparencia binaria: " + resource);
                }
                if (alpha == 255) {
                    hasOpaquePixel = true;
                    int red = (argb >>> 16) & 0xff;
                    int green = (argb >>> 8) & 0xff;
                    int blue = argb & 0xff;
                    if (red != 0 || green != 0 || blue != 0) {
                        throw new IOException("Logo deve conter somente preto e transparencia: " + resource);
                    }
                }
            }
        }
        if (!hasOpaquePixel) {
            throw new IOException("Logo nao contem pixels visiveis: " + resource);
        }
    }

    private BufferedImage compositeOnWhite(BufferedImage source) {
        BufferedImage result = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public record BrandLogo(PDImageXObject image, String fingerprint) {
    }
}
