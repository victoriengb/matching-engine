package com.victorien.matchingengine.engine;

import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.OrderCommand.CommandType;
import com.victorien.matchingengine.model.Side;
import com.victorien.matchingengine.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEngineTest {

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
    }

    // --- Cas NEW sans match ---

    @Test
    void newOrderWithNoCounterpartyShouldProduceNoTrade() {
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 1L, Side.BUY, 100.0, 10, 1000L));

        assertTrue(trades.isEmpty());
    }

    // --- Cas NEW avec match total ---

    @Test
    void exactMatchShouldProduceOneTrade() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.BUY, 100.0, 10, 1001L));

        assertEquals(1, trades.size());
        assertEquals(100.0, trades.getFirst().executionPrice());
        assertEquals(10,    trades.getFirst().quantity());
    }

    @Test
    void executionPriceShouldBeResidentOrderPrice() {
        // Résident SELL à 100, agresseur BUY à 105 — exécution à 100
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.BUY, 105.0, 10, 1001L));

        assertEquals(100.0, trades.getFirst().executionPrice());
    }

    // --- Cas NEW avec match partiel ---

    @Test
    void partialMatchShouldProduceOneTradeWithPartialQuantity() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.BUY, 100.0, 10, 1001L));

        assertEquals(1, trades.size());
        assertEquals(5, trades.getFirst().quantity());
    }

    @Test
    void incomingOrderMatchingMultipleResidentsShouldProduceMultipleTrades() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 10, 1002L));

        assertEquals(2, trades.size());
        assertEquals(5, trades.get(0).quantity());
        assertEquals(5, trades.get(1).quantity());
    }

    // --- Cas CANCEL ---

    @Test
    void cancelShouldRemoveOrderFromBook() {
        engine.process(new OrderCommand(CommandType.NEW,    1L, Side.SELL, 100.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.CANCEL, 1L, Side.SELL, 100.0, 0,  1001L));

        // Après annulation, un BUY au même prix ne doit plus trouver de contrepartie
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.BUY, 100.0, 10, 1002L));

        assertTrue(trades.isEmpty());
    }

    // --- Cas MODIFY ---

    @Test
    void modifyShouldUpdatePriceAndTriggerMatchIfCrossing() {
        engine.process(new OrderCommand(CommandType.NEW,    1L, Side.SELL, 105.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW,    2L, Side.BUY,  95.0,  10, 1001L));

        // Aucun match au prix initial (105 > 95)
        // On modifie le SELL à 95 — il croise maintenant le BUY à 95
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.MODIFY, 1L, Side.SELL, 95.0, 10, 1002L));

        assertEquals(1, trades.size());
        assertEquals(10, trades.getFirst().quantity());
    }

    // --- Price-time priority ---

    @Test
    void betterPricedResidentShouldMatchFirst() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 101.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 105.0, 5, 1002L));

        // Le SELL à 100 doit matcher en premier (meilleur prix pour l'acheteur)
        assertEquals(1, trades.size());
        assertEquals(100.0, trades.getFirst().executionPrice());
    }

    @Test
    void samePriceFifoShouldMatchOlderOrderFirst() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 5, 1002L));

        // L'ordre 1 (plus ancien) doit matcher en premier
        assertEquals(1, trades.size());
        assertEquals(1L, trades.getFirst().sellOrderId());
    }

    // --- Prix qui ne se croisent pas ---

    @Test
    void buyBelowBestAskShouldProduceNoTrade() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.BUY, 95.0, 10, 1001L));

        assertTrue(trades.isEmpty());
    }

    @Test
    void sellAboveBestBidShouldProduceNoTrade() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.BUY, 100.0, 10, 1000L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.SELL, 105.0, 10, 1001L));

        assertTrue(trades.isEmpty());
    }

    // --- Sweep de plusieurs niveaux de prix ---

    @Test
    void incomingBuySweepingMultiplePriceLevelsShouldProduceMultipleTrades() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 101.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 105.0, 10, 1002L));

        assertEquals(2, trades.size());
        assertEquals(100.0, trades.get(0).executionPrice());
        assertEquals(101.0, trades.get(1).executionPrice());
    }

    @Test
    void incomingSellMatchingMultipleBuysShouldProduceMultipleTrades() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.BUY, 101.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY, 100.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.SELL, 95.0, 10, 1002L));

        assertEquals(2, trades.size());
        assertEquals(101.0, trades.get(0).executionPrice());
        assertEquals(100.0, trades.get(1).executionPrice());
    }

    // --- Champs du Trade ---

    @Test
    void tradeIdsShouldBeUniqueAndIncreasing() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 10, 1002L));

        assertEquals(2, trades.size());
        assertTrue(trades.get(0).tradeId() < trades.get(1).tradeId());
    }

    @Test
    void sellTakerShouldHaveCorrectBuyAndSellOrderIds() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.BUY,  100.0, 10, 1000L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 10, 1001L));

        assertEquals(1, trades.size());
        assertEquals(1L, trades.getFirst().buyOrderId());
        assertEquals(2L, trades.getFirst().sellOrderId());
    }

    // --- Quantités résiduelles ---

    @Test
    void partialMatchShouldLeaveIncomingResidualInBook() {
        // maker qty=5, taker qty=10 → trade de 5, résiduel taker de 5 reste dans le carnet
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5,  1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY,  100.0, 10, 1001L));

        // Un nouveau SELL doit trouver le résiduel BUY toujours présent
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.SELL, 100.0, 5, 1002L));

        assertEquals(1, trades.size());
        assertEquals(5, trades.getFirst().quantity());
    }

    @Test
    void partialMatchShouldLeaveResidentResidualInBook() {
        // maker qty=10, taker qty=5 → trade de 5, résiduel maker de 5 reste dans le carnet
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY,  100.0, 5,  1001L));

        // Un nouveau BUY doit trouver le résiduel SELL toujours présent
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 5, 1002L));

        assertEquals(1, trades.size());
        assertEquals(5, trades.getFirst().quantity());
    }

    // --- Cas limites CANCEL ---

    @Test
    void cancelUnknownOrderShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.process(new OrderCommand(CommandType.CANCEL, 999L, Side.BUY, 0, 0, 1000L)));
    }

    @Test
    void cancelPartiallyFilledOrderShouldSucceed() {
        engine.process(new OrderCommand(CommandType.NEW,    1L, Side.SELL, 100.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW,    2L, Side.BUY,  100.0, 5,  1001L));  // fill partiel : SELL a qty=5 restante

        List<Trade> cancelResult = engine.process(
                new OrderCommand(CommandType.CANCEL, 1L, Side.SELL, 0, 0, 1002L));

        assertTrue(cancelResult.isEmpty());

        // Le résiduel SELL doit avoir été retiré du carnet
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 5, 1003L));
        assertTrue(trades.isEmpty());
    }

    // --- Cas limites MODIFY ---

    @Test
    void modifyUnknownOrderShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.process(new OrderCommand(CommandType.MODIFY, 999L, Side.SELL, 95.0, 0, 1000L)));
    }

    @Test
    void modifyToNonCrossingPriceShouldProduceNoTrade() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.BUY,  90.0,  10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 10, 1001L));

        // Modification du SELL à 95 — toujours au-dessus du BUY à 90, pas de croisement
        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.MODIFY, 2L, Side.SELL, 95.0, 0, 1002L));

        assertTrue(trades.isEmpty());
    }

    @Test
    void modifyLosesTimePriorityAtSamePrice() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        // Modifier l'ordre 1 au même prix : il perd sa priorité et passe derrière l'ordre 2
        engine.process(new OrderCommand(CommandType.MODIFY, 1L, Side.SELL, 100.0, 0, 1002L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 5, 1003L));

        // L'ordre 2 (désormais le plus ancien à ce prix) doit matcher en premier
        assertEquals(1, trades.size());
        assertEquals(2L, trades.getFirst().sellOrderId());
    }

    @Test
    void modifyPartiallyFilledOrderShouldUseRemainingQuantity() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY,  100.0, 3,  1001L));  // fill partiel : SELL a qty=7 restante

        // Modifier le prix du SELL (qty résiduelle = 7, pas 10)
        engine.process(new OrderCommand(CommandType.MODIFY, 1L, Side.SELL, 95.0, 0, 1002L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 7, 1003L));

        assertEquals(1, trades.size());
        assertEquals(7, trades.getFirst().quantity());
    }
}