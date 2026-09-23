package com.victorien.matchingengine.dod;

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

/**
 * Tests de MatchingEngineSoA, adaptés de MatchingEngineTest (Jalon 0).
 * Même comportement observable attendu -- c'est la preuve d'équivalence
 * fonctionnelle entre la baseline et le Jalon 5b, comme convenu dès
 * l'Exercice 10 (comparaison reportée au niveau du moteur, pas du
 * carnet en isolation).
 *
 * Seule différence de méthode : OrderCommand.price() (double) est
 * tronqué en tick entier par MatchingEngineSoA -- toutes les valeurs de
 * prix ci-dessous restent des entiers exacts (100.0, 105.0, etc.) pour
 * que cette conversion soit sans perte et ne biaise pas la comparaison.
 */
class MatchingEngineSoATest {

    private static final int CAPACITY = 64;
    private static final int PRICE_TICKS = 20_000;

    private MatchingEngineSoA engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngineSoA(CAPACITY, PRICE_TICKS);
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

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 2L, Side.BUY, 100.0, 10, 1002L));

        assertTrue(trades.isEmpty());
    }

    // --- Cas MODIFY ---

    @Test
    void modifyShouldUpdatePriceAndTriggerMatchIfCrossing() {
        engine.process(new OrderCommand(CommandType.NEW,    1L, Side.SELL, 105.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW,    2L, Side.BUY,  95.0,  10, 1001L));

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

        assertEquals(1, trades.size());
        assertEquals(100.0, trades.getFirst().executionPrice());
    }

    @Test
    void samePriceFifoShouldMatchOlderOrderFirst() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 5, 1002L));

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
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5,  1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY,  100.0, 10, 1001L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.SELL, 100.0, 5, 1002L));

        assertEquals(1, trades.size());
        assertEquals(5, trades.getFirst().quantity());
    }

    @Test
    void partialMatchShouldLeaveResidentResidualInBook() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY,  100.0, 5,  1001L));

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
        engine.process(new OrderCommand(CommandType.NEW,    2L, Side.BUY,  100.0, 5,  1001L));

        List<Trade> cancelResult = engine.process(
                new OrderCommand(CommandType.CANCEL, 1L, Side.SELL, 0, 0, 1002L));

        assertTrue(cancelResult.isEmpty());

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

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.MODIFY, 2L, Side.SELL, 95.0, 0, 1002L));

        assertTrue(trades.isEmpty());
    }

    @Test
    void modifyLosesTimePriorityAtSamePrice() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 5, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.SELL, 100.0, 5, 1001L));

        engine.process(new OrderCommand(CommandType.MODIFY, 1L, Side.SELL, 100.0, 0, 1002L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 5, 1003L));

        assertEquals(1, trades.size());
        assertEquals(2L, trades.getFirst().sellOrderId());
    }

    @Test
    void modifyPartiallyFilledOrderShouldUseRemainingQuantity() {
        engine.process(new OrderCommand(CommandType.NEW, 1L, Side.SELL, 100.0, 10, 1000L));
        engine.process(new OrderCommand(CommandType.NEW, 2L, Side.BUY,  100.0, 3,  1001L));

        engine.process(new OrderCommand(CommandType.MODIFY, 1L, Side.SELL, 95.0, 0, 1002L));

        List<Trade> trades = engine.process(
                new OrderCommand(CommandType.NEW, 3L, Side.BUY, 100.0, 7, 1003L));

        assertEquals(1, trades.size());
        assertEquals(7, trades.getFirst().quantity());
    }
}