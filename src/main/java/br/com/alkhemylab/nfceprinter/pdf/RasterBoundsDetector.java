package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.model.Bounds;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

public final class RasterBoundsDetector {
    public Optional<Bounds> detect(
            PDDocument document,
            int pageIndex,
            int dpi,
            int whiteThreshold,
            float pageWidth,
            float pageHeight) throws IOException {

        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);

        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = image.getRaster().getSample(x, y, 0);
                if (value < whiteThreshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return Optional.empty();
        }

        float xScale = pageWidth / image.getWidth();
        float yScale = pageHeight / image.getHeight();
        return Optional.of(new Bounds(
                minX * xScale,
                minY * yScale,
                (maxX + 1) * xScale,
                (maxY + 1) * yScale));
    }
}
