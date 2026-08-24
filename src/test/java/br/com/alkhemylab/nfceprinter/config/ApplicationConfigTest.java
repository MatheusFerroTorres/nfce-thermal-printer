package br.com.alkhemylab.nfceprinter.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationConfigTest {
    @Test
    void allowsCompletedReprintsByDefault() throws IOException {
        ApplicationConfig config = ApplicationConfig.load();

        assertTrue(config.watchAllowCompletedReprint());
    }
}
