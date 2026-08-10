package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.engine.MatchingEngine;
import com.victorien.matchingengine.generator.OrderGenerator;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Trade;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingWorkerInterruptionTest {

    @Test
    void normalShutdownShouldPropagateSignalExactlyOnce() throws InterruptedException {
        BlockingQueue<OrderCommand> inputQueue = new ArrayBlockingQueue<>(4);
        BlockingQueue<Trade> outputQueue = new ArrayBlockingQueue<>(4);
        MatchingWorker worker = new MatchingWorker(inputQueue, outputQueue, new MatchingEngine());

        inputQueue.put(OrderGenerator.SHUTDOWN_SIGNAL);

        worker.run();

        List<Trade> collected = drainAll(outputQueue);

        assertEquals(1, collected.size(),
                "Le signal d'arrêt ne doit être propagé qu'une seule fois sur le chemin normal");
        assertEquals(MatchingWorker.SHUTDOWN_SIGNAL, collected.get(0));
    }

    @Test
    void interruptionWhileBlockedOnTakeShouldStillPropagateShutdownSignal() throws InterruptedException {
        BlockingQueue<OrderCommand> inputQueue = new ArrayBlockingQueue<>(1);
        BlockingQueue<Trade> outputQueue = new ArrayBlockingQueue<>(4);
        MatchingWorker worker = new MatchingWorker(inputQueue, outputQueue, new MatchingEngine());

        Thread workerThread = new Thread(worker, "matching-worker-under-test");
        workerThread.start();

        waitUntilBlockedOnTake(workerThread);

        workerThread.interrupt();
        workerThread.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(!workerThread.isAlive(), "Le thread doit se terminer après interruption");

        List<Trade> collected = drainAll(outputQueue);

        assertEquals(1, collected.size(),
                "Le signal d'arrêt doit être propagé exactement une fois après interruption, "
                        + "pour éviter que PersistenceWorker reste bloqué indéfiniment");
        assertEquals(MatchingWorker.SHUTDOWN_SIGNAL, collected.get(0));
    }

    @Test
    void interruptedThreadShouldNotHangWaitingForPersistenceWorker() throws InterruptedException {
        BlockingQueue<OrderCommand> commandQueue = new ArrayBlockingQueue<>(1);
        BlockingQueue<Trade> tradeQueue = new ArrayBlockingQueue<>(4);
        MatchingWorker matchingWorker = new MatchingWorker(commandQueue, tradeQueue, new MatchingEngine());

        Thread matchingThread = new Thread(matchingWorker, "matching-worker-under-test");
        matchingThread.start();
        waitUntilBlockedOnTake(matchingThread);
        matchingThread.interrupt();
        matchingThread.join(TimeUnit.SECONDS.toMillis(2));

        StringWriterStub csvSink = new StringWriterStub();
        Thread persistenceThread = new Thread(
                new PersistenceWorker(tradeQueue, csvSink), "persistence-worker-under-test");
        persistenceThread.start();
        persistenceThread.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(!persistenceThread.isAlive(),
                "PersistenceWorker doit se terminer -- s'il reste bloqué, "
                        + "le signal d'arrêt n'a pas été propagé correctement");
    }

    private void waitUntilBlockedOnTake(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(1);
        while (System.currentTimeMillis() < deadline) {
            if (thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private List<Trade> drainAll(BlockingQueue<Trade> queue) {
        List<Trade> collected = new ArrayList<>();
        queue.drainTo(collected);
        return collected;
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