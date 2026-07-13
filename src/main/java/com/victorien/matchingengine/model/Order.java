package com.victorien.matchingengine.model;

/**
 * État d'un ordre tel que conservé dans le carnet d'ordres.
 *
 * Contrairement à {@link OrderCommand} et {@link Trade} (instantanés
 * immuables), Order porte un état qui évolue dans le temps :
 * remainingQuantity diminue au fil des exécutions partielles, et status
 * transite de RESTING vers PARTIALLY_FILLED puis FILLED, ou vers
 * CANCELLED sur annulation.
 */
public class Order {

    public enum Status {
        RESTING,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED
    }

    private final long orderId;
    private final Side side;
    private final double price;
    private final int originalQuantity;
    private int remainingQuantity;
    private final long createdAt;
    private Status status;

    public Order(long orderId, Side side, double price, int originalQuantity, long createdAt) {
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.originalQuantity = originalQuantity;
        this.remainingQuantity = originalQuantity;
        this.createdAt = createdAt;
        this.status = Status.RESTING;
    }

    public long orderId() {
        return orderId;
    }

    public Side side() {
        return side;
    }

    public double price() {
        return price;
    }

    public int originalQuantity() {
        return originalQuantity;
    }

    public int remainingQuantity() {
        return remainingQuantity;
    }

    public long createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    /**
     * Exécute partiellement ou totalement l'ordre.
     *
     * Invariants à respecter :
     *   - remainingQuantity ne peut jamais devenir négatif
     *   - si filledQuantity correspond exactement à remainingQuantity
     *     (avant décrément), le statut devient FILLED
     *   - sinon, le statut devient PARTIALLY_FILLED
     *   - un ordre déjà FILLED ou CANCELLED ne devrait pas pouvoir être
     *     rempli à nouveau (réfléchis à la façon de le garantir :
     *     exception ? assertion ? silently ignore ? — choisis et justifie
     *     ton choix, c'est une vraie décision de conception)
     *
     * @param filledQuantity quantité exécutée lors de ce match, doit être
     *                       inférieure ou égale à remainingQuantity
     */
    public void fill(int filledQuantity) {
        // The order of the exceptions matters. We check in this order:  order is not CANCELLED > order is not FILLED > filledQuantity positive > filledQuantity inferior to remainingQuantity
        if (this.status == Status.CANCELLED)
            throw new IllegalStateException("The order has been cancelled");
        if (this.status == Status.FILLED)
            throw new IllegalStateException("The order has been filled");
        if (filledQuantity <= 0)
            throw new IllegalArgumentException("Cannot ask for a negative or a zero quantity of an asset");
        if(filledQuantity > this.remainingQuantity)
            throw new IllegalArgumentException("Cannot ask for more than remaining quantity of an asset");

        this.remainingQuantity -= filledQuantity;
        this.status = this.remainingQuantity == 0 ? Status.FILLED : Status.PARTIALLY_FILLED;
    }

    /**
     * Annule l'ordre.
     *
     * Invariant à respecter : un ordre déjà FILLED ne devrait pas pouvoir
     * être annulé (réfléchis à ce que ça signifierait métier de
     * l'autoriser, et à ce qui devrait se passer pour un ordre déjà
     * CANCELLED — idempotence ou erreur ?).
     */
    public void cancel() {
        if (this.status == Status.CANCELLED)
            throw new IllegalStateException("Order already cancelled");

        if (this.status == Status.FILLED)
            throw new IllegalStateException("Order already filled");

        this.status = Status.CANCELLED;
    }
}
