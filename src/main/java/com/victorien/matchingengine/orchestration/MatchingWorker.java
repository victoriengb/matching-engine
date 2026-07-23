package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.engine.MatchingEngine;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread consommateur du module de matching : lit les OrderCommand
 * depuis inputQueue, les traite via MatchingEngine, et dépose les Trade
 * produits dans outputQueue à destination du module de persistance.
 *
 * Thread-safety : MatchingEngine n'est PAS thread-safe par construction
 * (cf. sa Javadoc), mais aucun verrou externe n'est nécessaire dans cette
 * configuration. L'accès concurrent à inputQueue est déjà géré par
 * BlockingQueue (verrouillage interne + garantie de visibilité mémoire
 * via la relation happens-before entre put() et take()). Au-delà de ce
 * point d'entrée, engine, orderBook et outputQueue ne sont jamais
 * touchés que par ce seul thread -- il n'y a donc pas de concurrence à
 * protéger sur la logique de matching elle-même.
 */
public class MatchingWorker implements Runnable {

    /**
     * Poison pill propagée vers outputQueue quand OrderGenerator.SHUTDOWN_SIGNAL
     * est reçu sur inputQueue : signale à PersistenceWorker qu'aucun trade
     * ne suivra. Mêmes valeurs sentinelles (-1) que OrderCommand.SHUTDOWN_SIGNAL,
     * par cohérence de convention entre les deux étages du pipeline.
     */
    public static final Trade SHUTDOWN_SIGNAL =
            new Trade(-1L, -1L, -1L, -1.0, -1, -1L);

    private final BlockingQueue<OrderCommand> inputQueue;
    private final BlockingQueue<Trade> outputQueue;
    private final MatchingEngine engine;

    public MatchingWorker(BlockingQueue<OrderCommand> inputQueue,
                          BlockingQueue<Trade> outputQueue,
                          MatchingEngine engine) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.engine = engine;
    }

    @Override
    public void run() {
        boolean shutdownPropagated = false;
        try {
            OrderCommand inputCommand;
            List<Trade> trades;
            while (true) {
                inputCommand = inputQueue.take();
                if (inputCommand.equals(OrderGenerator.SHUTDOWN_SIGNAL)) {
                    outputQueue.put(MatchingWorker.SHUTDOWN_SIGNAL);
                    shutdownPropagated = true;
                    break;
                }

                trades = this.engine.process(inputCommand);

                for (Trade trade : trades)
                    outputQueue.put(trade);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.getLogger(MatchingWorker.class.getName())
                    .log(Level.SEVERE, "Thread interrupted unexpectedly");
        } finally {
            if (!shutdownPropagated) {
                propagateShutdownBestEffort();
            }
        }
    }

    /**
     * Tentative de dernier recours pour éviter que PersistenceWorker reste
     * bloqué indéfiniment sur inputQueue.take() si ce thread s'arrête de
     * façon anormale (interruption). Le flag d'interruption ayant déjà été
     * positionné, un put() bloquant classique échouerait immédiatement -- on
     * l'isole donc dans son propre try/catch et on l'accepte comme
     * best-effort : si même cette tentative échoue, PersistenceWorker
     * restera bloqué, ce qui reste préférable à un plantage en cascade ici.
     */
    private void propagateShutdownBestEffort() {
        boolean offered = outputQueue.offer(MatchingWorker.SHUTDOWN_SIGNAL);
        if (!offered)
            Logger.getLogger(MatchingWorker.class.getName()).log(Level.WARNING, "Impossible de propager le signal d'arrêt : outputQueue pleine");
    }
}