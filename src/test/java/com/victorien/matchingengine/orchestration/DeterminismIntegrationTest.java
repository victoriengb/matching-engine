package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.engine.MatchingEngine;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
 * Thread.sleep() tronque 99,995 % des délais à 0 ms. Un test de
 * déterminisme doit s'appuyer sur un comportement de générateur
 * conforme à ce qu'il prétend faire, pas sur son mode dégradé.
 */
class DeterminismIntegrationTest {

    private static final double TEST_RATE_PER_SECOND = 100.0;

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

    /**
     * Compare deux trades en ignorant executedAt.
     *
     * Justification : executedAt dérive de Instant.now() dans
     * MatchingEngine.buildTrade() (cf. TODO Jalon 1 -- appel système sur le
     * chemin critique, non déterministe entre deux exécutions). Le
     * déterminisme visé par la NFR porte sur LA SÉQUENCE ET LE CONTENU des
     * matchs (mêmes ordres, mêmes prix, même ordre d'exécution), pas sur
     * l'horodatage d'exécution, qui dépend légitimement de l'horloge
     * murale. Une fois le TODO résolu (timestamp dérivé de données
     * déterministes plutôt que de l'horloge système), cette exclusion
     * pourra être retirée et remplacée par un assertEquals strict.
     */
    private void assertTradesEqualIgnoringExecutionTime(Trade expected, Trade actual, int index) {
        assertEquals(expected.tradeId(), actual.tradeId(), "tradeId différent à l'index " + index);
        assertEquals(expected.buyOrderId(), actual.buyOrderId(), "buyOrderId différent à l'index " + index);
        assertEquals(expected.sellOrderId(), actual.sellOrderId(), "sellOrderId différent à l'index " + index);
        assertEquals(expected.executionPrice(), actual.executionPrice(), "executionPrice différent à l'index " + index);
        assertEquals(expected.quantity(), actual.quantity(), "quantity différent à l'index " + index);
        // executedAt volontairement exclu -- cf. Javadoc de cette méthode
    }

    @Test
    void pipelineShouldProduceAtLeastOneTradeForSanityCheck() throws InterruptedException {
        // Garde-fou : si ce test échoue, samesSeedShouldProduceIdenticalTradeSequence
        // peut passer trivialement en comparant deux listes vides, ce qui ne
        // prouve rien sur le déterminisme réel du matching.
        List<Trade> trades = runPipeline(1_000);

        assertFalse(trades.isEmpty(), "Le pipeline de test ne produit aucun trade -- "
                + "le test de déterminisme ne peut rien prouver dans ces conditions");
    }

    private List<Trade> runPipeline(int commandCount) throws InterruptedException {
        BlockingQueue<OrderCommand> commands = new LinkedBlockingQueue<>();
        BlockingQueue<Trade> trades = new LinkedBlockingQueue<>();
        MatchingEngine engine = new MatchingEngine();

        Thread generator = new Thread(
                new OrderGenerator(commands, commandCount, TEST_RATE_PER_SECOND));
        Thread matcher = new Thread(new MatchingWorker(commands, trades, engine));

        generator.start();
        matcher.start();

        generator.join();
        matcher.join();

        List<Trade> collected = new ArrayList<>();
        trades.drainTo(collected);

        // Retire le signal d'arrêt propagé par MatchingWorker vers la file
        // de trades, en miroir d'OrderGenerator.SHUTDOWN_SIGNAL. Référencé
        // explicitement (pas de magic value -1L) pour rester couplé à la
        // définition canonique -- si le sentinel change de forme dans
        // MatchingWorker, ce test doit casser à la compilation, pas
        // silencieusement laisser passer un faux trade.
        collected.remove(MatchingWorker.SHUTDOWN_SIGNAL);

        return collected;
    }
}
