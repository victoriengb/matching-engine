package com.victorien.matchingengine.benchmark.transport;

import org.HdrHistogram.Histogram;

/**
 * Point d'entrée de la mesure comparative de latence de transit
 * cross-thread entre le mécanisme du Jalon 0 (ArrayBlockingQueue) et
 * celui du Jalon 1 (RingBuffer).
 *
 * Les deux transports sont mesurés successivement -- et non en
 * parallèle -- afin qu'ils ne se disputent pas les coeurs de la machine
 * pendant leur propre mesure, ce qui introduirait une interférence
 * asymétrique selon l'ordre d'exécution.
 *
 * La cadence est un paramètre volontairement exposé : la latence d'un
 * transport n'est pas une constante mais une fonction de la charge, et
 * l'écart entre les deux mécanismes n'a aucune raison d'être uniforme
 * sur toute la plage. Mesurer à plusieurs cadences permet d'observer où
 * chacun décroche.
 */
public final class TransportLatencyMain {

    private static final int CAPACITY = 1024;
    private static final int WARMUP_MESSAGES = 200_000;
    private static final int MEASURED_MESSAGES = 1_000_000;

    private static final double[] TARGET_RATES = {
            100_000.0,
            500_000.0,
            1_000_000.0
    };

    private TransportLatencyMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        TransportLatencyHarness.requireSufficientCores();
        System.out.printf("Coeurs disponibles : %d%n",
                Runtime.getRuntime().availableProcessors());

        for (double rate : TARGET_RATES) {
            System.out.printf("%n=== Cadence cible : %,.0f msg/s ===%n", rate);

            Histogram queueHistogram = TransportLatencyHarness.measure(
                    ArrayBlockingQueueTransport::new,
                    CAPACITY, WARMUP_MESSAGES, MEASURED_MESSAGES, rate);
            report("ArrayBlockingQueue (Jalon 0)", queueHistogram);

            Histogram ringHistogram = TransportLatencyHarness.measure(
                    RingBufferTransport::new,
                    CAPACITY, WARMUP_MESSAGES, MEASURED_MESSAGES, rate);
            report("RingBuffer (Jalon 1)", ringHistogram);
        }
    }

    private static void report(String label, Histogram histogram) {
        System.out.printf("%n--- %s ---%n", label);
        System.out.printf("  Echantillons : %,d%n", histogram.getTotalCount());
        System.out.printf("  Moyenne      : %,.0f ns%n", histogram.getMean());
        System.out.printf("  P50          : %,d ns%n", histogram.getValueAtPercentile(50.0));
        System.out.printf("  P90          : %,d ns%n", histogram.getValueAtPercentile(90.0));
        System.out.printf("  P99          : %,d ns%n", histogram.getValueAtPercentile(99.0));
        System.out.printf("  P99.9        : %,d ns%n", histogram.getValueAtPercentile(99.9));
        System.out.printf("  P99.99       : %,d ns%n", histogram.getValueAtPercentile(99.99));
        // P99.995 : le percentile exact de la NFR, inaccessible avec les
        // paliers fixes de JMH et rendu disponible par HdrHistogram.
        System.out.printf("  P99.995      : %,d ns%n", histogram.getValueAtPercentile(99.995));
        System.out.printf("  P99.999      : %,d ns%n", histogram.getValueAtPercentile(99.999));
        System.out.printf("  Max          : %,d ns%n", histogram.getMaxValue());
    }
}
