package kz.kaznaru.fim.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class BaselineManager {

    private final HashCalculator hashCalculator;
    private final Map<String, String> baseline;

    public BaselineManager() {
        this.hashCalculator = new HashCalculator();
        this.baseline = new HashMap<>();
    }

    public void saveBaseline(Path filePath) throws IOException {
        String hash = hashCalculator.calculateSHA256(filePath);
        baseline.put(filePath.toAbsolutePath().toString(), hash);
    }

    public boolean checkFile(Path filePath) throws IOException {
        String key = filePath.toAbsolutePath().toString();

        if (!baseline.containsKey(key)) {
            return false;
        }

        String currentHash = hashCalculator.calculateSHA256(filePath);
        String originalHash = baseline.get(key);

        return currentHash.equals(originalHash);
    }

    public void saveBaselineToFile(Path filePath) throws IOException {
        StringBuilder data = new StringBuilder();

        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            data.append(entry.getKey())
                .append("|")
                .append(entry.getValue())
                .append(System.lineSeparator());
        }

        Files.writeString(filePath, data.toString());
    }
}