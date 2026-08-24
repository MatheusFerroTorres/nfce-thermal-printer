package br.com.alkhemylab.nfceprinter;

import br.com.alkhemylab.nfceprinter.config.ApplicationConfig;
import br.com.alkhemylab.nfceprinter.pdf.PdfThermalConverter;
import br.com.alkhemylab.nfceprinter.print.PdfWindowsPrinter;
import br.com.alkhemylab.nfceprinter.util.LogSupport;
import br.com.alkhemylab.nfceprinter.watch.SpoolWatcher;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage();
            return;
        }
        if (args.length != 1 || args[0].isBlank()) {
            printUsage();
            System.exit(2);
        }

        Path generatedOutput = null;
        try {
            ApplicationConfig config = ApplicationConfig.load();
            LogSupport.configure(config.logDirectory());

            if ("--watch".equals(args[0])) {
                new SpoolWatcher(config).run();
                return;
            }

            Path input = Path.of(args[0]).toAbsolutePath().normalize();
            Path output = deriveOutputPath(input, config.outputSuffix());

            LOGGER.info(() -> "Iniciando processamento em modo "
                    + (config.autoPrint() ? "impressao" : "preview")
                    + "; estilo="
                    + (config.enhancedVisualStyle() ? "enhanced" : "classic")
                    + "; logo=" + (config.logoEnabled() ? "enabled" : "disabled")
                    + "; arquivo=" + input);
            PdfThermalConverter.ConversionResult result = new PdfThermalConverter()
                    .convert(input, output, config);
            generatedOutput = result.output();

            System.out.println("Conversao concluida.");
            System.out.println("Arquivo: " + result.output());
            System.out.println("Pagina termica: " + result.dimensionsSummary());
            System.out.println("Texto preservado: " + result.validation().textPreserved());
            System.out.println("QR/imagens preservados: " + result.validation().imagesPreserved());
            System.out.println("Logo conforme configuracao: " + result.validation().logoPreserved());

            if (config.autoPrint()) {
                PdfWindowsPrinter.PrintResult printResult = new PdfWindowsPrinter()
                        .print(result.output(), config);
                System.out.println("Impressora: " + printResult.printerName());
                System.out.println("Trabalho: " + printResult.jobName());
                System.out.println("Envio ao spooler: aceito");
            } else {
                System.out.println("Modo preview: nenhuma impressao foi realizada.");
            }
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Falha ao processar a NFC-e.", exception);
            System.err.println("Erro: " + exception.getMessage());
            if (generatedOutput != null) {
                System.err.println("O PDF termico foi preservado em: " + generatedOutput);
            }
            System.exit(1);
        }
    }

    static Path deriveOutputPath(Path input, String suffix) {
        String filename = input.getFileName().toString();
        String lower = filename.toLowerCase(Locale.ROOT);
        String base = lower.endsWith(".pdf")
                ? filename.substring(0, filename.length() - 4)
                : filename;
        return input.resolveSibling(base + suffix + ".pdf");
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  java -jar nfce-printer.jar \"C:\\Spool\\impressao nfc-e_arquivo.pdf\"");
        System.out.println("  java -jar nfce-printer.jar --watch");
        System.out.println();
        System.out.println("Arquivo PDF: conversao manual, preservada para testes e reimpressoes intencionais.");
        System.out.println("--watch: monitora spool.directory com controle persistente de tentativas.");
        System.out.println("watch.allow.completed.reprint=true: permite solicitar novamente uma nota concluida.");
        System.out.println("auto.print=false: gera preview sem imprimir.");
        System.out.println("auto.print=true: gera, valida e envia ao driver configurado.");
    }
}
