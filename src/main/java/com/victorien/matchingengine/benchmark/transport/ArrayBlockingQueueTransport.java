package com.victorien.matchingengine.benchmark.transport;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Adaptateur de l'ArrayBlockingQueue (Jalon 0) vers l'interface
 * Transport, reproduisant le protocole put()/take() employé par le
 * pipeline de la baseline.
 *
 * Contrairement au RingBufferTransport, l'attente est ici déléguée au
 * noyau : take() suspend le thread consommateur en état WAITING tant
 * qu'aucune donnée n'est disponible, et put() fait de même pour le
 * producteur lorsque la file est pleine. C'est précisément le coût de
 * cette suspension -- changement de contexte à l'endormissement, délai
 * de réveil à la republication -- que la mesure cross-thread cherche à
 * quantifier.
 */
public class ArrayBlockingQueueTransport implements Transport {

    private final BlockingQueue<Long> queue;

    public ArrayBlockingQueueTransport(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void publish(long payload) throws InterruptedException {
        queue.put(payload);
    }

    @Override
    public long receive() throws InterruptedException {
        return queue.take();
    }
}
