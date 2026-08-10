package com.victorien.matchingengine.generator;

import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.OrderCommand.CommandType;
import com.victorien.matchingengine.model.Side;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Génère des OrderCommand selon un processus de Poisson de taux
 * targetRatePerSecond : les inter-arrivées suivent une loi exponentielle
 * de paramètre lambda = targetRatePerSecond, ce qui modélise un flux
 * d'ordres de marché réaliste (arrivées indépendantes, sans horloge
 * fixe), conformément à la NFR de débit ("charge de référence de
 * 10 000 opérations par seconde" -- un taux moyen, pas un volume brut à
 * débiter le plus vite possible).
 *
 * Fourni tel quel : la génération aléatoire n'a pas de valeur analytique
 * pour le mémoire, contrairement à ce qu'elle alimente en aval.
 *
 * LIMITATION CONNUE -- granularité de Thread.sleep(), mesurée
 * empiriquement :
 * Thread.sleep() ne peut honorer que des délais entiers en
 * millisecondes (le paramètre est un long en ms). Or pour un taux cible
 * de 10 000 ordres/seconde, l'inter-arrivée moyenne théorique est de
 * 0.1 ms. Une simulation locale (100 000 tirages, targetRate = 10 000)
 * montre que 99,995 % des délais tirés sont tronqués à 0 ms par la
 * conversion en long -- ce n'est donc pas une simple gigue d'arrondi
 * marginale, mais une troncature quasi systématique.
 *
 * Conséquence concrète : à 10 000/s, ce générateur n'approxime PAS un
 * processus de Poisson -- il produit en boucle quasi continue, à la
 * vitesse maximale que la JVM et la file peuvent absorber, avec
 * occasionnellement (0,005 % des cas) une vraie pause d'au moins 1 ms.
 * Le comportement Poisson n'est fidèle qu'à des taux cibles nettement
 * plus bas (validé empiriquement à 100/s, où le débit effectif mesuré
 * est proche de la cible, à l'arrondi systématique vers le bas près).
 *
 * Ce choix est délibéré pour cette baseline malgré cette limitation :
 * une implémentation précise à haute fréquence (spin-wait, ou horloge
 * haute résolution avec busy-polling) consommerait un cœur CPU en
 * continu rien que pour le rythme des arrivées, ce qui fausserait les
 * mesures de charge du système sous test. La troncature de Thread.sleep()
 * est donc acceptée comme limitation documentée à ce jalon. Si un jalon
 * ultérieur exige un débit cible élevé strictement respecté, une
 * alternative à base de LockSupport.parkNanos() (résolution sub-milliseconde
 * sur la plupart des OS) devra être évaluée.
 */
public class OrderGenerator implements Runnable {

    /** Poison pill : signale au consommateur qu'aucune commande ne suivra. */
    public static final OrderCommand SHUTDOWN_SIGNAL =
            new OrderCommand(CommandType.NEW, -1L, Side.BUY, -1.0, -1, -1L);

    private final BlockingQueue<OrderCommand> outputQueue;
    private final int commandCount;
    private final double targetRatePerSecond;
    private final AtomicLong orderIdGenerator = new AtomicLong(1);
    private final Random random = new Random(42); // seed fixe : déterminisme du jeu de test

    /**
     * @param outputQueue         file de destination des commandes générées
     * @param commandCount        nombre total de commandes à générer avant
     *                            l'envoi du signal d'arrêt
     * @param targetRatePerSecond taux moyen visé (lambda du processus de
     *                            Poisson), en commandes par seconde
     */
    public OrderGenerator(BlockingQueue<OrderCommand> outputQueue,
                          int commandCount,
                          double targetRatePerSecond) {
        this.outputQueue = outputQueue;
        this.commandCount = commandCount;
        this.targetRatePerSecond = targetRatePerSecond;
    }

    @Override
    public void run() {
        try {
            for (int i = 0; i < commandCount; i++) {
                outputQueue.put(randomCommand());
                Thread.sleep(nextInterArrivalMillis());
            }
            outputQueue.put(SHUTDOWN_SIGNAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.getLogger(OrderGenerator.class.getName()).log(Level.SEVERE, "Thread interrupted unexpectedly");
        }
    }

    /**
     * Tire un délai d'inter-arrivée selon une loi exponentielle de
     * paramètre lambda = targetRatePerSecond (en événements/seconde).
     * Formule d'inversion de la fonction de répartition :
     *     interArrival = -ln(1 - U) / lambda,   U ~ Uniforme(0,1)
     *
     * cf. limitation de granularité documentée en tête de classe.
     */
    private long nextInterArrivalMillis() {
        double u = random.nextDouble();
        double interArrivalSeconds = -Math.log(1 - u) / targetRatePerSecond;
        return (long) (interArrivalSeconds * 1000);
    }

    private OrderCommand randomCommand() {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        double price = 90.0 + random.nextInt(2000) / 100.0; // borné [90, 110]
        int quantity = 1 + random.nextInt(20);
        return new OrderCommand(
                CommandType.NEW,
                orderIdGenerator.getAndIncrement(),
                side,
                price,
                quantity,
                System.nanoTime()
        );
    }
}
