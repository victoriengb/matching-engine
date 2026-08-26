package com.victorien.matchingengine;

import com.victorien.matchingengine.dod.MatchingEngineSoA;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;
import com.victorien.matchingengine.orchestration.MatchingWorker;
import com.victorien.matchingengine.orchestration.PersistenceWorker;
import com.victorien.matchingengine.ring.RingBuffer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Main {

    private static final int COMMAND_COUNT = 10_000;
    private static final double TARGET_RATE_PER_SECOND = 10_000.0;
    private static final int RING_BUFFER_CAPACITY = 1024;
    private static final Path OUTPUT_DIR = Path.of("resources/");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final int MATCHING_ENGINE_CAPACITY = 8192;
    private static final int PRICE_TICKS = 10_000_000;

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        RingBuffer<OrderCommand> commandQueue = new RingBuffer<>(RING_BUFFER_CAPACITY);
        RingBuffer<Trade> tradeQueue = new RingBuffer<>(RING_BUFFER_CAPACITY);

        MatchingEngineSoA engine = new MatchingEngineSoA(MATCHING_ENGINE_CAPACITY, PRICE_TICKS);
        String outputCsvPath = buildOutputCsvPath();

        try (Writer csvWriter = new FileWriter(outputCsvPath)) {

            Thread generatorThread = new Thread(
                    new OrderGenerator(commandQueue, COMMAND_COUNT, TARGET_RATE_PER_SECOND),
                    "order-generator");
            Thread matchingThread = new Thread(
                    new MatchingWorker(commandQueue, tradeQueue, engine),
                    "matching-worker");
            Thread persistenceThread = new Thread(
                    new PersistenceWorker(tradeQueue, csvWriter),
                    "persistence-worker");

            generatorThread.start();
            matchingThread.start();
            persistenceThread.start();

            generatorThread.join();
            matchingThread.join();
            persistenceThread.join();
        }

        System.out.println("Pipeline terminé. Trades journalisés dans " + outputCsvPath);
    }

    private static String buildOutputCsvPath() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        return OUTPUT_DIR.resolve("trades-" + timestamp + ".csv").toString();
    }
}