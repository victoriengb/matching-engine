package com.victorien.matchingengine.book;

import com.victorien.matchingengine.model.Order;
import com.victorien.matchingengine.model.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests à l'état ROUGE : ils ne passeront qu'une fois OrderBook
 * implémenté. Ne modifie pas le contenu de ces tests -- implémente le
 * code de production jusqu'à ce qu'ils passent au vert.
 */
class OrderBookTest {

    // -------------------------------------------------------------------------
    // Tests originaux
    // -------------------------------------------------------------------------

    @Test
    void bestBidShouldReturnHighestPrice() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 105.0, 10, 1001L));
        book.insert(new Order(3L, Side.BUY, 95.0, 10, 1002L));

        assertEquals(105.0, book.bestBid().price());
    }

    @Test
    void bestAskShouldReturnLowestPrice() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.SELL, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.SELL, 95.0, 10, 1001L));
        book.insert(new Order(3L, Side.SELL, 105.0, 10, 1002L));

        assertEquals(95.0, book.bestAsk().price());
    }

    @Test
    void ordersAtSamePriceShouldRespectFifoOrder() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 100.0, 10, 1001L));

        assertEquals(1L, book.bestBid().orderId());
    }

    @Test
    void emptyBookShouldReturnNullBestBidAndBestAsk() {
        OrderBook book = new OrderBook();

        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void removeShouldDeleteFromBothLevelsAndIndex() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));

        Order removed = book.remove(1L);

        assertNotNull(removed);
        assertNull(book.bestBid());
    }

    @Test
    void removeShouldReturnNullIfOrderDoesNotExist() {
        OrderBook book = new OrderBook();

        assertNull(book.remove(999L));
    }

    @Test
    void removingOrderNotAtFrontOfQueueShouldPreserveOthers() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 100.0, 10, 1001L));
        book.insert(new Order(3L, Side.BUY, 100.0, 10, 1002L));

        Order removed = book.remove(2L);

        assertNotNull(removed);
        assertEquals(1L, book.bestBid().orderId());
        assertNotNull(book.remove(3L));
    }

    @Test
    void removingPriceLevelEntirelyShouldNotLeaveEmptyLevelBehind() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 95.0, 10, 1001L));

        book.remove(1L);

        // Le niveau 100.0 est maintenant vide : bestBid() doit retomber
        // sur le niveau suivant (95.0), pas planter ni retourner une
        // file vide.
        assertEquals(95.0, book.bestBid().price());
    }

    @Test
    void removingAllOrdersAtSellLevelShouldNotLeaveEmptyLevelBehind() {
        // Symétrique du test BUY : garantit que remove() nettoie les niveaux
        // vides sur le côté SELL également, préservant l'invariant que
        // maxOrder() ne rencontrera jamais un Deque vide.
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.SELL, 100.0, 5, 1000L));
        book.insert(new Order(2L, Side.SELL, 110.0, 5, 1001L));

        book.remove(1L);

        assertEquals(110.0, book.bestAsk().price());
    }

    @Test
    void removingAllOrdersAtMultiOrderLevelShouldNotLeaveEmptyLevelBehind() {
        // Vérifie que le nettoyage du niveau se produit même quand plusieurs
        // ordres occupaient ce niveau et qu'ils sont tous retirés un à un.
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 100.0, 10, 1001L));
        book.insert(new Order(3L, Side.BUY, 90.0,  10, 1002L));

        book.remove(1L);
        book.remove(2L);  // vide le niveau 100.0

        // bestBid() doit tomber sur 90.0 sans jamais rencontrer un niveau vide.
        assertEquals(90.0, book.bestBid().price());
    }

    // -------------------------------------------------------------------------
    // Cas courants supplémentaires
    // -------------------------------------------------------------------------

    @Test
    void insertNullOrderShouldThrow() {
        OrderBook book = new OrderBook();

        assertThrows(IllegalArgumentException.class, () -> book.insert(null));
    }

    @Test
    void bestBidShouldNotMutateBook() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));

        Order first  = book.bestBid();
        Order second = book.bestBid();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.orderId(), second.orderId(),
                "bestBid() ne doit pas modifier le carnet");
    }

    @Test
    void bestAskShouldNotMutateBook() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.SELL, 50.0, 5, 2000L));

        Order first  = book.bestAsk();
        Order second = book.bestAsk();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.orderId(), second.orderId(),
                "bestAsk() ne doit pas modifier le carnet");
    }

    @Test
    void bestBidAfterPeekShouldStillReturnFifoOrder() {
        // Vérifie la priorité FIFO après plusieurs appels successifs à bestBid().
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 100.0, 10, 1001L));

        // Deux lectures successives : chacune doit rendre le même ordre (le plus ancien)
        assertEquals(1L, book.bestBid().orderId());
        assertEquals(1L, book.bestBid().orderId());
    }

    @Test
    void bestAskFifoOrderPreservedAcrossMultipleCalls() {
        OrderBook book = new OrderBook();
        book.insert(new Order(10L, Side.SELL, 99.0, 5, 500L));
        book.insert(new Order(11L, Side.SELL, 99.0, 5, 501L));

        assertEquals(10L, book.bestAsk().orderId());
        assertEquals(10L, book.bestAsk().orderId());
    }

    @Test
    void singleBuyOrderShouldBeItsOwnBestBid() {
        OrderBook book = new OrderBook();
        book.insert(new Order(42L, Side.BUY, 123.45, 1, 9999L));

        assertEquals(42L, book.bestBid().orderId());
    }

    @Test
    void singleSellOrderShouldBeItsOwnBestAsk() {
        OrderBook book = new OrderBook();
        book.insert(new Order(7L, Side.SELL, 77.0, 3, 1L));

        assertEquals(7L, book.bestAsk().orderId());
    }

    @Test
    void buyAndSellSidesShouldBeIndependent() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY,  90.0, 10, 1000L));
        book.insert(new Order(2L, Side.SELL, 100.0, 5, 1001L));

        assertEquals(90.0,  book.bestBid().price());
        assertEquals(100.0, book.bestAsk().price());
    }

    @Test
    void removingLastOrderMakesBookEmpty() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.SELL, 50.0, 10, 1000L));
        book.remove(1L);

        assertNull(book.bestAsk());
    }

    @Test
    void removeReturnedOrderShouldCarryCorrectFields() {
        OrderBook book = new OrderBook();
        Order original = new Order(5L, Side.BUY, 88.0, 20, 300L);
        book.insert(original);

        Order removed = book.remove(5L);

        assertNotNull(removed);
        assertEquals(5L,   removed.orderId());
        assertEquals(88.0, removed.price());
        assertEquals(20,   removed.remainingQuantity());
    }

    @Test
    void removingFrontOrderShouldExposeNextFifoOrder() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.SELL, 100.0, 5, 1000L));
        book.insert(new Order(2L, Side.SELL, 100.0, 5, 1001L));

        book.remove(1L);

        assertEquals(2L, book.bestAsk().orderId());
    }

    // -------------------------------------------------------------------------
    // Cas limites
    // -------------------------------------------------------------------------

    @Test
    void removeSameOrderTwiceShouldReturnNullOnSecondCall() {
        // Le premier remove retire l'ordre ; le second ne doit pas planter.
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));

        assertNotNull(book.remove(1L));
        assertNull(book.remove(1L));
    }

    @Test
    void removeBuyOrderWhenSellExistsAtSamePrice() {
        // Vérifie que remove() retire le bon ordre même quand les deux côtés
        // ont un niveau au même prix — scénario de croisement bid/ask.
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY,  100.0, 10, 1000L));
        book.insert(new Order(2L, Side.SELL, 100.0, 5,  1001L));

        Order result = book.remove(1L);

        assertNotNull(result);
        assertNull(book.bestBid(), "L'ordre BUY id=1 doit être retiré du carnet");
        assertNotNull(book.bestAsk(), "L'ordre SELL id=2 doit rester dans le carnet");
    }

    @Test
    void insertDuplicateOrderIdShouldThrow() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));

        assertThrows(IllegalArgumentException.class,
                () -> book.insert(new Order(1L, Side.BUY, 105.0, 5, 1001L)),
                "Insérer un ordre avec un orderId déjà présent doit lever une exception");
    }

    @Test
    void bestBidWithManyPriceLevelsShouldReturnGlobalMax() {
        OrderBook book = new OrderBook();
        for (int i = 1; i <= 100; i++) {
            book.insert(new Order(i, Side.BUY, i * 1.0, 1, i));
        }

        assertEquals(100.0, book.bestBid().price());
    }

    @Test
    void bestAskWithManyPriceLevelsShouldReturnGlobalMin() {
        OrderBook book = new OrderBook();
        for (int i = 1; i <= 100; i++) {
            book.insert(new Order(i, Side.SELL, i * 1.0, 1, i));
        }

        assertEquals(1.0, book.bestAsk().price());
    }

    @Test
    void bestBidShouldNotReturnOrderFromSellSide() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.SELL, 200.0, 10, 1000L));

        // Aucun ordre BUY : bestBid doit être null même si le SELL est rempli.
        assertNull(book.bestBid());
    }

    @Test
    void bestAskShouldNotReturnOrderFromBuySide() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 50.0, 10, 1000L));

        assertNull(book.bestAsk());
    }

    @Test
    void bestBidRemainsConsistentAfterInterleavedInsertsAndRemoves() {
        OrderBook book = new OrderBook();
        book.insert(new Order(1L, Side.BUY, 100.0, 10, 1000L));
        book.insert(new Order(2L, Side.BUY, 110.0, 10, 1001L));
        book.insert(new Order(3L, Side.BUY, 90.0,  10, 1002L));

        book.remove(2L);   // retire le meilleur prix

        assertEquals(100.0, book.bestBid().price());

        book.remove(1L);   // retire le nouveau meilleur

        assertEquals(90.0, book.bestBid().price());
    }
}
