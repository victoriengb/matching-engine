package com.victorien.matchingengine.benchmark;

import com.victorien.matchingengine.ring.RingBuffer;
import com.victorien.matchingengine.ring.Sequence;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark comparatif Jalon 0 (ArrayBlockingQueue) vs Jalon 1 (RingBuffer)
 * du mécanisme de communication seul, indépendamment de
 * MatchingEngine.process() -- ce dernier étant inchangé entre les deux
 * jalons, la comparaison pertinente porte sur le coût du transport, pas
 * sur la logique de matching.
 *
 * Mode.SampleTime (cohérent avec MatchingEngineBenchmark) : on mesure la
 * distribution de latence d'un cycle publish+read, pas un débit agrégé
 * -- conformément au fil conducteur du mémoire (NFR de percentiles,
 * argumentation Gil Tene sur les pics masqués par une moyenne).
 *
 * MÉTHODOLOGIE -- cycle mono-thread plutôt que deux threads séparés :
 * Chaque @Benchmark exécute un aller-retour complet (écriture puis
 * lecture immédiate) sur UN SEUL thread, à l'image du calcul de latence
 * "put+take" classique en microbenchmarking de files. Ce choix est
 * cohérent avec la méthodologie déjà établie (état réinitialisé à
 * Level.Invocation, Blackhole pour éviter le DCE) mais a une limite
 * importante, documentée ci-dessous.
 *
 * LIMITE CONNUE -- ce que ce benchmark NE mesure PAS :
 * En mono-thread, ni ArrayBlockingQueue.take() ni le busy-spin du
 * RingBuffer n'ont jamais l'occasion d'attendre réellement : la donnée
 * est toujours déjà disponible au moment de la lecture. Ce protocole
 * mesure donc le coût intrinsèque des opérations (allocation de nœud
 * vs tableau pré-alloué, verrou vs échange de séquences) mais PAS le
 * bénéfice architectural central du Jalon 1 -- l'absence de changement
 * de contexte quand le consommateur doit réellement attendre le
 * producteur. Une mesure de ce second effet nécessiterait une
 * instrumentation cross-thread plus élaborée (horodatage applicatif
 * publié dans la donnée elle-même, lu par un thread consommateur
 * séparé), volontairement hors du périmètre de cette mesure pour
 * rester dans un délai raisonnable -- limitation assumée et à
 * documenter comme telle dans le mémoire.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class TransportLatencyBenchmark {

    private static final int CAPACITY = 1024;

    @State(Scope.Thread)
    public static class RingBufferState {
        RingBuffer<Long> ringBuffer;
        Sequence consumerSequence;
        long nextToRead;

        @Setup(Level.Invocation)
        public void setup() {
            ringBuffer = new RingBuffer<>(CAPACITY, 1);
            consumerSequence = new Sequence(-1L);
            ringBuffer.addGatingSequence(consumerSequence);
            nextToRead = 0L;
        }
    }

    @State(Scope.Thread)
    public static class ArrayBlockingQueueState {
        ArrayBlockingQueue<Long> queue;

        @Setup(Level.Invocation)
        public void setup() {
            queue = new ArrayBlockingQueue<>(CAPACITY);
        }
    }

    @Benchmark
    public void ringBufferRoundTrip(RingBufferState state, Blackhole blackhole) {
        long seq = state.ringBuffer.next();
        state.ringBuffer.set(seq, seq);
        state.ringBuffer.publish(seq);

        // Pas d'attente réelle attendue ici (mono-thread, cf. Javadoc de
        // classe) -- ce busy-spin ne fait jamais plus d'une itération en
        // pratique, il reste présent pour rester fidèle au protocole
        // réel de lecture du RingBuffer.
        while (state.ringBuffer.getCursor() < state.nextToRead) {
            // busy-spin
        }
        long value = state.ringBuffer.get(state.nextToRead);
        state.consumerSequence.set(state.nextToRead);
        state.nextToRead++;

        blackhole.consume(value);
    }

    @Benchmark
    public void arrayBlockingQueueRoundTrip(ArrayBlockingQueueState state, Blackhole blackhole)
            throws InterruptedException {
        state.queue.put(1L);
        blackhole.consume(state.queue.take());
    }
}