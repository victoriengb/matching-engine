package com.victorien.matchingengine.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests à l'état ROUGE : ils ne passeront qu'une fois fill() et cancel()
 * implémentés dans Order. Ne modifie pas le contenu de ces tests --
 * implémente le code de production jusqu'à ce qu'ils passent au vert.
 */
class OrderTest {

    // -------------------------------------------------------------------------
    // Tests originaux
    // -------------------------------------------------------------------------

    @Test
    void fillingExactRemainingQuantityShouldSetStatusFilled() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        order.fill(10);

        assertEquals(0, order.remainingQuantity());
        assertEquals(Order.Status.FILLED, order.status());
    }

    @Test
    void fillingPartialQuantityShouldSetStatusPartiallyFilled() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        order.fill(4);

        assertEquals(6, order.remainingQuantity());
        assertEquals(Order.Status.PARTIALLY_FILLED, order.status());
    }

    @Test
    void successivePartialFillsShouldAccumulateCorrectly() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        order.fill(4);
        order.fill(6);

        assertEquals(0, order.remainingQuantity());
        assertEquals(Order.Status.FILLED, order.status());
    }

    @Test
    void cancelShouldSetStatusCancelled() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        order.cancel();

        assertEquals(Order.Status.CANCELLED, order.status());
    }

    // -------------------------------------------------------------------------
    // Invariants d'état initial
    // -------------------------------------------------------------------------

    @Test
    void newOrderShouldHaveStatusResting() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        assertEquals(Order.Status.RESTING, order.status());
    }

    @Test
    void newOrderRemainingQuantityShouldEqualOriginalQuantity() {
        Order order = new Order(1L, Side.SELL, 50.0, 7, 2000L);

        assertEquals(7, order.remainingQuantity());
        assertEquals(7, order.originalQuantity());
    }

    // -------------------------------------------------------------------------
    // Cas limites de fill()
    // -------------------------------------------------------------------------

    /**
     * BUG: fill() ignore silencieusement un excès de quantité au lieu de
     * lever une exception. remainingQuantity ne doit jamais devenir négatif.
     * Ce test échouera tant que fill() ne lève pas IllegalArgumentException.
     */
    @Test
    void fillExceedingRemainingQuantityShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        assertThrows(IllegalArgumentException.class, () -> order.fill(11),
            "fill() doit lever une exception quand filledQuantity > remainingQuantity");
    }

    /**
     * Corollaire du test précédent : remainingQuantity ne doit pas changer
     * si fill() détecte une tentative d'excès (état cohérent après l'erreur).
     */
    @Test
    void fillExceedingRemainingQuantityShouldLeaveStateUnchanged() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);
        order.fill(6); // remainingQuantity = 4

        try {
            order.fill(5); // excède les 4 restants
        } catch (IllegalArgumentException ignored) { }

        assertEquals(4, order.remainingQuantity());
        assertEquals(Order.Status.PARTIALLY_FILLED, order.status());
    }

    @Test
    void fillWithZeroQuantityShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        assertThrows(IllegalArgumentException.class, () -> order.fill(0));
    }

    @Test
    void fillWithNegativeQuantityShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        assertThrows(IllegalArgumentException.class, () -> order.fill(-3));
    }

    /**
     * BUG: les assertions Java sont désactivées en production (-ea non activé
     * par défaut). fill() sur un ordre FILLED est actuellement silencieux.
     * Ce test échouera tant que fill() ne lève pas IllegalStateException.
     */
    @Test
    void fillOnFilledOrderShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);
        order.fill(10); // order is now FILLED

        assertThrows(IllegalStateException.class, () -> order.fill(1),
            "fill() doit lever une exception sur un ordre déjà FILLED");
    }

    /**
     * BUG: même problème que fillOnFilledOrderShouldThrow — les asserts
     * sont no-ops sans -ea.
     */
    @Test
    void fillOnCancelledOrderShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);
        order.cancel();

        assertThrows(IllegalStateException.class, () -> order.fill(1),
            "fill() doit lever une exception sur un ordre CANCELLED");
    }

    // -------------------------------------------------------------------------
    // Cas limites de cancel()
    // -------------------------------------------------------------------------

    /**
     * BUG: cancel() sur un ordre FILLED ne lève pas d'exception (assert
     * désactivé). Annuler un ordre exécuté n'a aucun sens métier.
     * Ce test échouera tant que cancel() ne lève pas IllegalStateException.
     */
    @Test
    void cancelOnFilledOrderShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);
        order.fill(10);

        assertThrows(IllegalStateException.class, () -> order.cancel(),
            "cancel() doit lever une exception sur un ordre FILLED");
    }

    /**
     * BUG: cancel() deux fois de suite — l'assert est no-op sans -ea.
     * Un double cancel doit être une erreur, pas idempotent (une annulation
     * confirmée est un fait passé ; en rejeter une seconde évite les
     * traitements en double côté downstream).
     */
    @Test
    void cancelOnAlreadyCancelledOrderShouldThrow() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);
        order.cancel();

        assertThrows(IllegalStateException.class, () -> order.cancel(),
            "cancel() doit lever une exception sur un ordre déjà CANCELLED");
    }

    @Test
    void cancelAfterPartialFillShouldSetStatusCancelled() {
        Order order = new Order(1L, Side.SELL, 200.0, 20, 3000L);
        order.fill(5); // PARTIALLY_FILLED, remaining = 15

        order.cancel();

        assertEquals(Order.Status.CANCELLED, order.status());
        assertEquals(15, order.remainingQuantity()); // remaining n'est pas remis à zéro
    }

    // -------------------------------------------------------------------------
    // Transitions de statut
    // -------------------------------------------------------------------------

    @Test
    void singleFillOf1OnQuantity1ShouldGoDirectlyToFilled() {
        Order order = new Order(42L, Side.SELL, 9.99, 1, 999L);

        order.fill(1);

        assertEquals(Order.Status.FILLED, order.status());
        assertEquals(0, order.remainingQuantity());
    }

    @Test
    void partialFillShouldNeverProduceFilledStatusUntilFullyConsumed() {
        Order order = new Order(1L, Side.BUY, 100.0, 10, 1000L);

        order.fill(9);

        assertEquals(Order.Status.PARTIALLY_FILLED, order.status());
        assertEquals(1, order.remainingQuantity());

        order.fill(1);

        assertEquals(Order.Status.FILLED, order.status());
        assertEquals(0, order.remainingQuantity());
    }
}
