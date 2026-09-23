package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.dod.MatchingEngineSoA;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;
import com.victorien.matchingengine.ring.RingBuffer;
import com.victorien.matchingengine.ring.Sequence;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MatchingWorker implements Runnable {

    public static final Trade SHUTDOWN_SIGNAL =
            new Trade(-1L, -1L, -1L, -1.0, -1, -1L);

    private final RingBuffer<OrderCommand> inputRingBufferOrderCommand;
    private final Sequence sequenceInputRingBuffer = new Sequence(-1L);
    private final RingBuffer<Trade> outputRingBufferTrade;
    private final MatchingEngineSoA engine;

    public MatchingWorker(RingBuffer<OrderCommand> inputRingBufferOrderCommand,
                          RingBuffer<Trade> outputRingBufferTrade,
                          MatchingEngineSoA engine) {
        this.inputRingBufferOrderCommand = inputRingBufferOrderCommand;
        this.outputRingBufferTrade = outputRingBufferTrade;

        this.inputRingBufferOrderCommand.addGatingSequence(this.sequenceInputRingBuffer);
        this.engine = engine;
    }

    @Override
    public void run() {
        OrderCommand inputCommand;
        List<Trade> trades;
        boolean shutdownPropagated = false;
        try{
            while (true) {
                while (this.inputRingBufferOrderCommand.getCursor() <= this.sequenceInputRingBuffer.get()){
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("Interrupted while spinning on inputRingBufferOrderCommand");
                    }
                }

                long nextToRead = this.sequenceInputRingBuffer.get() + 1;
                inputCommand = inputRingBufferOrderCommand.get(nextToRead);
                this.sequenceInputRingBuffer.set(nextToRead);

                if (inputCommand.equals(OrderGenerator.SHUTDOWN_SIGNAL)) {
                    long sequence = this.outputRingBufferTrade.next();
                    outputRingBufferTrade.set(sequence, MatchingWorker.SHUTDOWN_SIGNAL);
                    outputRingBufferTrade.publish(sequence);
                    shutdownPropagated = true;
                    break;
                }
                trades = this.engine.process(inputCommand);

                for (Trade trade : trades){
                    long sequence = this.outputRingBufferTrade.next();
                    this.outputRingBufferTrade.set(sequence, trade);
                    this.outputRingBufferTrade.publish(sequence);
                }
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
     * bloqué en busy-spin indéfiniment si ce thread s'arrête de façon
     * anormale (interruption). Contrairement à la version BlockingQueue
     * (qui utilisait offer() pour éviter un put() bloquant sur un flag déjà
     * positionné), next()/set()/publish() sur un RingBuffer n'a pas
     * d'équivalent non-bloquant -- si le ring est plein, cet appel peut lui
     * même bloquer en busy-spin. C'est un best-effort dégradé par rapport à
     * la version bloquante : accepté ici car le ring de sortie n'a aucune
     * raison d'être plein au moment d'un arrêt anormal en usage normal.
     */
    private void propagateShutdownBestEffort() {
        try {
            long sequence = this.outputRingBufferTrade.next();
            this.outputRingBufferTrade.set(sequence, MatchingWorker.SHUTDOWN_SIGNAL);
            this.outputRingBufferTrade.publish(sequence);
        } catch (RuntimeException e) {
            Logger.getLogger(MatchingWorker.class.getName())
                    .log(Level.WARNING, "Impossible de propager le signal d'arrêt après interruption");
        }
    }
}