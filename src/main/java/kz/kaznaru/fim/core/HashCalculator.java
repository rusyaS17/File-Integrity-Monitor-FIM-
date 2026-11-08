package kz.kaznaru.fim.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class HashCalculator {

    public String calculateSHA256(Path filePath) throws IOException {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream inputStream = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return HexFormat.of().formatHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not found", e);
        }
    }
}