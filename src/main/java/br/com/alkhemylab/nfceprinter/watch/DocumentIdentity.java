package br.com.alkhemylab.nfceprinter.watch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

public record DocumentIdentity(String sourceName, String sha256) {
    private static final int BUFFER_SIZE = 32 * 1024;

    public DocumentIdentity {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("O nome do arquivo nao pode ser vazio.");
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("SHA-256 invalido.");
        }
        sourceName = Normalizer.normalize(sourceName, Normalizer.Form.NFC);
        sha256 = sha256.toLowerCase();
    }

    public static DocumentIdentity from(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        Path filename = normalized.getFileName();
        if (filename == null || !Files.isRegularFile(normalized)) {
            throw new IOException("Arquivo nao encontrado para identificacao: " + normalized);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 nao esta disponivel nesta JVM.", exception);
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(normalized)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return new DocumentIdentity(filename.toString(), HexFormat.of().formatHex(digest.digest()));
    }
}
