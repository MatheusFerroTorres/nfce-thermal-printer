package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.pdf.PdfContentExtractor.PageContent;
import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public final class PdfValidator {
    private final PdfContentExtractor extractor = new PdfContentExtractor();

    public ValidationResult validate(
            Path output,
            String sourceText,
            List<String> sourceImageHashes,
            String expectedLogoFingerprint,
            ApplicationConfig config) throws IOException {

        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            if (document.getNumberOfPages() != 1) {
                throw new IOException("PDF termico invalido: quantidade de paginas diferente de 1.");
            }

            PageContent result = extractor.extract(document, 0);
            String sourceCharacters = withoutWhitespace(sourceText);
            String resultCharacters = withoutWhitespace(result.normalizedText());
            boolean textPreserved = sourceCharacters.equals(resultCharacters);

            List<OutputImage> outputImages = new ArrayList<>();
            for (PdfContentExtractor.ImageElement image : result.images()) {
                outputImages.add(new OutputImage(
                        image,
                        ImageFingerprint.sha256(image.image())));
            }
            List<String> expectedImageHashes = new ArrayList<>(sourceImageHashes);
            if (expectedLogoFingerprint != null) {
                expectedImageHashes.add(expectedLogoFingerprint);
            }
            List<String> resultImageHashes = outputImages.stream()
                    .map(OutputImage::fingerprint)
                    .toList();
            boolean imagesPreserved = sorted(resultImageHashes)
                    .equals(sorted(expectedImageHashes));

            boolean logoPreserved;
            if (expectedLogoFingerprint == null) {
                logoPreserved = true;
            } else {
                List<OutputImage> logoMatches = outputImages.stream()
                        .filter(image -> image.fingerprint().equals(expectedLogoFingerprint))
                        .toList();
                logoPreserved = logoMatches.size() == 1
                        && !logoMatches.get(0).element().qrCandidate()
                        && Math.abs(
                                MeasurementUtils.pointsToMm(
                                        logoMatches.get(0).element().bounds().width())
                                        - config.logoWidthMm()) <= 0.05;
            }

            List<PdfContentExtractor.ImageElement> qrCandidates = result.images().stream()
                    .filter(PdfContentExtractor.ImageElement::qrCandidate)
                    .toList();
            boolean qrSizeValid = qrCandidates.size() == 1
                    && Math.abs(
                            MeasurementUtils.pointsToMm(qrCandidates.get(0).bounds().width())
                                    - config.qrSizeMm()) <= 0.05;

            double widthMm = MeasurementUtils.pointsToMm(result.pageWidth());
            double heightMm = MeasurementUtils.pointsToMm(result.pageHeight());
            boolean pageWidthValid = Math.abs(widthMm - config.paperWidthMm()) <= 0.05;

            if (!textPreserved) {
                throw new IOException("Validacao falhou: a sequencia de caracteres do texto foi alterada.");
            }
            if (!imagesPreserved) {
                throw new IOException("Validacao falhou: o conjunto de imagens esperado nao foi preservado.");
            }
            if (!logoPreserved) {
                throw new IOException("Validacao falhou: logo ausente, alterada ou com largura incorreta.");
            }
            if (!qrSizeValid) {
                throw new IOException("Validacao falhou: tamanho fisico do QR Code diferente de qr.size.mm.");
            }
            if (!pageWidthValid) {
                throw new IOException("Validacao falhou: largura final diferente de paper.width.mm.");
            }

            return new ValidationResult(
                    true,
                    true,
                    logoPreserved,
                    widthMm,
                    heightMm,
                    result.rows().size(),
                    result.images().size());
        }
    }

    private String withoutWhitespace(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private record OutputImage(
            PdfContentExtractor.ImageElement element,
            String fingerprint) {
    }

    public record ValidationResult(
            boolean textPreserved,
            boolean imagesPreserved,
            boolean logoPreserved,
            double widthMm,
            double heightMm,
            int textRows,
            int images) {
    }
}
