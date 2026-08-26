package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.dod.MatchingEngineSoA;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;
import com.victorien.matchingengine.ring.RingBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Vérifie le déterminisme du pipeline complet (NFR "Déterminisme") :
 * deux exécutions avec la même seed doivent produire exactement la même
 * séquence de trades, dans le même ordre.
 *
 * Taux de 100/s retenu pour ce test (et non 10 000/s) : cf. limitation
 * documentée dans OrderGenerator -- à 100/s, le comportement Poisson est
 * fidèle (validé empiriquement), contrairement à 10 000/s où
 * Thread.sleep() tronque 99,995 % des délais à 0 ms.
 */
class DeterminismIntegrationTest {

    private static final double TEST_RATE_PER_SECOND = 100.0;
    private static final int RING_CAPACITY = 2048;

    private static final int MATCHING_ENGINE_CAPACITY = 8192;
    private static final int PRICE_TICKS = 10_000_000;

    @Test
    void samesSeedShouldProduceIdenticalTradeSequence() throws InterruptedException {
        List<Trade> firstRun = runPipeline(1_000);
        List<Trade> secondRun = runPipeline(1_000);

        assertEquals(firstRun.size(), secondRun.size(),
                "Les deux runs doivent produire le même nombre de trades");

        for (int i = 0; i < firstRun.size(); i++) {
            assertTradesEqualIgnoringExecutionTime(firstRun.get(i), secondRun.get(i), i);
        }
    }

    private void assertTradesEqualIgnoringExecutionTime(Trade expected, Trade actual, int index) {
        assertEquals(expected.tradeId(), actual.tradeId(), "tradeId différent à l'index " + index);
        assertEquals(expected.buyOrderId(), actual.buyOrderId(), "buyOrderId différent à l'index " + index);
        assertEquals(expected.sellOrderId(), actual.sellOrderId(), "sellOrderId différent à l'index " + index);
        assertEquals(expected.executionPrice(), actual.executionPrice(), "executionPrice différent à l'index " + index);
        assertEquals(expected.quantity(), actual.quantity(), "quantity différent à l'index " + index);
        // executedAt volontairement exclu -- cf. TODO Jalon 1 sur Instant.now()
    }

    @Test
    void pipelineShouldProduceAtLeastOneTradeForSanityCheck() throws InterruptedException {
        List<Trade> trades = runPipeline(1_000);

        assertFalse(trades.isEmpty(), "Le pipeline de test ne produit aucun trade -- "
                + "le test de déterminisme ne peut rien prouver dans ces conditions");
    }

    private List<Trade> runPipeline(int commandCount) throws InterruptedException {
        RingBuffer<OrderCommand> commands = new RingBuffer<>(RING_CAPACITY);
        RingBuffer<Trade> trades = new RingBuffer<>(RING_CAPACITY);
        MatchingEngineSoA engine = new MatchingEngineSoA(MATCHING_ENGINE_CAPACITY, PRICE_TICKS);

        Thread generator = new Thread(
                new OrderGenerator(commands, commandCount, TEST_RATE_PER_SECOND));
        Thread matcher = new Thread(new MatchingWorker(commands, trades, engine));

        generator.start();
        matcher.start();

        generator.join();
        matcher.join();

        return drainPublished(trades);
    }

    /**
     * Lecture séquentielle de tout ce qui a été publié sur le ring, de la
     * séquence 0 jusqu'au curseur inclus. RingBuffer n'expose pas de
     * drainTo() (contrairement à BlockingQueue) -- ce n'est pas un besoin
     * de production, seulement de test, d'où cette lecture manuelle
     * plutôt qu'une méthode ajoutée au RingBuffer pour ce seul usage.
     */
    private List<Trade> drainPublished(RingBuffer<Trade> ring) {
        List<Trade> collected = new ArrayList<>();
        long cursor = ring.getCursor();

        for (long seq = 0; seq <= cursor; seq++) {
            Trade trade = ring.get(seq);
            if (trade != null && !trade.equals(MatchingWorker.SHUTDOWN_SIGNAL)) {
                collected.add(trade);
            }
        }
        return collected;
    }
}