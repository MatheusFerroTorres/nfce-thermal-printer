package br.com.alkhemylab.nfceprinter.watch;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SpoolFileMatcher {
    private final Pattern pattern;
    private final String thermalSuffix;

    public SpoolFileMatcher(String glob, String thermalSuffix) {
        if (glob == null || glob.isBlank()) {
            throw new IllegalArgumentException("O filtro de arquivos nao pode ser vazio.");
        }
        this.pattern = Pattern.compile(
                globToRegex(normalize(glob)),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.thermalSuffix = normalize(thermalSuffix).toLowerCase(Locale.ROOT) + ".pdf";
    }

    public boolean matches(Path path) {
        Path filename = path == null ? null : path.getFileName();
        if (filename == null) {
            return false;
        }

        String normalized = normalize(filename.toString());
        return !normalized.toLowerCase(Locale.ROOT).endsWith(thermalSuffix)
                && pattern.matcher(normalized).matches();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            switch (character) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '\\', '.', '^', '$', '|', '(', ')', '[', ']', '{', '}', '+' ->
                        regex.append('\\').append(character);
                default -> regex.append(character);
            }
        }
        return regex.append('$').toString();
    }
}
