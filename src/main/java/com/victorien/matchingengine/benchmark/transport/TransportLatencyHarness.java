package com.victorien.matchingengine.benchmark.transport;

import org.HdrHistogram.Histogram;

import java.util.function.IntFunction;

/**
 * Harnais de mesure de la latence de transit cross-thread d'un
 * Transport : temps écoulé entre l'instant où une donnée AURAIT DÛ être
 * émise par le producteur et l'instant où elle est effectivement lue par
 * le consommateur, sur deux threads distincts.
 *
 * POURQUOI PAS JMH. JMH chronomètre l'exécution d'une méthode sur le
 * thread qui l'appelle. Or la grandeur qui nous intéresse ici est un
 * délai de transit ENTRE deux threads : elle commence dans le thread
 * producteur et se termine dans le thread consommateur, et aucune
 * méthode unique ne l'encadre. Un harnais dédié est donc nécessaire, au
 * prix de devoir réimplémenter manuellement ce que JMH fournissait
 * gratuitement -- notamment la phase de warmup du compilateur JIT.
 *
 * CORRECTION DE LA COORDINATED OMISSION. C'est le point méthodologique
 * central de cette mesure. Un harnais naïf horodaterait la donnée au
 * moment où le producteur parvient effectivement à la publier. Si le
 * transport bloque le producteur -- file pleine, verrou contesté --
 * l'horodatage serait alors décalé d'autant, et la latence mesurée
 * resterait faible : le système paraîtrait rapide au moment précis où
 * il est en difficulté. C'est le biais que Gil Tene nomme coordinated
 * omission, et qui masque exactement les épisodes de dégradation que la
 * mesure devrait révéler.
 *
 * La correction consiste à fixer a priori une cadence d'émission
 * (targetRatePerSecond) et à horodater chaque message avec son instant
 * d'émission THÉORIQUE -- startTime + i x intervalle -- et non avec
 * l'instant réel de publication. Si le producteur prend du retard, les
 * instants théoriques continuent d'avancer sans lui : le retard
 * accumulé se reporte intégralement sur la latence mesurée des messages
 * suivants, au lieu d'être silencieusement absorbé.
 *
 * CONTRÔLE DE CADENCE PAR BUSY-SPIN. Le rythme d'émission est tenu par
 * une attente active sur System.nanoTime(), et non par Thread.sleep()
 * dont la granularité milliseconde a déjà été mesurée comme
 * insuffisante à haute fréquence (cf. OrderGenerator). Ce busy-spin
 * consomme un cœur pendant toute la mesure, mais il est appliqué à
 * l'identique aux deux transports comparés : il ne biaise donc pas la
 * comparaison, seulement le coût absolu en ressources du banc de test.
 *
 * LIMITE -- comparabilité de System.nanoTime() entre threads. La mesure
 * suppose que nanoTime() est cohérent d'un thread à l'autre au sein de
 * la même JVM. C'est le cas en pratique sur les architectures modernes
 * disposant d'un compteur temporel synchronisé entre coeurs, mais la
 * spécification Java ne le garantit pas formellement : une migration de
 * thread entre coeurs pourrait théoriquement introduire un écart. Cette
 * hypothèse est retenue faute d'alternative accessible depuis Java
 * standard, et constitue une limite assumée de la mesure.
 *
 * LIMITE -- autoboxing. Le payload transitant est un long encapsulé en
 * Long, ce qui provoque une allocation par message dans les deux
 * transports. Cette allocation ajoute une pression sur le Garbage
 * Collector absente d'une implémentation Zero-Allocation, mais elle
 * s'applique identiquement des deux côtés et reste représentative du
 * pipeline réel, qui transporte lui aussi des références d'objets
 * (OrderCommand, Trade).
 */
public final class TransportLatencyHarness {

