package com.victorien.matchingengine.dod;

import com.victorien.matchingengine.model.Side;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests organisés en couches de dépendance croissante.
 *
 * Couches 0-3 : insert() (vérifications, Groupe 1, Groupe 2, Groupe 3) --
 * déjà validées, inchangées.
 *
 * Couches 4-5 (NOUVELLES) : bestBidOrderId/bestAskOrderId, et
 * contains/priceOf/remainingQuantityOf -- méthodes déjà implémentées
 * mais jusqu'ici seulement couvertes indirectement.
 *
 * Couche 6 (NOUVELLE, à l'état ROUGE) : remove(), pas encore
 * implémentée. Organisée en sous-groupes symétriques à insert() :
 * Groupe 4 (recyclage), Groupe 2 (dé-chaînage, les 3 cas milieu/tête/
 * queue), Groupe 3 (sortie des niveaux actifs), Groupe 1 + index externe.
 */
class OrderBookSoATest {

    private static final int CAPACITY = 16;
    private static final int PRICE_TICKS = 20_000;
    private static final int EMPTY = OrderBookSoA.emptySentinel();

    // ═══ Couche 0 : vérifications préalables ═══

    @Test
    void insertShouldNotThrowGivenValidArguments() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertDoesNotThrow(() -> book.insert(1L, Side.BUY, 10_000, 5, 1000L));
    }

    @Test
    void duplicateOrderIdShouldThrowAndChangeNothing() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int freeBefore = book.freeSlotCount();

        assertThrows(IllegalArgumentException.class,
                () -> book.insert(1L, Side.SELL, 20_000, 3, 1001L));

        assertEquals(freeBefore, book.freeSlotCount(),
                "Une tentative de doublon ne doit consommer aucun slot");
    }

    @Test
    void insertBeyondCapacityShouldThrowAndLeaveExistingOrdersIntact() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        for (int i = 0; i < CAPACITY; i++) {
            book.insert(i, Side.BUY, 10_000 + i, 1, 1000L + i);
        }

        assertThrows(IllegalStateException.class,
                () -> book.insert(999L, Side.BUY, 10_000, 1, 2000L));

        assertEquals(EMPTY, book.slotOf(999L),
                "Un insert() qui échoue ne doit pas laisser de trace dans le mapping");
        for (int i = 0; i < CAPACITY; i++) {
            int slot = book.slotOf(i);
            assertNotEquals(EMPTY, slot);
            assertEquals(10_000 + i, book.priceAtSlot(slot));
        }
    }

    // ═══ Couche 1 : Groupe 1 + index externe ═══

    @Test
    void insertShouldConsumeOneFreeSlot() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        int freeBefore = book.freeSlotCount();

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertEquals(freeBefore - 1, book.freeSlotCount());
    }

    @Test
    void insertShouldRegisterOrderIdInSlotIndex() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertNotEquals(EMPTY, book.slotOf(1L));
    }

    @Test
    void unknownOrderIdShouldMapToEmptySentinel() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertEquals(EMPTY, book.slotOf(999L));
    }

    @Test
    void insertShouldWriteCorrectDataAtAllocatedSlot() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int slot = book.slotOf(1L);

        assertEquals(1L, book.orderIdAtSlot(slot));
        assertEquals(10_000, book.priceAtSlot(slot));
        assertEquals(5, book.remainingQuantityAtSlot(slot));
        assertEquals((byte) 0, book.sideAtSlot(slot), "BUY doit être encodé 0");
        assertEquals(OrderBookSoA.statusRestingValue(), book.statusAtSlot(slot),
                "Un ordre fraîchement inséré doit être RESTING");
    }

    @Test
    void sellSideShouldBeEncodedDistinctlyFromBuy() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.SELL, 10_000, 5, 1000L);
        int slot = book.slotOf(1L);

        assertEquals((byte) 1, book.sideAtSlot(slot), "SELL doit être encodé 1");
    }

    @Test
    void insertingUpToCapacityShouldPlaceEachOrderAtDistinctSlotWithoutOverwriting() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        for (int i = 0; i < CAPACITY; i++) {
            book.insert(i, Side.BUY, 10_000 + i, 1, 1000L + i);
        }

        Set<Integer> slotsUsed = new HashSet<>();
        for (int i = 0; i < CAPACITY; i++) {
            int slot = book.slotOf(i);
            assertTrue(slotsUsed.add(slot),
                    "Le slot " + slot + " est réutilisé par plusieurs orderId simultanément");
            assertEquals(i, book.orderIdAtSlot(slot));
        }
        assertEquals(0, book.freeSlotCount());
    }

    // ═══ Couche 2a : Groupe 2, Cas A -- tick nouveau ═══

    @Test
    void firstOrderAtTickShouldBecomeHeadAndTail() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int slot = book.slotOf(1L);

        assertEquals(slot, book.buyLevelHeadAt(10_000));
        assertEquals(slot, book.buyLevelTailAt(10_000),
                "Le premier ordre d'un tick doit être à la fois head ET tail");
    }

    @Test
    void firstOrderAtTickShouldHaveNoNeighbors() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int slot = book.slotOf(1L);

        assertEquals(EMPTY, book.nextOrderIndexAtSlot(slot));
        assertEquals(EMPTY, book.previousOrderIndexAtSlot(slot));
    }

    @Test
    void emptyTickShouldHaveSentinelHeadAndTail() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertEquals(EMPTY, book.buyLevelHeadAt(10_000));
        assertEquals(EMPTY, book.buyLevelTailAt(10_000));
    }

    // ═══ Couche 2b : Groupe 2, Cas B -- tick déjà occupé ═══

    @Test
    void secondOrderAtSameTickShouldExtendTailNotHead() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int firstSlot = book.slotOf(1L);

        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        int secondSlot = book.slotOf(2L);

        assertEquals(firstSlot, book.buyLevelHeadAt(10_000),
                "La tête ne doit pas bouger : le premier arrivé reste prioritaire");
        assertEquals(secondSlot, book.buyLevelTailAt(10_000));
    }

    @Test
    void secondOrderAtSameTickShouldBeDoublyLinkedToFirst() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int firstSlot = book.slotOf(1L);

        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        int secondSlot = book.slotOf(2L);

        assertEquals(secondSlot, book.nextOrderIndexAtSlot(firstSlot),
                "Le premier doit chaîner vers le second (next)");
        assertEquals(firstSlot, book.previousOrderIndexAtSlot(secondSlot),
                "Le second doit chaîner vers le premier (previous)");
        assertEquals(EMPTY, book.nextOrderIndexAtSlot(secondSlot),
                "Le second, étant le dernier, ne doit avoir aucun next");
    }

    @Test
    void thirdOrderAtSameTickShouldExtendChainCorrectly() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        int secondSlot = book.slotOf(2L);

        book.insert(3L, Side.BUY, 10_000, 5, 1002L);
        int thirdSlot = book.slotOf(3L);

        assertEquals(thirdSlot, book.buyLevelTailAt(10_000));
        assertEquals(thirdSlot, book.nextOrderIndexAtSlot(secondSlot));
        assertEquals(secondSlot, book.previousOrderIndexAtSlot(thirdSlot));
        assertEquals(EMPTY, book.nextOrderIndexAtSlot(thirdSlot));
    }

    // ═══ Couche 3 : Groupe 3 -- entrée dans les niveaux actifs ═══

    @Test
    void firstInsertAtNewTickShouldRegisterActiveTick() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertEquals(1, book.activeBuyLevelCount());
        assertEquals(10_000, book.activeBuyTickAt(0));
    }

    @Test
    void secondInsertAtSameTickShouldNotDuplicateActiveTick() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        book.insert(2L, Side.BUY, 10_000, 5, 1001L);

        assertEquals(1, book.activeBuyLevelCount(),
                "Un second ordre au même tick ne doit pas créer un second niveau actif");
    }

    @Test
    void activeBuyTicksShouldStaySortedDescending() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_500, 5, 1001L);
        book.insert(3L, Side.BUY, 9_500, 5, 1002L);

        assertEquals(10_500, book.activeBuyTickAt(0));
        assertEquals(10_000, book.activeBuyTickAt(1));
        assertEquals(9_500, book.activeBuyTickAt(2));
    }

    @Test
    void activeSellTicksShouldStaySortedAscending() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.SELL, 10_000, 5, 1000L);
        book.insert(2L, Side.SELL, 9_500, 5, 1001L);
        book.insert(3L, Side.SELL, 10_500, 5, 1002L);

        assertEquals(9_500, book.activeSellTickAt(0));
        assertEquals(10_000, book.activeSellTickAt(1));
        assertEquals(10_500, book.activeSellTickAt(2));
    }

    @Test
    void buyAndSellActiveTicksShouldBeIndependent() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertEquals(1, book.activeBuyLevelCount());
        assertEquals(0, book.activeSellLevelCount());
    }

    @Test
    void insertingAtSameTickBothSidesShouldStayIndependentInGroup2() {
        // Cas non testé jusqu'ici : un même tick numérique peut porter
        // un carnet BUY et un carnet SELL simultanément (rien ne
        // l'interdit fonctionnellement, même si en pratique un tel
        // marché serait déjà croisé). Vérifie que buyLevelHead/Tail et
        // sellLevelHead/Tail au même tick ne s'écrasent pas entre eux.
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.SELL, 10_000, 5, 1001L);

        int buySlot = book.slotOf(1L);
        int sellSlot = book.slotOf(2L);

        assertEquals(buySlot, book.buyLevelHeadAt(10_000));
        assertEquals(sellSlot, book.sellLevelHeadAt(10_000));
    }

    // ═══ Couche 4 (NOUVELLE) : bestBidOrderId / bestAskOrderId ═══
    // Dépend de : Couches 2 et 3 (chaînage + niveaux actifs déjà corrects)

    @Test
    void emptyBookShouldReturnSentinelForBestBidAndAsk() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertEquals(-1L, book.bestBidOrderId());
        assertEquals(-1L, book.bestAskOrderId());
    }

    @Test
    void bestBidShouldReturnHighestPrice() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_500, 5, 1001L);
        book.insert(3L, Side.BUY, 9_500, 5, 1002L);

        assertEquals(2L, book.bestBidOrderId());
    }

    @Test
    void bestAskShouldReturnLowestPrice() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.SELL, 10_000, 5, 1000L);
        book.insert(2L, Side.SELL, 9_500, 5, 1001L);
        book.insert(3L, Side.SELL, 10_500, 5, 1002L);

        assertEquals(2L, book.bestAskOrderId());
    }

    @Test
    void ordersAtSameTickShouldRespectFifoOrderForBestBid() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);

        assertEquals(1L, book.bestBidOrderId(),
                "Le premier arrivé au même tick doit rester prioritaire");
    }

    @Test
    void bestBidShouldUpdateWhenBetterTickIsInsertedAfterward() {
        // Vérifie que bestBidOrderId() reflète l'insertion la plus
        // récente, pas seulement le tout premier tick jamais créé --
        // couvre le cas où le meilleur tick change de position dans
        // activeBuyTicks après un nouvel insert.
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        assertEquals(1L, book.bestBidOrderId());

        book.insert(2L, Side.BUY, 10_500, 5, 1001L);
        assertEquals(2L, book.bestBidOrderId(),
                "Un meilleur prix inséré ensuite doit devenir le nouveau meilleur bid");
    }

    @Test
    void bestBidAndBestAskShouldBeIndependentOfEachOther() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertEquals(1L, book.bestBidOrderId());
        assertEquals(-1L, book.bestAskOrderId(),
                "Un carnet SELL vide doit rester -1 même si BUY est peuplé");
    }

    // ═══ Couche 5 (NOUVELLE) : contains / priceOf / remainingQuantityOf ═══
    // Dépend de : Couche 1 (slotOf correct)

    @Test
    void containsShouldReflectPresence() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertTrue(book.contains(1L));
        assertFalse(book.contains(999L));
    }

    @Test
    void priceOfShouldReturnCorrectValue() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertEquals(10_000, book.priceOf(1L));
    }

    @Test
    void remainingQuantityOfShouldReturnCorrectValue() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        assertEquals(5, book.remainingQuantityOf(1L));
    }

    @Test
    void priceOfUnknownOrderShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertThrows(IllegalArgumentException.class, () -> book.priceOf(999L));
    }

    @Test
    void remainingQuantityOfUnknownOrderShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertThrows(IllegalArgumentException.class, () -> book.remainingQuantityOf(999L));
    }

    // ═══ Couche 6 (NOUVELLE, ROUGE) : remove() ═══
    // remove() n'est pas encore implémentée -- ces tests sont au ROUGE
    // et servent de spécification, symétrique à insert() sur les mêmes
    // 4 groupes (dans l'ordre inverse : dé-chaîner avant de recycler).

    @Test
    void removeShouldReturnFalseForUnknownOrder() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertFalse(book.remove(999L));
    }

    @Test
    void removeShouldReturnTrueAndClearSlotIndexEntry() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        boolean removed = book.remove(1L);

        assertTrue(removed);
        assertEquals(EMPTY, book.slotOf(1L));
        assertFalse(book.contains(1L));
    }

    @Test
    void removeUnknownOrderShouldChangeNothing() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int freeBefore = book.freeSlotCount();

        book.remove(999L);

        assertEquals(freeBefore, book.freeSlotCount());
        assertTrue(book.contains(1L));
    }

    @Test
    void removeShouldReleaseSlotBackToFreePile() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        int freeAfterInsert = book.freeSlotCount();

        book.remove(1L);

        assertEquals(freeAfterInsert + 1, book.freeSlotCount());
    }

    @Test
    void slotShouldBeReusableAfterRemoval() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        for (int i = 0; i < CAPACITY; i++) {
            book.insert(i, Side.BUY, 10_000 + i, 1, 1000L + i);
        }
        for (int i = 0; i < CAPACITY; i++) {
            book.remove(i);
        }

        assertDoesNotThrow(() -> book.insert(999L, Side.BUY, 10_000, 1, 2000L));
        assertEquals(CAPACITY - 1, book.freeSlotCount());
    }

    // --- Groupe 2 : les 3 cas de dé-chaînage (tête / milieu / queue) ---

    @Test
    void removingOnlyOrderAtTickShouldClearHeadTail() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);

        book.remove(1L);

        assertEquals(EMPTY, book.buyLevelHeadAt(10_000));
        assertEquals(EMPTY, book.buyLevelTailAt(10_000));
    }

    @Test
    void removingHeadOfChainShouldPromoteNextToHead() {
        // Cas TÊTE -- symétrique du Cas A de insert()
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        int secondSlot = book.slotOf(2L);

        book.remove(1L);

        assertEquals(secondSlot, book.buyLevelHeadAt(10_000));
        assertEquals(EMPTY, book.previousOrderIndexAtSlot(secondSlot),
                "Le nouveau head ne doit plus avoir de previous");
    }

    @Test
    void removingTailOfChainShouldPromotePreviousToTail() {
        // Cas QUEUE -- celui qui a motivé l'ajout de previousOrderIndex
        // (impossible à résoudre en O(1) sans lui)
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        int firstSlot = book.slotOf(1L);

        book.remove(2L);

        assertEquals(firstSlot, book.buyLevelTailAt(10_000));
        assertEquals(EMPTY, book.nextOrderIndexAtSlot(firstSlot),
                "Le nouveau tail ne doit plus avoir de next");
    }

    @Test
    void removingMiddleOfChainShouldPreserveNeighborsLink() {
        // Cas MILIEU -- A -> B -> C, on retire B
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        book.insert(3L, Side.BUY, 10_000, 5, 1002L);
        int firstSlot = book.slotOf(1L);
        int thirdSlot = book.slotOf(3L);

        book.remove(2L);

        assertEquals(thirdSlot, book.nextOrderIndexAtSlot(firstSlot),
                "A doit désormais chaîner directement vers C");
        assertEquals(firstSlot, book.previousOrderIndexAtSlot(thirdSlot),
                "C doit désormais chaîner directement vers A");
        assertTrue(book.contains(1L));
        assertTrue(book.contains(3L));
    }

    // --- Groupe 3 : sortie des niveaux actifs ---

    @Test
    void removingLastOrderAtTickShouldClearActiveTickList() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 9_000, 5, 1001L);

        book.remove(1L);

        assertEquals(1, book.activeBuyLevelCount());
        assertEquals(9_000, book.activeBuyTickAt(0));
        assertEquals(2L, book.bestBidOrderId(),
                "Après retrait du meilleur tick, le suivant doit prendre sa place");
    }

    @Test
    void removingOneOrderAtTickWithOthersRemainingShouldNotAffectActiveTicks() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);

        book.remove(1L);

        assertEquals(1, book.activeBuyLevelCount(),
                "Le tick reste actif tant qu'il reste au moins un ordre");
        assertEquals(10_000, book.activeBuyTickAt(0));
    }

    @Test
    void removingMiddleActiveTickShouldKeepOthersSorted() {
        // Vérifie le décalage à gauche dans un tableau à 3+ éléments,
        // pas seulement le cas trivial à 1 élément.
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_500, 5, 1000L);
        book.insert(2L, Side.BUY, 10_000, 5, 1001L);
        book.insert(3L, Side.BUY, 9_500, 5, 1002L);

        book.remove(2L); // vide le tick 10_000, le tick du milieu

        assertEquals(2, book.activeBuyLevelCount());
        assertEquals(10_500, book.activeBuyTickAt(0));
        assertEquals(9_500, book.activeBuyTickAt(1));
    }

    // --- Scénario intégrateur ---

    @Test
    void insertRemoveInsertShouldProduceConsistentState() {
        // Enchaîne insert/remove/insert pour vérifier qu'un slot recyclé
        // ne laisse aucun résidu de son ancien usage (next/previous
        // périmés, ancien tick fantôme dans activeBuyTicks, etc.).
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 5, 1000L);
        book.remove(1L);

        book.insert(2L, Side.BUY, 9_000, 3, 2000L);

        assertEquals(2L, book.bestBidOrderId());
        assertEquals(1, book.activeBuyLevelCount());
        assertEquals(9_000, book.activeBuyTickAt(0));
        int slot = book.slotOf(2L);
        assertEquals(EMPTY, book.nextOrderIndexAtSlot(slot));
        assertEquals(EMPTY, book.previousOrderIndexAtSlot(slot));
    }

    // ═══ Couche 7 (NOUVELLE) : fill() / isFilled() ═══
