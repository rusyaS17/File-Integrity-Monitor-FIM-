package kz.kaznaru.fim.core;

import java.io.IOException;
import java.nio.file.Path;

public class IntegrityChecker {

    private final HashCalculator hashCalculator;

    public IntegrityChecker() {
        this.hashCalculator = new HashCalculator();
    }

    public boolean isFileModified(Path filePath, String originalHash)
            throws IOException {

        String currentHash = hashCalculator.calculateSHA256(filePath);

        return !currentHash.equals(originalHash);
    }

    public String getCurrentHash(Path filePath) throws IOException {
        return hashCalculator.calculateSHA256(filePath);
    }
}