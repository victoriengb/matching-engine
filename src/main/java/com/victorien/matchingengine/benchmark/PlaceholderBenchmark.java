package com.victorien.matchingengine.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark placeholder validant l'ensemble du pipeline de métrologie
 * (build -> benchmark -> comparaison -> gate) avant l'implémentation du
 * Jalon 0.
 *
 * À REMPLACER par le benchmark du matching engine réel au Jalon 0.
 *
 * Mode.SampleTime est utilisé car il produit une distribution de
 * percentiles (champ "scorePercentiles" dans la sortie JSON), contrairement
 * à Mode.AverageTime qui ne donne qu'une moyenne. C'est cette distribution
 * que le script scripts/compare-benchmarks.sh interroge.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class PlaceholderBenchmark {

    @Benchmark
    public long run() {
        // Opération triviale, sans signification métier : sert uniquement
        // à valider que la chaîne CI/CD fonctionne de bout en bout.
        long acc = 0;
        for (int i = 0; i < 1000; i++) {
            acc += i;
        }
        return acc;
    }
}
