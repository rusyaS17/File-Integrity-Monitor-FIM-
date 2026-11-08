package kz.kaznaru.fim.console;

import kz.kaznaru.fim.core.BaselineManager;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) {

        try {
            BaselineManager manager = new BaselineManager();

            Path file = Path.of("test.txt");
            Path baselineFile = Path.of("baseline.txt");

            manager.saveBaseline(file);
            manager.saveBaselineToFile(baselineFile);

            System.out.println("FILE INTEGRITY MONITOR");
            System.out.println("----------------------");
            System.out.println("Baseline saved successfully!");

            boolean safe = manager.checkFile(file);

            if (safe) {
                System.out.println("STATUS: FILE SAFE");
            } else {
                System.out.println("STATUS: FILE MODIFIED!");
            }

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }
}