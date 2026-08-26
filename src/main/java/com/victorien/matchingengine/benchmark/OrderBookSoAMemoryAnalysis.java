package com.victorien.matchingengine.benchmark;

import com.victorien.matchingengine.dod.OrderBookSoA;
import com.victorien.matchingengine.model.Side;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

public class OrderBookSoAMemoryAnalysis {

    private static final int CAPACITY = 1_000;
    private static final int PRICE_TICKS = 20_000;

    public static void main(String[] args) {
        // ═══ Mesure 1 : structure de l'objet OrderBookSoA seul ═══
        // Attendu : petit, puisque ce ne sont que des références vers
        // les tableaux -- pas l'empreinte réelle du carnet.
        System.out.println("=== ClassLayout : structure de l'objet seul ===");
        OrderBookSoA emptyBook = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        System.out.println(ClassLayout.parseInstance(emptyBook).toPrintable());

        // ═══ Mesure 2 : empreinte réelle, carnet vide ═══
        // GraphLayout suit les références vers tous les tableaux --
        // c'est ici que l'essentiel du poids apparaît.
        System.out.println("\n=== GraphLayout : empreinte totale, carnet VIDE ===");
        System.out.println(GraphLayout.parseInstance(emptyBook).toFootprint());

        // ═══ Mesure 3 : empreinte réelle, carnet à 1000 ordres ═══
        // Comparable directement à l'analyse OrderBook (Jalon 0) sur le
        // même nombre d'ordres résidents.
        OrderBookSoA populatedBook = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        for (long i = 0; i < CAPACITY; i++) {
            Side side = (i % 2 == 0) ? Side.BUY : Side.SELL;
            int price = 10_000 + (int) (i % 500); // étale les ordres sur plusieurs ticks
            populatedBook.insert(i, side, price, 10, System.nanoTime());
        }

        System.out.println("\n=== GraphLayout : empreinte totale, carnet à " + CAPACITY + " ordres ===");
        System.out.println(GraphLayout.parseInstance(populatedBook).toFootprint());

        // ═══ Comparaison au calcul théorique naïf ═══
        // Rappel Jalon 0 : 1000 x 56 octets (Order seul) = 56 000 octets,
        // sans compter TreeMap/ArrayDeque/HashMap.
        long naiveOrderOnly = 1000L * 56;
        System.out.println("\n=== Comparaison ===");
        System.out.println("Théorique naïf (1000 x 56 octets, Order seul, Jalon 0) : " + naiveOrderOnly + " octets");
    }
}