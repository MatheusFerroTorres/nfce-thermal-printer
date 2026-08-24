package br.com.alkhemylab.nfceprinter.pdf;

import br.com.alkhemylab.nfceprinter.model.Bounds;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class PdfContentExtractor {
    private static final float BASELINE_TOLERANCE_POINTS = 1.75f;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public PageContent extract(PDDocument document, int pageIndex) throws IOException {
        PDPage page = document.getPage(pageIndex);
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        GlyphCollector glyphCollector = new GlyphCollector();
        List<Glyph> glyphs = glyphCollector.extract(document, pageIndex);
        List<TextRow> rows = groupRows(glyphs, pageWidth);

        ImageCollector imageCollector = new ImageCollector(page);
        imageCollector.processPage(page);
        List<ImageElement> images = imageCollector.images();

        Optional<Bounds> structuralBounds = calculateStructuralBounds(rows, images);
        String normalizedText = normalizeWhitespace(rows.stream()
                .map(TextRow::text)
                .reduce("", (left, right) -> left + " " + right));

        return new PageContent(
                page,
                pageWidth,
                pageHeight,
                List.copyOf(rows),
                List.copyOf(images),
                structuralBounds,
                normalizedText);
    }

    private List<TextRow> groupRows(List<Glyph> glyphs, float pageWidth) {
        List<Glyph> ordered = glyphs.stream()
                .sorted(Comparator.comparing(Glyph::baseline).thenComparing(Glyph::x))
                .toList();

        List<List<Glyph>> groups = new ArrayList<>();
        for (Glyph glyph : ordered) {
            if (groups.isEmpty()) {
                groups.add(new ArrayList<>(List.of(glyph)));
                continue;
            }

            List<Glyph> current = groups.get(groups.size() - 1);
            float averageBaseline = (float) current.stream().mapToDouble(Glyph::baseline).average().orElse(glyph.baseline());
            if (Math.abs(glyph.baseline() - averageBaseline) <= BASELINE_TOLERANCE_POINTS) {
                current.add(glyph);
            } else {
                groups.add(new ArrayList<>(List.of(glyph)));
            }
        }

        List<TextRow> rows = new ArrayList<>();
        for (List<Glyph> group : groups) {
            group.sort(Comparator.comparing(Glyph::x));
            List<Glyph> visible = group.stream().filter(glyph -> !glyph.text().isBlank()).toList();
            if (visible.isEmpty()) {
                continue;
            }

            String text = normalizeRowText(group);
            float left = visible.stream().map(Glyph::x).min(Float::compare).orElseThrow();
            float right = visible.stream().map(glyph -> glyph.x() + glyph.width()).max(Float::compare).orElseThrow();
            float top = visible.stream().map(Glyph::top).min(Float::compare).orElseThrow();
            float bottom = visible.stream().map(glyph -> glyph.top() + glyph.height()).max(Float::compare).orElseThrow();
            float fontSize = visible.stream().map(Glyph::fontSize).max(Float::compare).orElseThrow();
            PDFont font = visible.get(0).font();
            float visualWidth = right - left;
            boolean centered = left > 40f && visualWidth < pageWidth * 0.65f;
            List<TextSegment> segments = detectSegments(visible);

            rows.add(new TextRow(
                    text,
                    new Bounds(left, top, right, bottom),
                    font,
                    fontSize,
                    centered,
                    segments));
        }

        rows.sort(Comparator.comparing(row -> row.bounds().top()));
        return rows;
    }

    private List<TextSegment> detectSegments(List<Glyph> glyphs) {
        List<Glyph> ordered = glyphs.stream()
                .sorted(Comparator.comparing(Glyph::x))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<Float> widths = ordered.stream()
                .map(Glyph::width)
                .filter(width -> width > 0.1f)
                .sorted()
                .toList();
        float typicalGlyphWidth = widths.isEmpty()
                ? 4f
                : widths.get(widths.size() / 2);
        float columnGapThreshold = Math.max(12f, typicalGlyphWidth * 3f);

        List<List<Glyph>> groups = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        float previousRight = Float.NaN;
        for (Glyph glyph : ordered) {
            if (!current.isEmpty() && glyph.x() - previousRight > columnGapThreshold) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(glyph);
            previousRight = Math.max(
                    Float.isNaN(previousRight) ? glyph.x() : previousRight,
                    glyph.x() + glyph.width());
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        List<TextSegment> segments = new ArrayList<>();
        for (List<Glyph> group : groups) {
            float left = group.stream().map(Glyph::x).min(Float::compare).orElseThrow();
            float right = group.stream().map(glyph -> glyph.x() + glyph.width()).max(Float::compare).orElseThrow();
            float top = group.stream().map(Glyph::top).min(Float::compare).orElseThrow();
            float bottom = group.stream().map(glyph -> glyph.top() + glyph.height()).max(Float::compare).orElseThrow();
            segments.add(new TextSegment(
                    normalizeRowText(group),
                    new Bounds(left, top, right, bottom)));
        }
        return List.copyOf(segments);
    }

    private String normalizeRowText(List<Glyph> glyphs) {
        List<Glyph> visibleGlyphs = glyphs.stream()
                .filter(glyph -> !glyph.text().isBlank())
                .sorted(Comparator.comparing(Glyph::x))
                .toList();
        StringBuilder text = new StringBuilder();
        float previousRight = Float.NaN;
        float previousGlyphWidth = 0f;

        for (Glyph glyph : visibleGlyphs) {
            String value = glyph.text().replace('\u00A0', ' ');
            if (!Float.isNaN(previousRight)
                    && glyph.x() - previousRight > Math.max(1.5f, previousGlyphWidth * 0.65f)
                    && !text.isEmpty()) {
                text.append(' ');
            }

            text.append(value);
            float glyphRight = glyph.x() + glyph.width();
            previousRight = Float.isNaN(previousRight)
                    ? glyphRight
                    : Math.max(previousRight, glyphRight);
            previousGlyphWidth = Math.max(0.1f, glyph.width());
        }

        return normalizeWhitespace(text.toString());
    }

    private Optional<Bounds> calculateStructuralBounds(List<TextRow> rows, List<ImageElement> images) {
        List<Bounds> allBounds = new ArrayList<>();
        rows.stream().map(TextRow::bounds).forEach(allBounds::add);
        images.stream().map(ImageElement::bounds).forEach(allBounds::add);

        if (allBounds.isEmpty()) {
            return Optional.empty();
        }

        Bounds result = allBounds.get(0);
        for (int index = 1; index < allBounds.size(); index++) {
            result = result.union(allBounds.get(index));
        }
        return Optional.of(result);
    }

    public static String normalizeWhitespace(String value) {
        return WHITESPACE.matcher(value == null ? "" : value).replaceAll(" ").trim();
    }

    public record Glyph(
            String text,
            float x,
            float top,
            float width,
            float height,
            float baseline,
            float fontSize,
            PDFont font) {
    }

    public record TextRow(
            String text,
            Bounds bounds,
            PDFont font,
            float fontSize,
            boolean centered,
            List<TextSegment> segments) {
    }

    public record TextSegment(
            String text,
            Bounds bounds) {
    }

    public record ImageElement(
            PDImage image,
            Bounds bounds,
            boolean qrCandidate) {
    }

    public record PageContent(
            PDPage sourcePage,
            float pageWidth,
            float pageHeight,
            List<TextRow> rows,
            List<ImageElement> images,
            Optional<Bounds> structuralBounds,
            String normalizedText) {
    }

    private static final class GlyphCollector extends PDFTextStripper {
        private final List<Glyph> glyphs = new ArrayList<>();

        private GlyphCollector() throws IOException {
            setSortByPosition(false);
        }

        private List<Glyph> extract(PDDocument document, int pageIndex) throws IOException {
            glyphs.clear();
            setStartPage(pageIndex + 1);
            setEndPage(pageIndex + 1);
            getText(document);
            return List.copyOf(glyphs);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String unicode = text.getUnicode();
            if (unicode != null && !unicode.isEmpty()) {
                float height = Math.max(0.1f, text.getHeightDir());
                glyphs.add(new Glyph(
                        unicode,
                        text.getXDirAdj(),
                        text.getYDirAdj() - height,
                        Math.max(0f, text.getWidthDirAdj()),
                        height,
                        text.getYDirAdj(),
                        text.getFontSizeInPt(),
                        text.getFont()));
            }
            super.processTextPosition(text);
        }
    }

    private static final class ImageCollector extends PDFGraphicsStreamEngine {
        private final float pageHeight;
        private final List<ImageElement> images = new ArrayList<>();
        private Point2D currentPoint;

        private ImageCollector(PDPage page) {
            super(page);
            this.pageHeight = page.getMediaBox().getHeight();
        }

        private List<ImageElement> images() {
            return List.copyOf(images);
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D p0 = matrix.transformPoint(0, 0);
            Point2D p1 = matrix.transformPoint(1, 0);
            Point2D p2 = matrix.transformPoint(0, 1);
            Point2D p3 = matrix.transformPoint(1, 1);

            double minX = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            double maxX = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            double minY = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            double maxY = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));

            Bounds bounds = new Bounds(
                    (float) minX,
                    pageHeight - (float) maxY,
                    (float) maxX,
                    pageHeight - (float) minY);
            float ratio = bounds.width() / Math.max(0.1f, bounds.height());
            boolean square = ratio >= 0.90f && ratio <= 1.10f;
            boolean squarePixels = image.getWidth() == image.getHeight();
            boolean qrCandidate = square && squarePixels && image.getBitsPerComponent() <= 8;
            images.add(new ImageElement(image, bounds, qrCandidate));
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            currentPoint = p3;
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
        }

        @Override
        public void endPath() {
            currentPoint = null;
        }

        @Override
        public void strokePath() {
            currentPoint = null;
        }

        @Override
        public void fillPath(int windingRule) {
            currentPoint = null;
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            currentPoint = null;
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }
    }
}
