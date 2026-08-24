package br.com.alkhemylab.nfceprinter.pdf;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@SuppressWarnings("deprecation")
final class FontEncodingSupport {
    private FontEncodingSupport() {
    }

    static float stringWidth(PDFont font, String text, float fontSize) throws IOException {
        byte[] encoded = encodeWithCanonicalLayoutCodes(font, text);
        float width = 0f;
        try (ByteArrayInputStream input = new ByteArrayInputStream(encoded)) {
            while (input.available() > 0) {
                int code = font.readCode(input);
                width += font.getWidth(code);
            }
        }
        return width / 1000f * fontSize;
    }

    static void showText(PDPageContentStream contentStream, PDFont font, String text) throws IOException {
        byte[] encoded = encodeWithCanonicalLayoutCodes(font, text);
        String command = "<" + HexFormat.of().withUpperCase().formatHex(encoded) + "> Tj\n";
        contentStream.appendRawCommands(command.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] encodeWithCanonicalLayoutCodes(PDFont font, String text) throws IOException {
        byte[] encoded = font.encode(text);
        if (!(font instanceof PDSimpleFont)) {
            return encoded;
        }

        boolean canonicalSpace = sameUnicode(font, 0x20, 0xA0, " ");
        boolean canonicalHyphen = sameUnicode(font, 0x2D, 0xAD, "-");
        for (int index = 0; index < encoded.length; index++) {
            int code = Byte.toUnsignedInt(encoded[index]);
            if (canonicalSpace && code == 0xA0) {
                encoded[index] = 0x20;
            } else if (canonicalHyphen && code == 0xAD) {
                encoded[index] = 0x2D;
            }
        }
        return encoded;
    }

    private static boolean sameUnicode(PDFont font, int preferred, int duplicate, String expected) {
        return expected.equals(font.toUnicode(preferred)) && expected.equals(font.toUnicode(duplicate));
    }
}
