package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.pdf.BrandLogoLoader.BrandLogo;
import br.com.alkhemylab.nfceprinter.pdf.PdfContentExtractor.PageContent;
import br.com.alkhemylab.nfceprinter.pdf.ThermalLayoutEngine.BoxPlacement;
import br.com.alkhemylab.nfceprinter.pdf.ThermalLayoutEngine.ImagePlacement;
import br.com.alkhemylab.nfceprinter.pdf.ThermalLayoutEngine.RulePlacement;
import br.com.alkhemylab.nfceprinter.pdf.ThermalLayoutEngine.TextPlacement;
import br.com.alkhemylab.nfceprinter.pdf.ThermalLayoutEngine.ThermalLayout;
import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class PdfThermalConverter {
    private static final Logger LOGGER = Logger.getLogger(PdfThermalConverter.class.getName());

    private final PdfInspector inspector = new PdfInspector();
    private final BrandLogoLoader logoLoader = new BrandLogoLoader();
    private final ThermalLayoutEngine layoutEngine = new ThermalLayoutEngine();
    private final PdfValidator validator = new PdfValidator();

    public ConversionResult convert(Path input, Path output, ApplicationConfig config) throws IOException {
        Path normalizedInput = input.toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        if (normalizedInput.equals(normalizedOutput)) {
            throw new IOException("O arquivo de saida nao pode sobrescrever o PDF original.");
        }
        if (!Files.isRegularFile(normalizedInput)) {
            throw new IOException("PDF de entrada nao encontrado: " + normalizedInput);
        }

        Path outputDirectory = normalizedOutput.getParent();
        if (outputDirectory != null) {
            Files.createDirectories(outputDirectory);
        }

        Path temporaryOutput = Files.createTempFile(
                outputDirectory,
                normalizedOutput.getFileName().toString() + ".",
                ".tmp");

        String sourceText;
        String logoFingerprint = null;
        List<String> sourceImageHashes = new ArrayList<>();
        PdfInspector.InspectionResult inspection;
        ThermalLayout layout;
        PdfValidator.ValidationResult validation;

        try {
            try (PDDocument document = Loader.loadPDF(normalizedInput.toFile())) {
                if (document.isEncrypted()) {
                    throw new IOException("PDF criptografado nao e suportado na Fase 1.");
                }

                inspection = inspector.inspect(document, config);
                PageContent content = inspection.content();
                if (content.rows().isEmpty()) {
                    throw new IOException("Nenhum texto foi detectado no PDF.");
                }
                long qrCandidates = content.images().stream()
                        .filter(PdfContentExtractor.ImageElement::qrCandidate)
                        .count();
                if (qrCandidates != 1) {
                    throw new IOException("O MVP exige exatamente um QR Code quadrado e binario. Candidatos encontrados: "
                            + qrCandidates);
                }
                sourceText = content.normalizedText();
                for (PdfContentExtractor.ImageElement image : content.images()) {
                    sourceImageHashes.add(ImageFingerprint.sha256(image.image()));
                }

                BrandLogo logo = logoLoader.load(document, config).orElse(null);
                if (logo != null) {
                    logoFingerprint = logo.fingerprint();
                }
                layout = layoutEngine.layout(
                        content,
                        config,
                        logo == null ? null : logo.image());
                PDPage thermalPage = new PDPage(new PDRectangle(layout.pageWidth(), layout.pageHeight()));
                document.addPage(thermalPage);
                writeLayout(document, thermalPage, layout);
                document.removePage(0);
                document.save(temporaryOutput.toFile());
            }

            validation = validator.validate(
                    temporaryOutput,
                    sourceText,
                    List.copyOf(sourceImageHashes),
                    logoFingerprint,
                    config);
            replaceAtomicallyWhenPossible(temporaryOutput, normalizedOutput);
        } finally {
            Files.deleteIfExists(temporaryOutput);
        }

        LOGGER.info(() -> String.format(
                "PDF termico gerado: %s; %.2f x %.2f mm; textos preservados=%s; imagens preservadas=%s; logo conforme configuracao=%s",
                normalizedOutput,
                validation.widthMm(),
                validation.heightMm(),
                validation.textPreserved(),
                validation.imagesPreserved(),
                validation.logoPreserved()));

        return new ConversionResult(
                normalizedInput,
                normalizedOutput,
                inspection,
                layout,
                validation);
    }

    private void replaceAtomicallyWhenPossible(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeLayout(PDDocument document, PDPage page, ThermalLayout layout) throws IOException {
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            for (RulePlacement placement : layout.rulePlacements()) {
                contentStream.saveGraphicsState();
                contentStream.setLineWidth(placement.thickness());
                if (placement.dashed()) {
                    contentStream.setLineDashPattern(new float[]{2.2f, 1.8f}, 0f);
                }
                float y = layout.pageHeight() - placement.top();
                contentStream.moveTo(placement.x(), y);
                contentStream.lineTo(placement.x() + placement.width(), y);
                contentStream.stroke();
                contentStream.restoreGraphicsState();
            }

            for (BoxPlacement placement : layout.boxPlacements()) {
                contentStream.saveGraphicsState();
                contentStream.setLineWidth(placement.thickness());
                float y = layout.pageHeight() - placement.top() - placement.height();
                contentStream.addRect(
                        placement.x(),
                        y,
                        placement.width(),
                        placement.height());
                contentStream.stroke();
                contentStream.restoreGraphicsState();
            }

            for (TextPlacement placement : layout.textPlacements()) {
                float baseline = layout.pageHeight() - placement.top() - placement.fontSize();
                contentStream.beginText();
                contentStream.setFont(placement.font(), placement.fontSize());
                contentStream.newLineAtOffset(placement.x(), baseline);
                FontEncodingSupport.showText(contentStream, placement.font(), placement.text());
                contentStream.endText();
            }

            for (ImagePlacement placement : layout.imagePlacements()) {
                PDImageXObject image;
                if (placement.image() instanceof PDImageXObject imageXObject) {
                    image = imageXObject;
                } else {
                    image = LosslessFactory.createFromImage(document, placement.image().getImage());
                }
                image.setInterpolate(false);

                float y = layout.pageHeight() - placement.top() - placement.height();
                contentStream.drawImage(
                        image,
                        placement.x(),
                        y,
                        placement.width(),
                        placement.height());
            }
        }
    }

    public record ConversionResult(
            Path input,
            Path output,
            PdfInspector.InspectionResult inspection,
            ThermalLayout layout,
            PdfValidator.ValidationResult validation) {
        public String dimensionsSummary() {
            return String.format(
                    "%.2f x %.2f mm",
                    MeasurementUtils.pointsToMm(layout.pageWidth()),
                    MeasurementUtils.pointsToMm(layout.pageHeight()));
        }
    }
}
