package br.com.alkhemylab.nfceprinter.config;

import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class ApplicationConfig {
    private static final Path EXTERNAL_CONFIG = Path.of("config", "application.properties");

    private final Properties properties;

    private ApplicationConfig(Properties properties) {
        this.properties = properties;
        validate();
    }

    public static ApplicationConfig load() throws IOException {
        Properties properties = new Properties();

        try (InputStream input = ApplicationConfig.class.getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new IOException("Configuracao padrao application.properties nao encontrada no JAR.");
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        if (Files.isRegularFile(EXTERNAL_CONFIG)) {
            try (Reader reader = Files.newBufferedReader(EXTERNAL_CONFIG, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        return new ApplicationConfig(properties);
    }

    public Path spoolDirectory() {
        return Path.of(required("spool.directory"));
    }

    public String printerName() {
        return required("printer.name");
    }

    public double paperWidthMm() {
        return positiveDouble("paper.width.mm");
    }

    public double printableWidthMm() {
        return positiveDouble("printable.width.mm");
    }

    public double marginMm() {
        return nonNegativeDouble("margin.mm");
    }

    public int dpi() {
        return positiveInt("dpi");
    }

    public int analysisDpi() {
        return positiveInt("analysis.dpi");
    }

    public int rasterWhiteThreshold() {
        return integer("raster.white.threshold");
    }

    public double boundsToleranceMm() {
        return nonNegativeDouble("bounds.tolerance.mm");
    }

    public float minimumFontSizePt() {
        return (float) positiveDouble("font.minimum.size.pt");
    }

    public float lineSpacingFactor() {
        return (float) positiveDouble("line.spacing.factor");
    }

    public double rowGapMm() {
        return nonNegativeDouble("row.gap.mm");
    }

    public double sectionGapMm() {
        return nonNegativeDouble("section.gap.mm");
    }

    public double qrSizeMm() {
        return positiveDouble("qr.size.mm");
    }

    public double qrQuietZoneMm() {
        return nonNegativeDouble("qr.quiet.zone.mm");
    }

    public boolean enhancedVisualStyle() {
        return switch (required("layout.style").toLowerCase(Locale.ROOT)) {
            case "enhanced" -> true;
            case "classic" -> false;
            default -> throw new IllegalArgumentException(
                    "layout.style deve ser enhanced ou classic.");
        };
    }

    public boolean logoEnabled() {
        return booleanValue("logo.enabled");
    }

    public String logoResource() {
        return required("logo.resource");
    }

    public double logoWidthMm() {
        return positiveDouble("logo.width.mm");
    }

    public double logoSpacingMm() {
        return nonNegativeDouble("logo.spacing.mm");
    }

    public String outputSuffix() {
        return required("output.suffix");
    }

    public Path logDirectory() {
        return Path.of(required("log.directory"));
    }

    public boolean autoPrint() {
        return booleanValue("auto.print");
    }

    public int printRasterDpi() {
        return nonNegativeInt("print.raster.dpi");
    }

    public int printCopies() {
        return positiveInt("print.copies");
    }

    public String watchFileGlob() {
        return required("watch.file.glob");
    }

    public boolean watchInitialScan() {
        return booleanValue("watch.initial.scan");
    }

    public boolean watchAllowCompletedReprint() {
        return booleanValue("watch.allow.completed.reprint");
    }

    public long watchStabilityIntervalMillis() {
        return positiveLong("watch.stability.interval.ms");
    }

    public int watchStabilityRequiredChecks() {
        return positiveInt("watch.stability.required.checks");
    }

    public long watchStabilityTimeoutSeconds() {
        return positiveLong("watch.stability.timeout.seconds");
    }

    public Path watchOriginalDirectory() {
        return relativePath("watch.original.directory");
    }

    public Path watchPreviewDirectory() {
        return relativePath("watch.preview.directory");
    }

    public Path watchProcessedDirectory() {
        return relativePath("watch.processed.directory");
    }

    public Path watchPrintedDirectory() {
        return relativePath("watch.printed.directory");
    }

    public Path watchErrorDirectory() {
        return relativePath("watch.error.directory");
    }

    public Path watchDuplicateDirectory() {
        return relativePath("watch.duplicate.directory");
    }

    public Path watchJournalFile() {
        return relativePath("watch.journal.file");
    }

    public Path watchInitializedFile() {
        return relativePath("watch.initialized.file");
    }

    public Path watchLockFile() {
        return relativePath("watch.lock.file");
    }

    public float contentWidthPoints() {
        double usableMillimeters = printableWidthMm() - (2 * marginMm());
        return br.com.alkhemylab.nfceprinter.util.MeasurementUtils.mmToPoints(usableMillimeters);
    }

    private void validate() {
        if (printableWidthMm() > paperWidthMm()) {
            throw new IllegalArgumentException("printable.width.mm nao pode ser maior que paper.width.mm.");
        }
        if (2 * marginMm() >= printableWidthMm()) {
            throw new IllegalArgumentException("margin.mm deixa a largura util igual ou menor que zero.");
        }
        if (rasterWhiteThreshold() < 0 || rasterWhiteThreshold() > 255) {
            throw new IllegalArgumentException("raster.white.threshold deve estar entre 0 e 255.");
        }
        if (qrSizeMm() > printableWidthMm()) {
            throw new IllegalArgumentException("qr.size.mm nao pode ser maior que printable.width.mm.");
        }
        if (logoEnabled() && logoWidthMm() > MeasurementUtils.pointsToMm(contentWidthPoints())) {
            throw new IllegalArgumentException("logo.width.mm nao pode ser maior que a largura util.");
        }
        if (!logoResource().startsWith("/")) {
            throw new IllegalArgumentException("logo.resource deve iniciar com /.");
        }
        enhancedVisualStyle();
        autoPrint();
        printRasterDpi();
        printCopies();
        if (watchFileGlob().contains("/") || watchFileGlob().contains("\\")
                || !watchFileGlob().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException(
                    "watch.file.glob deve ser um padrao de nome PDF, sem caminho de pasta.");
        }
        watchInitialScan();
        watchAllowCompletedReprint();
        long stabilityWindowMillis;
        long timeoutMillis;
        try {
            stabilityWindowMillis = Math.multiplyExact(
                    watchStabilityIntervalMillis(),
                    watchStabilityRequiredChecks());
            timeoutMillis = Math.multiplyExact(watchStabilityTimeoutSeconds(), 1_000L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Configuracao de estabilidade excede o limite numerico.", exception);
        }
        if (timeoutMillis <= stabilityWindowMillis) {
            throw new IllegalArgumentException(
                    "watch.stability.timeout.seconds deve ser maior que a janela de verificacoes estaveis.");
        }

        List<Path> archiveDirectories = List.of(
                watchOriginalDirectory(),
                watchPreviewDirectory(),
                watchProcessedDirectory(),
                watchPrintedDirectory(),
                watchErrorDirectory(),
                watchDuplicateDirectory());
        if (new HashSet<>(archiveDirectories).size() != archiveDirectories.size()) {
            throw new IllegalArgumentException("As pastas watch.*.directory devem ser diferentes entre si.");
        }

        List<Path> stateFiles = List.of(watchJournalFile(), watchInitializedFile(), watchLockFile());
        if (new HashSet<>(stateFiles).size() != stateFiles.size()) {
            throw new IllegalArgumentException("Os arquivos de estado do monitor devem ter caminhos diferentes.");
        }
    }

    private String required(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Propriedade obrigatoria ausente: " + key);
        }
        return value.trim();
    }

    private double positiveDouble(String key) {
        double value = Double.parseDouble(required(key));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " deve ser maior que zero.");
        }
        return value;
    }

    private double nonNegativeDouble(String key) {
        double value = Double.parseDouble(required(key));
        if (value < 0) {
            throw new IllegalArgumentException(key + " nao pode ser negativo.");
        }
        return value;
    }

    private int positiveInt(String key) {
        int value = integer(key);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " deve ser maior que zero.");
        }
        return value;
    }

    private int nonNegativeInt(String key) {
        int value = integer(key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " nao pode ser negativo.");
        }
        return value;
    }

    private long positiveLong(String key) {
        long value = Long.parseLong(required(key));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " deve ser maior que zero.");
        }
        return value;
    }

    private Path relativePath(String key) {
        Path value = Path.of(required(key)).normalize();
        if (value.isAbsolute() || value.getNameCount() == 0 || value.startsWith("..")) {
            throw new IllegalArgumentException(key + " deve ser um caminho relativo dentro de spool.directory.");
        }
        return value;
    }

    private boolean booleanValue(String key) {
        return switch (required(key).toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " deve ser true ou false.");
        };
    }

    private int integer(String key) {
        return Integer.parseInt(required(key));
    }
}