// Dépend de : insert() (Couche 1) pour préparer l'ordre à remplir.
// Méthodes ajoutées à OrderBookSoA pour permettre à un futur
// MatchingEngineSoA de décrémenter un ordre résident sans le retirer
// du carnet -- remove() reste la décision de l'appelant.

    @Test
    void fillShouldNotThrowGivenValidArguments() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        assertDoesNotThrow(() -> book.fill(1L, 4));
    }

    @Test
    void fillUnknownOrderShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertThrows(IllegalArgumentException.class, () -> book.fill(999L, 1));
    }

    @Test
    void fillWithZeroQuantityShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        assertThrows(IllegalArgumentException.class, () -> book.fill(1L, 0));
    }

    @Test
    void fillWithNegativeQuantityShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        assertThrows(IllegalArgumentException.class, () -> book.fill(1L, -3));
    }

    @Test
    void fillExceedingRemainingQuantityShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        assertThrows(IllegalArgumentException.class, () -> book.fill(1L, 11));
    }

    @Test
    void fillEqualToRemainingQuantityShouldBeAccepted() {
        // Cas limite : décrémenter exactement la quantité restante doit
        // réussir (pas une exception), et amener remainingQuantity à 0.
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        assertDoesNotThrow(() -> book.fill(1L, 10));
        assertEquals(0, book.remainingQuantityOf(1L));
    }

    @Test
    void partialFillShouldDecrementRemainingQuantity() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        book.fill(1L, 4);

        assertEquals(6, book.remainingQuantityOf(1L));
    }

    @Test
    void partialFillShouldSetStatusToPartiallyFilledNotFilled() {
        // C'est le test qui aurait révélé le bug (absence de else dans
        // updateStatusAfterFill) : sans lui, un fill partiel semblait
        // toujours produire FILLED.
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        book.fill(1L, 4);

        assertFalse(book.isFilled(1L),
                "Un ordre partiellement rempli ne doit PAS être considéré FILLED");
        assertEquals(6, book.remainingQuantityOf(1L));
    }

    @Test
    void fullFillShouldSetStatusToFilled() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        book.fill(1L, 10);

        assertTrue(book.isFilled(1L));
    }

    @Test
    void successivePartialFillsShouldAccumulateCorrectly() {
        // Vérifie que plusieurs fill() successifs (comme lors de matchs
        // partiels répétés contre plusieurs makers) s'accumulent bien,
        // et que le statut ne passe à FILLED qu'au tout dernier appel.
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        book.fill(1L, 4);
        assertFalse(book.isFilled(1L));
        assertEquals(6, book.remainingQuantityOf(1L));

        book.fill(1L, 6);
        assertTrue(book.isFilled(1L));
        assertEquals(0, book.remainingQuantityOf(1L));
    }

    @Test
    void fillOnAlreadyFilledOrderShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);
        book.fill(1L, 10); // épuise complètement l'ordre

        assertThrows(IllegalStateException.class, () -> book.fill(1L, 1));
    }

    @Test
    void isFilledOnUnknownOrderShouldThrow() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);

        assertThrows(IllegalArgumentException.class, () -> book.isFilled(999L));
    }

    @Test
    void freshlyInsertedOrderShouldNotBeFilled() {
        OrderBookSoA book = new OrderBookSoA(CAPACITY, PRICE_TICKS);
        book.insert(1L, Side.BUY, 10_000, 10, 1000L);

        assertFalse(book.isFilled(1L));
    }
}