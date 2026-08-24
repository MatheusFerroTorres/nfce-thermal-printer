package br.com.alkhemylab.nfceprinter.pdf;

import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ImageFingerprint {
    private ImageFingerprint() {
    }

    public static String sha256(PDImage image) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BufferedImage bufferedImage = image.getImage();
            digest.update(ByteBuffer.allocate(12)
                    .putInt(bufferedImage.getWidth())
                    .putInt(bufferedImage.getHeight())
                    .putInt(image.getBitsPerComponent())
                    .array());

            byte[] pixel = new byte[4];
            for (int y = 0; y < bufferedImage.getHeight(); y++) {
                for (int x = 0; x < bufferedImage.getWidth(); x++) {
                    int argb = bufferedImage.getRGB(x, y);
                    pixel[0] = (byte) (argb >>> 24);
                    pixel[1] = (byte) (argb >>> 16);
                    pixel[2] = (byte) (argb >>> 8);
                    pixel[3] = (byte) argb;
                    digest.update(pixel);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        }
    }
}
