package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.model.Bounds;
import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.Optional;
import java.util.logging.Logger;

public final class PdfInspector {
    private static final Logger LOGGER = Logger.getLogger(PdfInspector.class.getName());

    private final PdfContentExtractor contentExtractor = new PdfContentExtractor();
    private final RasterBoundsDetector rasterBoundsDetector = new RasterBoundsDetector();

    public InspectionResult inspect(PDDocument document, ApplicationConfig config) throws IOException {
        if (document.getNumberOfPages() != 1) {
            throw new IOException("O MVP da Fase 1 aceita exatamente uma pagina. Paginas encontradas: "
                    + document.getNumberOfPages());
        }

        PdfContentExtractor.PageContent content = contentExtractor.extract(document, 0);
        Optional<Bounds> rasterBounds = rasterBoundsDetector.detect(
                document,
                0,
                config.analysisDpi(),
                config.rasterWhiteThreshold(),
                content.pageWidth(),
                content.pageHeight());

        LOGGER.info(() -> String.format(
                "Pagina original: %.2f x %.2f mm; rotacao: %d graus",
                MeasurementUtils.pointsToMm(content.pageWidth()),
                MeasurementUtils.pointsToMm(content.pageHeight()),
                content.sourcePage().getRotation()));
        LOGGER.info(() -> "Linhas de texto detectadas: " + content.rows().size()
                + "; imagens detectadas: " + content.images().size());

        content.structuralBounds().ifPresent(bounds -> LOGGER.info(() -> formatBounds("Area estrutural", bounds)));
        rasterBounds.ifPresent(bounds -> LOGGER.info(() -> formatBounds("Area renderizada", bounds)));

        boolean boundsAgree = boundsAgree(content.structuralBounds(), rasterBounds, config.boundsToleranceMm());
        if (!boundsAgree) {
            LOGGER.warning("Os limites estrutural e renderizado diferem acima da tolerancia configurada.");
        }

        return new InspectionResult(content, rasterBounds, boundsAgree);
    }

    private boolean boundsAgree(Optional<Bounds> structural, Optional<Bounds> raster, double toleranceMm) {
        if (structural.isEmpty() || raster.isEmpty()) {
            return structural.isEmpty() && raster.isEmpty();
        }
        float tolerance = MeasurementUtils.mmToPoints(toleranceMm);
        Bounds left = structural.orElseThrow();
        Bounds right = raster.orElseThrow();
        return Math.abs(left.left() - right.left()) <= tolerance
                && Math.abs(left.top() - right.top()) <= tolerance
                && Math.abs(left.right() - right.right()) <= tolerance
                && Math.abs(left.bottom() - right.bottom()) <= tolerance;
    }

    private String formatBounds(String label, Bounds bounds) {
        return String.format(
                "%s: x=%.2f..%.2f pt, topo=%.2f..%.2f pt, %.2f x %.2f mm",
                label,
                bounds.left(),
                bounds.right(),
                bounds.top(),
                bounds.bottom(),
                MeasurementUtils.pointsToMm(bounds.width()),
                MeasurementUtils.pointsToMm(bounds.height()));
    }

    public record InspectionResult(
            PdfContentExtractor.PageContent content,
            Optional<Bounds> rasterBounds,
            boolean boundsAgree) {
    }
}
