package com.victorien.matchingengine.benchmark.transport;

import com.victorien.matchingengine.ring.RingBuffer;
import com.victorien.matchingengine.ring.Sequence;

/**
 * Adaptateur du RingBuffer (Jalon 1) vers l'interface Transport.
 *
 * Reproduit exactement le protocole de lecture employé par
 * MatchingWorker et PersistenceWorker dans le pipeline réel : busy-spin
 * sur getCursor(), lecture, puis avancement de la séquence de gating --
 * dans cet ordre, la séquence n'étant avancée qu'APRÈS la lecture pour
 * ne pas déclarer l'emplacement libre avant que la donnée n'en ait été
 * extraite.
 */
public class RingBufferTransport implements Transport {

    private final RingBuffer<Long> ring;
    private final Sequence consumerSequence = new Sequence(-1L);
    private long nextToRead = 0L;

    public RingBufferTransport(int capacity) {
        this.ring = new RingBuffer<>(capacity, 1);
        this.ring.addGatingSequence(this.consumerSequence);
    }

    @Override
    public void publish(long payload) {
        long sequence = ring.next();
        ring.set(sequence, payload);
        ring.publish(sequence);
    }

    @Override
    public long receive() {
        while (ring.getCursor() < nextToRead) {
            // busy-spin : attente active de la publication
        }
        long value = ring.get(nextToRead);
        consumerSequence.set(nextToRead);
        nextToRead++;
        return value;
    }
}
