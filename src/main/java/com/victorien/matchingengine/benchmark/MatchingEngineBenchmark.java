package com.victorien.matchingengine.benchmark;

import com.victorien.matchingengine.engine.MatchingEngine;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.OrderCommand.CommandType;
import com.victorien.matchingengine.model.Side;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmarks du chemin critique de MatchingEngine (Jalon 0 -- baseline).
 *
 * Scénarios de matching (RESTING, FULL_MATCH, PARTIAL_MATCH), cf.
 * commentaires détaillés sur chaque @State ci-dessous.
 *
 * Scénarios d'annulation (CANCEL_BEST_CASE, CANCEL_WORST_CASE) : ajoutés
 * pour vérifier empiriquement la complexité documentée de
 * OrderBook.remove() -- O(log n) pour localiser le niveau de prix via
 * ordersById, + O(k) pour removeIf() sur la file, k étant le nombre
 * d'ordres au niveau de prix concerné. Cette dégradation était
 * documentée comme "mesurable sous forte contention" (Exercice 1) sans
 * jamais avoir été mesurée -- ces deux scénarios comblent ce trou entre
 * affirmation théorique et preuve empirique.
 *
 * NOTE @Fork(1) : choix délibéré pour les runs de développement courants
 * (déclenchés à chaque PR par le pipeline CI/CD). @Fork isole les biais
 * inter-JVM (décisions JIT variables d'un lancement à l'autre, état du
 * GC hérité d'une exécution précédente), un rôle distinct de
 * @Warmup/@Measurement qui lissent le bruit intra-JVM. Un fork unique
 * est un compromis vitesse/rigueur défendable ici : le pipeline compare
 * des jalons entiers (écarts d'un ordre de grandeur attendus, seuil de
 * gate à 20%), pas des variations fines de quelques pourcents entre deux
 * implémentations proches.
 * TODO : Fork(5) ou plus pour la mesure finale de synthèse du mémoire.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MatchingEngineBenchmark {

    private static final double RESIDENT_PRICE = 100.0;
    private static final int RESIDENT_QUANTITY = 10;

    private static final int INCOMING_QUANTITY_EXCEEDING_RESIDENT = RESIDENT_QUANTITY + 5;

    // Taille du niveau de prix pour le scénario CANCEL_WORST_CASE.
    // Valeur arbitraire mais représentative d'un niveau de prix
    // "populaire" -- suffisamment grande pour rendre le coût O(k) de
    // removeIf() visible dans la mesure face au bruit de fond.
    private static final int WORST_CASE_LEVEL_SIZE = 5_000;

    @State(Scope.Thread)
    public static class RestingOrderState {
        MatchingEngine engine;
        long nextOrderId;

        @Setup(Level.Invocation)
        public void setup() {
            // Level.Invocation (et non Level.Trial) : sans réinitialiser
            // engine avant CHAQUE invocation mesurée, le TreeMap et le
            // HashMap internes à OrderBook grossiraient au fil des
            // milliers d'appels JMH, faussant progressivement la mesure.
            engine = new MatchingEngine();
            nextOrderId = 1L;
        }
    }

    @State(Scope.Thread)
    public static class FullMatchState {
        MatchingEngine engine;
        long nextOrderId;

        @Setup(Level.Invocation)
        public void setup() {
            engine = new MatchingEngine();
            nextOrderId = 1L;

            OrderCommand residentSell = new OrderCommand(
                    CommandType.NEW, nextOrderId++, Side.SELL,
                    RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime());
            engine.process(residentSell);
        }
    }

    @State(Scope.Thread)
    public static class PartialMatchState {
        MatchingEngine engine;
        long nextOrderId;

        @Setup(Level.Invocation)
        public void setup() {
            engine = new MatchingEngine();
            nextOrderId = 1L;

            OrderCommand residentSell = new OrderCommand(
                    CommandType.NEW, nextOrderId++, Side.SELL,
                    RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime());
            engine.process(residentSell);
        }
    }

    @State(Scope.Thread)
    public static class CancelBestCaseState {
        MatchingEngine engine;
        long orderIdToCancel;

        @Setup(Level.Invocation)
        public void setup() {
            engine = new MatchingEngine();

            // Un seul ordre résident à ce niveau de prix : removeIf()
            // parcourt une file de taille 1 -- k=1, coût minimal de
            // localisation dans la file.
            long id = 1L;
            OrderCommand resident = new OrderCommand(
                    CommandType.NEW, id, Side.SELL,
                    RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime());
            engine.process(resident);

            orderIdToCancel = id;
        }
    }

    @State(Scope.Thread)
    public static class CancelWorstCaseState {
        MatchingEngine engine;
        long orderIdToCancel;

        @Setup(Level.Invocation)
        public void setup() {
            engine = new MatchingEngine();

            // WORST_CASE_LEVEL_SIZE ordres résidents au MÊME niveau de
            // prix (même side, même price -> même file ArrayDeque dans
            // OrderBook). On annule le DERNIER inséré : removeIf() doit
            // parcourir toute la file avant de le trouver -- k=50, révèle
            // le coût O(k) documenté à l'Exercice 1.
            long lastId = -1L;
            for (int i = 0; i < WORST_CASE_LEVEL_SIZE; i++) {
                long id = i + 1L;
                OrderCommand resident = new OrderCommand(
                        CommandType.NEW, id, Side.SELL,
                        RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime());
                engine.process(resident);
                lastId = id;
            }

            orderIdToCancel = lastId;
        }
    }

    @Benchmark
    public void restingOrder(RestingOrderState state, Blackhole blackhole) {
        OrderCommand incoming = new OrderCommand(
                CommandType.NEW, state.nextOrderId++, Side.BUY,
                RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime());

        blackhole.consume(state.engine.process(incoming));
    }

    @Benchmark
    public void fullMatch(FullMatchState state, Blackhole blackhole) {
        OrderCommand incoming = new OrderCommand(
                CommandType.NEW, state.nextOrderId++, Side.BUY,
                RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime());

        blackhole.consume(state.engine.process(incoming));
    }

    @Benchmark
    public void partialMatch(PartialMatchState state, Blackhole blackhole) {
        OrderCommand incoming = new OrderCommand(
                CommandType.NEW, state.nextOrderId++, Side.BUY,
                RESIDENT_PRICE, INCOMING_QUANTITY_EXCEEDING_RESIDENT, System.nanoTime());

        blackhole.consume(state.engine.process(incoming));
    }

    @Benchmark
    public void cancelBestCase(CancelBestCaseState state, Blackhole blackhole) {
        blackhole.consume(state.engine.process(
                new OrderCommand(CommandType.CANCEL, state.orderIdToCancel, Side.SELL,
                        RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime())));
    }

    @Benchmark
    public void cancelWorstCase(CancelWorstCaseState state, Blackhole blackhole) {
        blackhole.consume(state.engine.process(
                new OrderCommand(CommandType.CANCEL, state.orderIdToCancel, Side.SELL,
                        RESIDENT_PRICE, RESIDENT_QUANTITY, System.nanoTime())));
    }
}