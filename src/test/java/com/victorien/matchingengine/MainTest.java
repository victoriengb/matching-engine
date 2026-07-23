package com.victorien.matchingengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    private static final Path OUTPUT_DIR = Path.of("resources");
    private static final String CSV_HEADER =
            "tradeId,buyOrderId,sellOrderId,executionPrice,quantity,executedAt";

    @AfterEach
    void cleanUpGeneratedFiles() throws IOException {
        if (!Files.exists(OUTPUT_DIR)) return;
        try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
            for (Path file : files.filter(this::isTradesCsv).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void mainShouldRunWithoutThrowing() {
        assertDoesNotThrowRunning();
    }

    @Test
    void mainShouldCreateOutputDirectoryIfMissing() throws Exception {
        Main.main(new String[]{});

        assertTrue(Files.isDirectory(OUTPUT_DIR), "Le dossier resources/ doit être créé automatiquement");
    }

    @Test
    void mainShouldProduceOneCsvFileWithHeaderAndAtLeastOneTrade() throws Exception {
        Main.main(new String[]{});

        Path csvFile = latestTradesCsv();
        List<String> lines = Files.readAllLines(csvFile);

        assertFalse(lines.isEmpty(), "Le fichier CSV ne doit pas être vide");
        assertEquals(CSV_HEADER, lines.get(0), "La première ligne doit être l'en-tête CSV");
        assertTrue(lines.size() > 1, "Le fichier doit contenir au moins un trade en plus de l'en-tête");
    }

    @Test
    void csvDataLinesShouldHaveExpectedColumnCount() throws Exception {
        Main.main(new String[]{});

        Path csvFile = latestTradesCsv();
        List<String> lines = Files.readAllLines(csvFile);

        for (int i = 1; i < lines.size(); i++) {
            String[] columns = lines.get(i).split(",");
            assertEquals(6, columns.length,
                    "Ligne " + i + " : nombre de colonnes CSV inattendu -> " + lines.get(i));
        }
    }

    @Test
    void twoSuccessiveRunsShouldProduceTwoDistinctFiles() throws Exception {
        Main.main(new String[]{});
        Path firstFile = latestTradesCsv();

        Main.main(new String[]{});
        Path secondFile = latestTradesCsv();

        assertNotEquals(firstFile, secondFile, "Deux exécutions successives ne doivent pas écraser le même fichier");
        assertTrue(Files.exists(firstFile), "Le fichier de la première exécution doit toujours exister");
        assertTrue(Files.exists(secondFile), "Le fichier de la seconde exécution doit exister");
    }

    private void assertDoesNotThrowRunning() {
        try {
            Main.main(new String[]{});
        } catch (Exception e) {
            throw new AssertionError("Main.main() ne doit pas lever d'exception", e);
        }
    }

    private boolean isTradesCsv(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("trades-") && name.endsWith(".csv");
    }

    private Path latestTradesCsv() throws IOException {
        try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
            return files.filter(this::isTradesCsv).min((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("Aucun fichier trades-*.csv trouvé dans " + OUTPUT_DIR));
        }
    }
}