package com.victorien.matchingengine.ring;

import org.openjdk.jol.info.ClassLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valide empiriquement, via JOL, que Sequence occupe bien sa propre
 * cache line -- pas seulement en théorie mais en layout mémoire réel
 * mesuré par la JVM.
 */
class SequencePaddingTest {

    private static final int CACHE_LINE_SIZE_BYTES = 64;

    @Test
    void sequenceShouldOccupyAtLeastOneFullCacheLine() {
        long instanceSize = ClassLayout.parseClass(Sequence.class).instanceSize();

        assertTrue(instanceSize >= CACHE_LINE_SIZE_BYTES,
                "Sequence doit occuper au moins une cache line complète (64 octets) pour "
                        + "garantir l'isolation -- taille mesurée : " + instanceSize + " octets");
    }
}