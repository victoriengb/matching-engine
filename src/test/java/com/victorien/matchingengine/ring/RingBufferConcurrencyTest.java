package com.victorien.matchingengine.ring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests à l'état ROUGE pour la concurrence Single-Writer du RingBuffer.
 * Ne modifie pas leur contenu -- implémente jusqu'à ce qu'ils passent.
 */
class RingBufferConcurrencyTest {

    // --- Visibilité cross-thread (le rôle réel de publish()) ---

    @Test
    void getCursorShouldReflectLastPublishedSequenceOnly() {
        RingBuffer<String> ring = new RingBuffer<>(8);

        assertEquals(-1L, ring.getCursor(),
                "Avant toute publication, le curseur visible doit indiquer qu'aucune séquence n'est prête");

        long seq = ring.next();
        ring.set(seq, "valeur");

        assertEquals(-1L, ring.getCursor(),
                "set() seul ne doit PAS rendre la séquence visible -- c'est le rôle de publish()");

        ring.publish(seq);

        assertEquals(0L, ring.getCursor(),
                "Après publish(), le curseur visible doit refléter la séquence publiée");
    }

    // --- Back-pressure : le producteur doit attendre le consommateur ---

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void nextShouldBlockWhenBufferIsFullUntilConsumerAdvances() throws InterruptedException {
        RingBuffer<String> ring = new RingBuffer<>(4);
        Sequence consumerSequence = new Sequence(-1L);
        ring.addGatingSequence(consumerSequence);

        // Remplir complètement le buffer (capacité 4 : séquences 0 à 3)
        for (int i = 0; i < 4; i++) {
            long seq = ring.next();
            ring.set(seq, "v" + seq);
            ring.publish(seq);
        }

        AtomicBoolean nextReturned = new AtomicBoolean(false);
        CountDownLatch producerStarted = new CountDownLatch(1);

        Thread producerThread = new Thread(() -> {
            producerStarted.countDown();
            ring.next(); // doit BLOQUER : aucune place libre, consommateur n'a rien lu
            nextReturned.set(true);
        });
        producerThread.start();
        producerStarted.await();

        // Laisse une fenêtre pour confirmer que next() est bien bloqué
        Thread.sleep(200);
        assertFalse(nextReturned.get(),
                "next() ne doit pas retourner tant que le consommateur n'a pas libéré de place");

        // Le consommateur "lit" en avançant sa séquence -- libère une place
        consumerSequence.set(0L);

        producerThread.join();
        assertTrue(nextReturned.get(),
                "next() doit débloquer dès que le consommateur a avancé sa séquence");
    }

    // --- Correction cross-thread : pas de lecture partielle/périmée ---

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentProducerConsumerShouldNeverObserveStaleOrTornValues() throws InterruptedException {
        // Test de stress : détecte une absence de volatile ou une erreur
        // de synchronisation qui laisserait le consommateur lire une
        // valeur incorrecte (null, ou une valeur d'une itération
        // antérieure) malgré un getCursor() indiquant la donnée prête.
        int iterations = 200_000;
        RingBuffer<Integer> ring = new RingBuffer<>(1024);
        Sequence consumerSequence = new Sequence(-1L);
        ring.addGatingSequence(consumerSequence);

        AtomicInteger errors = new AtomicInteger(0);

        Thread producer = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                long seq = ring.next();
                ring.set(seq, i);
                ring.publish(seq);
            }
        });

        Thread consumer = new Thread(() -> {
            long nextToRead = 0;
            while (nextToRead < iterations) {
                if (ring.getCursor() >= nextToRead) {
                    Integer value = ring.get(nextToRead);
                    if (value == null || value != nextToRead) {
                        errors.incrementAndGet();
                    }
                    consumerSequence.set(nextToRead);
                    nextToRead++;
                }
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        assertEquals(0, errors.get(),
                "Aucune lecture ne doit être incorrecte ou périmée : " + errors.get() + " erreurs détectées");
    }
}