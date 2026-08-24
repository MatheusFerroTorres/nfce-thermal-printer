package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.pdf.PdfContentExtractor.ImageElement;
import br.com.alkhemylab.nfceprinter.pdf.PdfContentExtractor.PageContent;
import br.com.alkhemylab.nfceprinter.pdf.PdfContentExtractor.TextRow;
import br.com.alkhemylab.nfceprinter.pdf.PdfContentExtractor.TextSegment;
import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ThermalLayoutEngine {
    private static final float STRUCTURAL_GAP_POINTS = 6f;
    private static final float RULE_THICKNESS_POINTS = 0.55f;
    private static final float BOX_THICKNESS_POINTS = 0.70f;

    public ThermalLayout layout(
            PageContent source,
            ApplicationConfig config,
            PDImage logoImage) throws IOException {
        float pageWidth = MeasurementUtils.mmToPoints(config.paperWidthMm());
        float printableWidth = MeasurementUtils.mmToPoints(config.printableWidthMm());
        float margin = MeasurementUtils.mmToPoints(config.marginMm());
        float contentWidth = config.contentWidthPoints();
        float contentLeft = (pageWidth - printableWidth) / 2f + margin;
        float rowGap = MeasurementUtils.mmToPoints(config.rowGapMm());
        float sectionGap = MeasurementUtils.mmToPoints(config.sectionGapMm());
        boolean enhanced = config.enhancedVisualStyle();
        int headerEndIndex = findHeaderEndIndex(source.rows());

        List<SourceBlock> sourceBlocks = new ArrayList<>();
        for (int index = 0; index < source.rows().size(); index++) {
            sourceBlocks.add(SourceBlock.forText(source.rows().get(index), index));
        }
        source.images().forEach(image -> sourceBlocks.add(SourceBlock.forImage(image)));
        sourceBlocks.sort(Comparator.comparing(SourceBlock::top));

        List<TextPlacement> textPlacements = new ArrayList<>();
        List<ImagePlacement> imagePlacements = new ArrayList<>();
        List<RulePlacement> rulePlacements = new ArrayList<>();
        List<BoxPlacement> boxPlacements = new ArrayList<>();

        float cursor = margin;
        if (logoImage != null) {
            float targetWidth = Math.min(
                    MeasurementUtils.mmToPoints(config.logoWidthMm()),
                    contentWidth);
            float targetHeight = targetWidth
                    * logoImage.getHeight()
                    / Math.max(1f, logoImage.getWidth());
            imagePlacements.add(new ImagePlacement(
                    logoImage,
                    (pageWidth - targetWidth) / 2f,
                    cursor,
                    targetWidth,
                    targetHeight,
                    false));
            cursor += targetHeight + MeasurementUtils.mmToPoints(config.logoSpacingMm());
        }

        float previousBottom = Float.NaN;
        for (SourceBlock sourceBlock : sourceBlocks) {
            if (!Float.isNaN(previousBottom)) {
                float sourceGap = Math.max(0f, sourceBlock.top() - previousBottom);
                if (sourceGap > STRUCTURAL_GAP_POINTS) {
                    if (enhanced) {
                        cursor += sectionGap * 0.45f;
                        rulePlacements.add(new RulePlacement(
                                contentLeft,
                                cursor,
                                contentWidth,
                                RULE_THICKNESS_POINTS,
                                true));
                        cursor += sectionGap * 0.55f;
                    } else {
                        cursor += sectionGap;
                    }
                } else {
                    cursor += rowGap;
                }
            }

            if (sourceBlock.textRow() != null) {
                TextRow row = sourceBlock.textRow();
                float preferredSize = preferredFontSize(
                        row,
                        sourceBlock.rowIndex(),
                        headerEndIndex,
                        enhanced);
                boolean totalEmphasis = enhanced && isTotalToEmphasize(row.text());

                if (totalEmphasis) {
                    float boxTop = cursor;
                    float padding = 2.4f;
                    float horizontalInset = 2.5f;
                    cursor += padding;
                    cursor = addTwoColumnOrWrappedRow(
                            row,
                            Math.max(9f, preferredSize),
                            contentLeft + horizontalInset,
                            contentWidth - (2 * horizontalInset),
                            cursor,
                            config,
                            textPlacements,
                            false);
                    cursor += padding;
                    boxPlacements.add(new BoxPlacement(
                            contentLeft,
                            boxTop,
                            contentWidth,
                            cursor - boxTop,
                            BOX_THICKNESS_POINTS));
                } else if (enhanced && row.segments().size() >= 4) {
                    cursor = addCompactTableRow(
                            row,
                            preferredSize,
                            contentLeft,
                            contentWidth,
                            cursor,
                            config,
                            textPlacements,
                            rowGap);
                } else if (enhanced && row.segments().size() == 2) {
                    cursor = addTwoColumnOrWrappedRow(
                            row,
                            preferredSize,
                            contentLeft,
                            contentWidth,
                            cursor,
                            config,
                            textPlacements,
                            false);
                } else {
                    boolean centered = row.centered()
                            || (enhanced && sourceBlock.rowIndex() <= headerEndIndex);
                    cursor = addWrappedRow(
                            row.text(),
                            row.font(),
                            preferredSize,
                            contentLeft,
                            contentWidth,
                            cursor,
                            config,
                            textPlacements,
                            centered);
                }
            } else {
                ImageElement element = sourceBlock.imageElement();
                boolean qr = element.qrCandidate();
                float quietZone = qr ? MeasurementUtils.mmToPoints(config.qrQuietZoneMm()) : 0f;
                float targetWidth;
                float targetHeight;

                if (qr) {
                    targetWidth = Math.min(MeasurementUtils.mmToPoints(config.qrSizeMm()), contentWidth);
                    targetHeight = targetWidth;
                } else {
                    float scale = Math.min(1f, contentWidth / Math.max(0.1f, element.bounds().width()));
                    targetWidth = element.bounds().width() * scale;
                    targetHeight = element.bounds().height() * scale;
                }

                cursor += quietZone;
                float x = (pageWidth - targetWidth) / 2f;
                imagePlacements.add(new ImagePlacement(
                        element.image(),
                        x,
                        cursor,
                        targetWidth,
                        targetHeight,
                        qr));
                cursor += targetHeight + quietZone;
            }

            previousBottom = sourceBlock.bottom();
        }

        float pageHeight = cursor + margin;
        return new ThermalLayout(
                pageWidth,
                pageHeight,
                contentLeft,
                contentWidth,
                List.copyOf(textPlacements),
                List.copyOf(imagePlacements),
                List.copyOf(rulePlacements),
                List.copyOf(boxPlacements));
    }

    private float addCompactTableRow(
            TextRow row,
            float preferredSize,
            float contentLeft,
            float contentWidth,
            float cursor,
            ApplicationConfig config,
            List<TextPlacement> placements,
            float rowGap) throws IOException {

        List<TextSegment> segments = row.segments();
        String primaryText = segments.get(0).text() + " " + segments.get(1).text();
        cursor = addWrappedRow(
                primaryText,
                row.font(),
                preferredSize,
                contentLeft,
                contentWidth,
                cursor,
                config,
                placements,
                false);

        cursor += rowGap * 0.35f;
        List<TextSegment> detailSegments = segments.subList(2, segments.size());
        float cellWidth = contentWidth / detailSegments.size();
        float baseFontSize = Math.max(config.minimumFontSizePt(), preferredSize - 0.5f);
        float lineHeight = baseFontSize * config.lineSpacingFactor();

        for (int index = 0; index < detailSegments.size(); index++) {
            String text = detailSegments.get(index).text();
            float availableWidth = Math.max(1f, cellWidth - 2f);
            float fontSize = fitFontSize(
                    row.font(),
                    text,
                    baseFontSize,
                    config.minimumFontSizePt(),
                    availableWidth);
            float textWidth = stringWidth(row.font(), text, fontSize);
            float x = contentLeft + (index * cellWidth) + Math.max(0f, (cellWidth - textWidth) / 2f);
            float top = cursor + Math.max(0f, baseFontSize - fontSize);
            placements.add(new TextPlacement(
                    text,
                    row.font(),
                    fontSize,
                    x,
                    top,
                    lineHeight));
        }
        return cursor + lineHeight;
    }

    private float addTwoColumnOrWrappedRow(
            TextRow row,
            float preferredSize,
            float contentLeft,
            float contentWidth,
            float cursor,
            ApplicationConfig config,
            List<TextPlacement> placements,
            boolean centeredFallback) throws IOException {

        if (row.segments().size() != 2) {
            return addWrappedRow(
                    row.text(),
                    row.font(),
                    preferredSize,
                    contentLeft,
                    contentWidth,
                    cursor,
                    config,
                    placements,
                    centeredFallback);
        }

        String leftText = row.segments().get(0).text();
        String rightText = row.segments().get(1).text();
        float minimumGap = 5f;
        float fontSize = Math.max(config.minimumFontSizePt(), preferredSize);
        float combinedWidth = stringWidth(row.font(), leftText, fontSize)
                + stringWidth(row.font(), rightText, fontSize)
                + minimumGap;
        if (combinedWidth > contentWidth) {
            fontSize = Math.max(
                    config.minimumFontSizePt(),
                    fontSize * contentWidth / combinedWidth);
        }

        float leftWidth = stringWidth(row.font(), leftText, fontSize);
        float rightWidth = stringWidth(row.font(), rightText, fontSize);
        if (leftWidth + rightWidth + minimumGap > contentWidth + 0.1f) {
            return addWrappedRow(
                    row.text(),
                    row.font(),
                    preferredSize,
                    contentLeft,
                    contentWidth,
                    cursor,
                    config,
                    placements,
                    centeredFallback);
        }

        float lineHeight = fontSize * config.lineSpacingFactor();
        placements.add(new TextPlacement(
                leftText,
                row.font(),
                fontSize,
                contentLeft,
                cursor,
                lineHeight));
        placements.add(new TextPlacement(
                rightText,
                row.font(),
                fontSize,
                contentLeft + contentWidth - rightWidth,
                cursor,
                lineHeight));
        return cursor + lineHeight;
    }

    private float addWrappedRow(
            String text,
            PDFont font,
            float preferredSize,
            float contentLeft,
            float contentWidth,
            float cursor,
            ApplicationConfig config,
            List<TextPlacement> placements,
            boolean centered) throws IOException {

        WrappedText wrapped = wrap(
                text,
                font,
                preferredSize,
                config.minimumFontSizePt(),
                contentWidth);
        float lineHeight = wrapped.fontSize() * config.lineSpacingFactor();

        for (String line : wrapped.lines()) {
            float lineWidth = stringWidth(font, line, wrapped.fontSize());
            float x = centered
                    ? contentLeft + Math.max(0f, (contentWidth - lineWidth) / 2f)
                    : contentLeft;
            placements.add(new TextPlacement(
                    line,
                    font,
                    wrapped.fontSize(),
                    x,
                    cursor,
                    lineHeight));
            cursor += lineHeight;
        }
        return cursor;
    }

    private int findHeaderEndIndex(List<TextRow> rows) {
        for (int index = 0; index < rows.size(); index++) {
            if (normalizedForMatch(rows.get(index).text()).contains("DOCUMENTO AUXILIAR")) {
                return index;
            }
        }
        return -1;
    }

    private float preferredFontSize(
            TextRow row,
            int rowIndex,
            int headerEndIndex,
            boolean enhanced) {
        if (!enhanced || rowIndex < 0 || rowIndex > headerEndIndex) {
            return row.fontSize();
        }
        if (rowIndex == 0 || normalizedForMatch(row.text()).contains("DOCUMENTO AUXILIAR")) {
            return Math.max(8.5f, row.fontSize());
        }
        return Math.max(8f, row.fontSize());
    }

    private boolean isTotalToEmphasize(String text) {
        return normalizedForMatch(text).startsWith("VALOR A PAGAR R$");
    }

    private String normalizedForMatch(String text) {
        return PdfContentExtractor.normalizeWhitespace(text).toUpperCase(Locale.ROOT);
    }

    private float fitFontSize(
            PDFont font,
            String text,
            float preferredSize,
            float minimumSize,
            float maximumWidth) throws IOException {
        float width = stringWidth(font, text, preferredSize);
        if (width <= maximumWidth) {
            return preferredSize;
        }
        return Math.max(minimumSize, preferredSize * maximumWidth / Math.max(0.1f, width));
    }

    private WrappedText wrap(
            String text,
            PDFont font,
            float preferredSize,
            float minimumSize,
            float maximumWidth) throws IOException {

        String normalized = PdfContentExtractor.normalizeWhitespace(text);
        if (normalized.isEmpty()) {
            return new WrappedText(preferredSize, List.of());
        }

        float fontSize = Math.max(minimumSize, preferredSize);
        String[] words = normalized.split(" ");
        float longestWordWidth = 0f;
        for (String word : words) {
            longestWordWidth = Math.max(longestWordWidth, stringWidth(font, word, fontSize));
        }

        if (longestWordWidth > maximumWidth) {
            fontSize = Math.max(minimumSize, fontSize * maximumWidth / longestWordWidth);
        }

        List<String> tokens = new ArrayList<>();
        for (String word : words) {
            if (stringWidth(font, word, fontSize) <= maximumWidth) {
                tokens.add(word);
            } else {
                tokens.addAll(splitLongToken(word, font, fontSize, maximumWidth));
            }
        }

        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : tokens) {
            String candidate = current.isEmpty() ? token : current + " " + token;
            if (!current.isEmpty() && stringWidth(font, candidate, fontSize) > maximumWidth) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(token);
            } else {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(token);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }

        return new WrappedText(fontSize, List.copyOf(lines));
    }

    private List<String> splitLongToken(
            String token,
            PDFont font,
            float fontSize,
            float maximumWidth) throws IOException {

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            String candidate = current + character;
            if (!current.isEmpty() && stringWidth(font, candidate, fontSize) > maximumWidth) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(character);
            offset += Character.charCount(codePoint);
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private float stringWidth(PDFont font, String text, float fontSize) throws IOException {
        return FontEncodingSupport.stringWidth(font, text, fontSize);
    }

    private record WrappedText(float fontSize, List<String> lines) {
    }

    private record SourceBlock(
            float top,
            float bottom,
            TextRow textRow,
            ImageElement imageElement,
            int rowIndex) {
        private static SourceBlock forText(TextRow row, int rowIndex) {
            return new SourceBlock(row.bounds().top(), row.bounds().bottom(), row, null, rowIndex);
        }

        private static SourceBlock forImage(ImageElement image) {
            return new SourceBlock(image.bounds().top(), image.bounds().bottom(), null, image, -1);
        }
    }

    public record TextPlacement(
            String text,
            PDFont font,
            float fontSize,
            float x,
            float top,
            float lineHeight) {
    }

    public record ImagePlacement(
            PDImage image,
            float x,
            float top,
            float width,
            float height,
            boolean qr) {
    }

    public record RulePlacement(
            float x,
            float top,
            float width,
            float thickness,
            boolean dashed) {
    }

    public record BoxPlacement(
            float x,
            float top,
            float width,
            float height,
            float thickness) {
    }

    public record ThermalLayout(
            float pageWidth,
            float pageHeight,
            float contentLeft,
            float contentWidth,
            List<TextPlacement> textPlacements,
            List<ImagePlacement> imagePlacements,
            List<RulePlacement> rulePlacements,
            List<BoxPlacement> boxPlacements) {
    }
}
