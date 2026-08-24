package br.com.alkhemylab.nfceprinter.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class LogSupport {
    private LogSupport() {
    }

    public static void configure(Path logDirectory) throws IOException {
        Files.createDirectories(logDirectory);

        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);

        FileHandler fileHandler = new FileHandler(
                logDirectory.resolve("nfce-printer.log").toString(),
                2_000_000,
                5,
                true);
        fileHandler.setEncoding("UTF-8");
        fileHandler.setFormatter(new SimpleFormatter());
        root.addHandler(fileHandler);
    }
}
