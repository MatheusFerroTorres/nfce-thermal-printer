package br.com.alkhemylab.nfceprinter.print;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.util.MeasurementUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.printing.Orientation;
import org.apache.pdfbox.printing.PDFPageable;

import javax.print.PrintService;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

public final class PdfWindowsPrinter {
    private static final Logger LOGGER = Logger.getLogger(PdfWindowsPrinter.class.getName());
    private static final int MAXIMUM_JOB_NAME_LENGTH = 96;

    private final PrinterLocator printerLocator;

    public PdfWindowsPrinter() {
        this(new PrinterLocator());
    }

    PdfWindowsPrinter(PrinterLocator printerLocator) {
        this.printerLocator = printerLocator;
    }

    public PrintResult print(Path pdf, ApplicationConfig config) throws IOException, PrinterException {
        return print(pdf, config, () -> {
        });
    }

    public PrintResult print(
            Path pdf,
            ApplicationConfig config,
            PrintSubmissionListener submissionListener) throws IOException, PrinterException {
        Objects.requireNonNull(submissionListener, "submissionListener");
        Path normalizedPdf = pdf.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPdf)) {
            throw new IOException("PDF termico nao encontrado para impressao: " + normalizedPdf);
        }

        try (PDDocument document = Loader.loadPDF(normalizedPdf.toFile())) {
            if (document.isEncrypted()) {
                throw new IOException("PDF termico criptografado nao pode ser impresso.");
            }
            if (document.getNumberOfPages() == 0) {
                throw new IOException("PDF termico nao possui paginas.");
            }

            PDPage firstPage = document.getPage(0);
            PDRectangle pageBox = firstPage.getCropBox();
            double widthMm = MeasurementUtils.pointsToMm(pageBox.getWidth());
            double heightMm = MeasurementUtils.pointsToMm(pageBox.getHeight());
            if (Math.floorMod(firstPage.getRotation(), 180) != 0) {
                double originalWidth = widthMm;
                widthMm = heightMm;
                heightMm = originalWidth;
            }

            PrintService service = printerLocator.locate(config.printerName());
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(service);
            job.setJobName(buildJobName(normalizedPdf));
            job.setCopies(config.printCopies());

            PDFPageable pageable = new PDFPageable(
                    document,
                    Orientation.PORTRAIT,
                    false,
                    config.printRasterDpi(),
                    true);
            pageable.setSubsamplingAllowed(false);
            job.setPageable(pageable);

            int pages = document.getNumberOfPages();
            double loggedWidthMm = widthMm;
            double loggedHeightMm = heightMm;
            LOGGER.info(() -> String.format(
                    Locale.ROOT,
                    "Enviando PDF ao spooler: arquivo=%s; impressora=%s; paginas=%d; pagina=%.2f x %.2f mm; raster.dpi=%d; copias=%d",
                    normalizedPdf,
                    service.getName(),
                    pages,
                    loggedWidthMm,
                    loggedHeightMm,
                    config.printRasterDpi(),
                    config.printCopies()));

            submissionListener.beforeSubmit();
            job.print();

            LOGGER.info(() -> "SPOOL_ACCEPTED: arquivo=" + normalizedPdf
                    + "; impressora=" + service.getName()
                    + "; trabalho=\"" + job.getJobName() + "\"");
            return new PrintResult(
                    normalizedPdf,
                    service.getName(),
                    job.getJobName(),
                    pages,
                    widthMm,
                    heightMm,
                    config.printRasterDpi(),
                    config.printCopies());
        }
    }

    @FunctionalInterface
    public interface PrintSubmissionListener {
        void beforeSubmit() throws IOException;
    }

    static String buildJobName(Path pdf) {
        String filename = pdf.getFileName().toString();
        String baseName = filename.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? filename.substring(0, filename.length() - 4)
                : filename;
        String sanitized = baseName.replaceAll("[\\p{Cntrl}]", " ").trim();
        String jobName = "NFC-e - " + sanitized;
        return jobName.length() <= MAXIMUM_JOB_NAME_LENGTH
                ? jobName
                : jobName.substring(0, MAXIMUM_JOB_NAME_LENGTH);
    }

    public record PrintResult(
            Path pdf,
            String printerName,
            String jobName,
            int pages,
            double widthMm,
            double heightMm,
            int rasterDpi,
            int copies) {
        public String dimensionsSummary() {
            return String.format(Locale.ROOT, "%.2f x %.2f mm", widthMm, heightMm);
        }
    }
}
