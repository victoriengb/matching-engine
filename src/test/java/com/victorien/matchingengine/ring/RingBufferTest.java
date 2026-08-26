package com.victorien.matchingengine.ring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Définit le contrat du Ring Buffer nu (Exercice 5) : un conteneur
 * circulaire de taille fixe, manipulé séquentiellement, sans aucune
 * notion de concurrence à ce stade.
 *
 * Ces tests sont à l'état ROUGE -- implémente RingBuffer<T> jusqu'à ce
 * qu'ils passent au vert, sans modifier leur contenu.
 */
class RingBufferTest {

    @Test
    void capacityMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<String>(100));
        assertDoesNotThrow(() -> new RingBuffer<String>(128));
    }

    @Test
    void publishedElementShouldBeRetrievableAtItsSequence() {
        RingBuffer<String> ring = new RingBuffer<>(8);

        long seq = ring.next();
        ring.set(seq, "premier");
        ring.publish(seq);

        assertEquals("premier", ring.get(seq));
    }

    @Test
    void sequenceShouldStrictlyIncreaseAcrossPublications() {
        RingBuffer<String> ring = new RingBuffer<>(8);

        long seq1 = ring.next();
        ring.set(seq1, "a");
        ring.publish(seq1);

        long seq2 = ring.next();
        ring.set(seq2, "b");
        ring.publish(seq2);

        assertTrue(seq2 > seq1, "La séquence doit strictement croître à chaque next()");
        assertEquals("a", ring.get(seq1));
        assertEquals("b", ring.get(seq2));
    }

    @Test
    void sequenceShouldWrapAroundToSamePhysicalSlot() {
        // Capacité 4 : les séquences 0 et 4 doivent occuper le MÊME
        // emplacement physique dans le tableau interne (0 & 3 == 4 & 3 == 0).
        RingBuffer<String> ring = new RingBuffer<>(4);

        for (int i = 0; i < 4; i++) {
            long seq = ring.next();
            ring.set(seq, "valeur-" + seq);
            ring.publish(seq);
        }

        // La 5ème publication (séquence 4) réutilise le même emplacement
        // physique que la séquence 0 -- la valeur "valeur-0" est donc
        // écrasée. C'est le comportement attendu d'un buffer circulaire :
        // get(0) après ce point n'est plus défini/fiable, seul get(4)
        // doit retourner la donnée actuelle à cette position physique.
        long seq4 = ring.next();
        ring.set(seq4, "valeur-4");
        ring.publish(seq4);

        assertEquals("valeur-4", ring.get(seq4));
        assertEquals(4L, seq4);
    }

    @Test
    void indexingShouldUseMaskNotModulo() {
        // Vérifie indirectement l'usage du masque : pour une capacité de
        // 8 (masque 0b111), les séquences 3 et 11 doivent pointer vers
        // le même emplacement physique (3 & 7 == 11 & 7 == 3).
        RingBuffer<String> ring = new RingBuffer<>(8);

        for (int i = 0; i <= 11; i++) {
            long seq = ring.next();
            ring.set(seq, "v" + seq);
            ring.publish(seq);
        }

        assertEquals("v11", ring.get(11L));
    }

    @Test
    void capacityShouldBeExposedForDownstreamMaskCalculations() {
        RingBuffer<String> ring = new RingBuffer<>(16);

        assertEquals(16, ring.capacity());
    }
}