package com.victorien.matchingengine.benchmark.transport;

/**
 * Abstraction du mécanisme de transport inter-threads, permettant au
 * harnais de mesure d'exécuter un protocole strictement identique sur
 * les deux implémentations comparées (ArrayBlockingQueue du Jalon 0,
 * RingBuffer du Jalon 1).
 *
 * Cette interface n'existe QUE pour le banc de mesure : le pipeline de
 * production n'en a pas besoin, puisqu'il ne manipule qu'une seule
 * implémentation à la fois. L'introduire ici garantit que le code du
 * harnais -- contrôle de cadence, horodatage, enregistrement des
 * latences -- est rigoureusement le même dans les deux cas, et que la
 * seule variable qui change entre les deux mesures est bien le
 * mécanisme de transport lui-même.
 */
public interface Transport {

    /** Valeur sentinelle signalant au consommateur la fin du flux. */
    long SHUTDOWN_SIGNAL = Long.MIN_VALUE;

    /**
     * Publie une valeur. Bloque (par verrou ou par busy-spin selon
     * l'implémentation) si le tampon est plein.
     */
    void publish(long payload) throws InterruptedException;

    /**
     * Lit la prochaine valeur. Bloque (par verrou ou par busy-spin
     * selon l'implémentation) tant qu'aucune valeur n'est disponible.
     */
    long receive() throws InterruptedException;
}
