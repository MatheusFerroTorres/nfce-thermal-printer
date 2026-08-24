package br.com.alkhemylab.nfceprinter.print;

import javax.print.PrintService;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class PrinterLocator {
    private static final Logger LOGGER = Logger.getLogger(PrinterLocator.class.getName());

    public PrintService locate(String requestedName) throws PrinterException {
        PrintService[] services = PrinterJob.lookupPrintServices();
        List<String> names = new ArrayList<>(services.length);
        for (PrintService service : services) {
            names.add(service.getName());
        }

        int selectedIndex = selectIndex(names, requestedName);
        if (selectedIndex < 0) {
            String available = names.isEmpty() ? "nenhuma" : String.join(", ", names);
            throw new PrinterException(
                    "Fila de impressao nao encontrada: " + requestedName
                            + ". Filas disponiveis: " + available);
        }

        PrintService selected = services[selectedIndex];
        LOGGER.info(() -> "Impressora localizada: solicitada=\"" + requestedName
                + "\"; encontrada=\"" + selected.getName() + "\"");
        return selected;
    }

    static int selectIndex(List<String> printerNames, String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return -1;
        }

        String requested = requestedName.trim();
        for (int index = 0; index < printerNames.size(); index++) {
            String candidate = printerNames.get(index);
            if (candidate != null && candidate.trim().equals(requested)) {
                return index;
            }
        }
        for (int index = 0; index < printerNames.size(); index++) {
            String candidate = printerNames.get(index);
            if (candidate != null && candidate.trim().equalsIgnoreCase(requested)) {
                return index;
            }
        }
        return -1;
    }
}