    private static final long LOWEST_DISCERNIBLE_NANOS = 1L;
    private static final long HIGHEST_TRACKABLE_NANOS = 60L * 1_000_000_000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    /**
     * Le harnais fait tourner simultanément deux threads en attente
     * active : le producteur, qui scrute System.nanoTime() pour tenir la
     * cadence, et le consommateur du RingBuffer, qui scrute la séquence
     * publiée. Aucun des deux ne cède volontairement son coeur.
     *
     * En dessous de trois coeurs, ces deux threads se disputent le
     * processeur et ne progressent qu'au rythme des préemptions forcées
     * de l'ordonnanceur -- la latence mesurée reflète alors le quantum
     * d'ordonnancement du système, de l'ordre de la milliseconde, et non
     * le coût réel du transport. Le phénomène a été observé lors de la
     * mise au point de ce harnais sur une machine à coeur unique : la
     * médiane du RingBuffer s'y établissait autour de 3,8 ms, valeur
     * dépourvue de sens métrologique mais très proche du quantum
     * d'ordonnancement Linux par défaut.
     */
    private static final int MINIMUM_CORES = 3;

    private TransportLatencyHarness() {
    }

    /**
     * Vérifie que la machine dispose d'assez de coeurs pour que la
     * mesure ait un sens. Échoue explicitement plutôt que de produire
     * des chiffres silencieusement invalides.
     */
    public static void requireSufficientCores() {
        int availableCores = Runtime.getRuntime().availableProcessors();
        if (availableCores < MINIMUM_CORES) {
            throw new IllegalStateException(
                    "Mesure impossible : " + availableCores + " coeur(s) disponible(s), "
                            + MINIMUM_CORES + " requis. Les threads en attente active se "
                            + "disputeraient le processeur et la latence mesuree refleterait "
                            + "le quantum d'ordonnancement du systeme, pas le cout du transport.");
        }
    }

    /**
     * Exécute une mesure complète : phase de warmup non enregistrée,
     * puis phase de mesure enregistrée dans un histogramme.
     *
     * @param transportFactory   fabrique du transport sous test, invoquée
     *                           séparément pour le warmup et pour la
     *                           mesure afin qu'aucun état résiduel de la
     *                           chauffe ne pollue les résultats
     * @param capacity           capacité du tampon
     * @param warmupMessages     messages émis sans enregistrement, pour
     *                           laisser le JIT compiler le hot path
     * @param measuredMessages   messages émis et enregistrés
     * @param targetRatePerSecond cadence d'émission visée
     */
    public static Histogram measure(IntFunction<Transport> transportFactory,
                                    int capacity,
                                    int warmupMessages,
                                    int measuredMessages,
                                    double targetRatePerSecond) throws InterruptedException {

        runPhase(transportFactory.apply(capacity), warmupMessages, targetRatePerSecond, null);

        Histogram histogram = new Histogram(
                LOWEST_DISCERNIBLE_NANOS, HIGHEST_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
        runPhase(transportFactory.apply(capacity), measuredMessages, targetRatePerSecond, histogram);

        return histogram;
    }

    /**
     * Une phase complète producteur/consommateur. L'histogramme est
     * nullable : lorsqu'il l'est, les latences sont calculées mais non
     * enregistrées (phase de warmup), de sorte que le chemin de code
     * exécuté reste aussi proche que possible de celui de la mesure.
     */
    private static void runPhase(Transport transport,
                                 int messageCount,
                                 double targetRatePerSecond,
                                 Histogram histogram) throws InterruptedException {

        long intervalNanos = (long) (1_000_000_000L / targetRatePerSecond);

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    long intendedEmissionTime = transport.receive();
                    if (intendedEmissionTime == Transport.SHUTDOWN_SIGNAL) {
                        return;
                    }
                    long latencyNanos = System.nanoTime() - intendedEmissionTime;
                    if (histogram != null && latencyNanos >= 0) {
                        histogram.recordValue(latencyNanos);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "latency-consumer");

        consumer.start();

        long startTime = System.nanoTime();
        for (int i = 0; i < messageCount; i++) {
            long intendedEmissionTime = startTime + (long) i * intervalNanos;

            // Attente active jusqu'à l'instant d'émission théorique. Si
            // le producteur est déjà en retard, la condition est fausse
            // d'emblée et l'émission a lieu immédiatement : le retard
            // n'est pas rattrapé par un décalage de l'horodatage, il se
            // reporte sur la latence mesurée -- c'est là que se joue la
            // correction de la coordinated omission.
            while (System.nanoTime() < intendedEmissionTime) {
                // busy-spin de cadencement
            }

            transport.publish(intendedEmissionTime);
        }

        transport.publish(Transport.SHUTDOWN_SIGNAL);
        consumer.join();
    }
}
