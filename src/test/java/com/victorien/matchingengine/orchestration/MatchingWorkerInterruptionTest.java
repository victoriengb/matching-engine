package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.engine.MatchingEngine;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;
import com.victorien.matchingengine.ring.RingBuffer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie le comportement de MatchingWorker face à l'interruption, dans
 * sa version RingBuffer (busy-spin).
 *
 * DIFFÉRENCE MAJEURE avec la version BlockingQueue (Jalon 0) : un thread
 * en busy-spin ne quitte JAMAIS l'état RUNNABLE -- il n'existe donc plus
 * de moyen fiable de détecter "le thread est en train d'attendre" via
 * Thread.getState() (contrairement à WAITING/TIMED_WAITING sur un
 * take() bloquant). Les tests ci-dessous se rabattent sur un délai fixe
 * pour laisser le thread entrer dans sa boucle de spin avant
 * d'interrompre -- une approximation temporelle, moins rigoureuse que
 * la détection d'état de la version précédente, mais la seule option
 * disponible avec ce modèle de concurrence.
 */
class MatchingWorkerInterruptionTest {

    private static final int RING_CAPACITY = 8;

    @Test
    void normalShutdownShouldPropagateSignalExactlyOnce() throws InterruptedException {
        RingBuffer<OrderCommand> inputRing = new RingBuffer<>(RING_CAPACITY);
        RingBuffer<Trade> outputRing = new RingBuffer<>(RING_CAPACITY);
        MatchingWorker worker = new MatchingWorker(inputRing, outputRing, new MatchingEngine());

        long seq = inputRing.next();
        inputRing.set(seq, OrderGenerator.SHUTDOWN_SIGNAL);
        inputRing.publish(seq);

        // Chemin normal : run() retourne dès réception du signal d'arrêt,
        // pas besoin de thread séparé.
        worker.run();

        assertEquals(0L, outputRing.getCursor(),
                "Une seule séquence doit avoir été publiée sur le ring de sortie");
        assertEquals(MatchingWorker.SHUTDOWN_SIGNAL, outputRing.get(0L));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.SECONDS)
    void interruptionWhileSpinningShouldStillPropagateShutdownSignal() throws InterruptedException {
        RingBuffer<OrderCommand> inputRing = new RingBuffer<>(RING_CAPACITY);
        RingBuffer<Trade> outputRing = new RingBuffer<>(RING_CAPACITY);
        MatchingWorker worker = new MatchingWorker(inputRing, outputRing, new MatchingEngine());

        // Rien n'est publié sur inputRing : le thread entre immédiatement
        // en busy-spin, dans l'attente d'une séquence qui ne viendra
        // jamais par la voie normale.
        Thread workerThread = new Thread(worker, "matching-worker-under-test");
        workerThread.start();

        waitBrieflyForSpinToStart();

        workerThread.interrupt();
        workerThread.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(!workerThread.isAlive(), "Le thread doit se terminer après interruption");
        assertEquals(0L, outputRing.getCursor(),
                "Le signal d'arrêt doit être propagé exactement une fois après interruption");
        assertEquals(MatchingWorker.SHUTDOWN_SIGNAL, outputRing.get(0L));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.SECONDS)
    void interruptedThreadShouldNotHangWaitingForPersistenceWorker() throws InterruptedException, java.io.IOException {
        RingBuffer<OrderCommand> inputRing = new RingBuffer<>(RING_CAPACITY);
        RingBuffer<Trade> outputRing = new RingBuffer<>(RING_CAPACITY);
        MatchingWorker matchingWorker = new MatchingWorker(inputRing, outputRing, new MatchingEngine());

        Thread matchingThread = new Thread(matchingWorker, "matching-worker-under-test");
        matchingThread.start();
        waitBrieflyForSpinToStart();
        matchingThread.interrupt();
        matchingThread.join(TimeUnit.SECONDS.toMillis(2));

        // PersistenceWorker démarré APRES l'interruption, pour lire le
        // signal déjà déposé dans outputRing par le finally de
        // MatchingWorker -- s'il n'a pas été déposé, ce thread reste en
        // busy-spin indéfiniment et le test échoue par @Timeout.
        StringWriterStub csvSink = new StringWriterStub();
        Thread persistenceThread = new Thread(
                new PersistenceWorker(outputRing, csvSink), "persistence-worker-under-test");
        persistenceThread.start();
        persistenceThread.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(!persistenceThread.isAlive(),
                "PersistenceWorker doit se terminer -- s'il reste bloqué, "
                        + "le signal d'arrêt n'a pas été propagé correctement");
    }

    /**
     * Laisse au thread producteur/consommateur le temps d'entrer dans sa
     * boucle de busy-spin avant qu'on ne l'interrompe. Approximation
     * temporelle assumée -- cf. Javadoc de classe : aucune détection
     * d'état fiable n'est possible avec un busy-spin.
     */
    private void waitBrieflyForSpinToStart() throws InterruptedException {
        Thread.sleep(100);
    }

    private static class StringWriterStub extends java.io.Writer {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void write(char[] cbuf, int off, int len) {
            buffer.append(cbuf, off, len);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}