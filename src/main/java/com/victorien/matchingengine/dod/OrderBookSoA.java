package com.victorien.matchingengine.dod;

import com.victorien.matchingengine.model.Side;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Hashing;

import java.util.Arrays;

/**
 * Carnet d'ordres en Structure-of-Arrays (Jalon 5b).
 *
 * Structures conçues en trois groupes, chacun dérivé du besoin plutôt
 * que d'un layout imposé :
 *
 *   Groupe 1 -- données de l'ordre, indexées par slot (recyclable, pas
 *   par orderId directement : le volume d'ordres sur la durée de vie du
 *   carnet est illimité, contrairement au nombre de slots).
 *
 *   Groupe 2 -- chaînage FIFO par tick, en liste DOUBLEMENT chaînée
 *   (next + previous) pour garantir un retrait en O(1) sur n'importe
 *   quel ordre, y compris le dernier maillon d'une file profonde, sans
 *   quoi ce cas dégraderait en O(k) -- exactement la limitation
 *   documentée sur OrderBook.remove() au Jalon 0.
 *
 *   Groupe 3 -- niveaux de prix actifs, triés et contigus, un par côté.
 *   Un tick entre dans cette structure UNIQUEMENT à sa première
 *   occupation (head(tick) == EMPTY avant insertion) et en sort
 *   UNIQUEMENT quand il se vide entièrement (head(tick) redevient
 *   EMPTY après retrait) -- jamais à chaque ordre individuel.
 */
public class OrderBookSoA {

    private static final int EMPTY = -1;

    // Statuts, encodés en byte plutôt que dérivés de
    // remainingQuantities : dériver introduirait un test conditionnel
    // sur le hot path (bestBid/match), écarté pour cette raison.
    private static final byte STATUS_RESTING          = 0;
    private static final byte STATUS_PARTIALLY_FILLED  = 1;
    private static final byte STATUS_FILLED            = 2;

    // Sides, encodés en byte pour le stockage interne (cf. discussion :
    // Side reste un enum en paramètre de méthode publique, converti une
    // seule fois à l'entrée -- seul le stockage interne est primitif).
    private static final byte SIDE_BUY  = 0;
    private static final byte SIDE_SELL = 1;

    private final int capacity;
    private final int priceTicks;

    // ═══ Groupe 1 — données de l'ordre, indexées par slot ═══
    private final long[] orderIds;
    private final int[]  prices;
    private final int[]  originalQuantities;
    private final int[]  remainingQuantities;
    private final long[] createdAt;
    private final byte[] sides;
    private final byte[] statuses;

    // ═══ Index externe orderId -> slot ═══
    private final Long2LongHashMap slotByOrderId;

    // ═══ Groupe 2 — chaînage FIFO doublement lié, par slot ═══
    private final int[] nextOrderIndex;
    private final int[] previousOrderIndex;

    // ═══ Groupe 2 — bornes de file, par tick ═══
    private final int[] buyLevelHead;
    private final int[] buyLevelTail;
    private final int[] sellLevelHead;
    private final int[] sellLevelTail;

    // ═══ Groupe 3 — niveaux actifs, triés et contigus ═══
    private final int[] activeBuyTicks;
    private int activeBuyLevelCount;
    private final int[] activeSellTicks;
    private int activeSellLevelCount;

    // ═══ Recyclage des slots ═══
    private final int[] freeSlots;
    private int freeSlotCount;

    public OrderBookSoA(int capacity, int priceTicks) {
        this.capacity = capacity;
        this.priceTicks = priceTicks;

        // ═══ Groupe 1 — taille capacity ═══
        this.orderIds = new long[capacity];
        this.prices = new int[capacity];
        this.originalQuantities = new int[capacity];
        this.remainingQuantities = new int[capacity];
        this.createdAt = new long[capacity];
        this.sides = new byte[capacity];
        this.statuses = new byte[capacity];

        // ═══ Groupe 2 (chaînage) — taille capacity ═══
        this.nextOrderIndex = new int[capacity];
        this.previousOrderIndex = new int[capacity];
        java.util.Arrays.fill(nextOrderIndex, EMPTY);
        java.util.Arrays.fill(previousOrderIndex, EMPTY);

        // ═══ Groupe 2 (bornes de file) — taille priceTicks ═══
        this.buyLevelHead = new int[priceTicks];
        this.buyLevelTail = new int[priceTicks];
        this.sellLevelHead = new int[priceTicks];
        this.sellLevelTail = new int[priceTicks];
        Arrays.fill(buyLevelHead, EMPTY);
        Arrays.fill(buyLevelTail, EMPTY);
        Arrays.fill(sellLevelHead, EMPTY);
        Arrays.fill(sellLevelTail, EMPTY);

        // ═══ Groupe 3 — taille priceTicks ═══
        this.activeBuyTicks = new int[priceTicks];
        this.activeSellTicks = new int[priceTicks];
        this.activeBuyLevelCount = 0;
        this.activeSellLevelCount = 0;

        // ═══ Recyclage des slots — taille capacity ═══
        this.freeSlots = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            freeSlots[i] = i;
        }
        this.freeSlotCount = capacity;

        // ═══ Index externe ═══
        this.slotByOrderId = new Long2LongHashMap(capacity, Hashing.DEFAULT_LOAD_FACTOR, EMPTY);
    }

    public void insert(long orderId, Side side, int priceInTicks,
                       int quantity, long timestamp) {
        if (freeSlotCount == 0)
            throw new IllegalStateException("No free slots for storing a new order");
        if (this.contains(orderId))
            throw new IllegalArgumentException("Cannot add two orders with same orderId");
        if (priceInTicks < 0 || priceInTicks >= this.priceTicks)
            throw new IllegalArgumentException("Price out of bounds");
        if (quantity <= 0)
            throw new IllegalArgumentException("Quantity cannot be negative or zero");

        // ═══ Groupe 1 — données de l'ordre, indexées par slot ═══
        int slot = this.freeSlots[--freeSlotCount];
        this.orderIds[slot] = orderId;
        this.prices[slot] = priceInTicks;
        this.originalQuantities[slot] = quantity;
        this.remainingQuantities[slot] = quantity;
        this.createdAt[slot] = timestamp;
        byte sideOrder = side == Side.BUY ? SIDE_BUY : SIDE_SELL;
        this.sides[slot] = sideOrder;
        this.statuses[slot] = STATUS_RESTING;

        // ═══ Index externe orderId -> slot ═══
        this.slotByOrderId.put(orderId, slot);

        // ═══ Groupe 2 — chaînage FIFO doublement lié, par slot ═══

        if (this.sideHead(sideOrder)[priceInTicks] != EMPTY){
            int slotTail = this.sideTail(sideOrder)[priceInTicks];
            this.previousOrderIndex[slot] = slotTail;
            this.nextOrderIndex[slotTail] = slot;
            this.sideTail(sideOrder)[priceInTicks] = slot;
        }else{
            this.sideHead(sideOrder)[priceInTicks] = slot;
            this.sideTail(sideOrder)[priceInTicks] = slot;

            this.nextOrderIndex[slot] = EMPTY;
            this.previousOrderIndex[slot] = EMPTY;

            // ═══ Groupe 3 — niveaux actifs, triés et contigus ═══
            if (sideOrder == SIDE_SELL){
                int activeIndex = this.binarySearchActiveIndex(this.activeSellTicks, this.activeSellLevelCount, priceInTicks, false);
                this.activeSellLevelCount = this.insertActiveTick(this.activeSellTicks, this.activeSellLevelCount, priceInTicks, activeIndex);
            }else{
                int activeIndex = this.binarySearchActiveIndex(this.activeBuyTicks, this.activeBuyLevelCount, priceInTicks, true);
                this.activeBuyLevelCount = this.insertActiveTick(this.activeBuyTicks, this.activeBuyLevelCount, priceInTicks, activeIndex);
            }
        }


    }

    private int[] sideTail(byte side){
        if (side == SIDE_SELL)
            return this.sellLevelTail;
        return this.buyLevelTail;
    }
    private int[] sideHead(byte side){
        if (side == SIDE_SELL)
            return this.sellLevelHead;
        return this.buyLevelHead;
    }

    private int binarySearchActiveIndex(int[] activeTicks, int activeLevelCount, int tick, boolean descending) {
        int lowerBound = 0;
        int upperBound = activeLevelCount;

        int mid;
        while (lowerBound < upperBound) {
            mid = lowerBound + (upperBound - lowerBound) / 2;
            boolean shouldGoRight = descending
                    ? activeTicks[mid] > tick
                    : activeTicks[mid] < tick;

            if (shouldGoRight) {
                lowerBound = mid + 1;
            } else {
                upperBound = mid;
            }
        }

        return lowerBound;
    }

    private int insertActiveTick(int[] activeTick, int activeLevelCount, int tick, int index){
        for (int i = activeLevelCount ; i > index ; i--){
            activeTick[i] = activeTick[i-1];
        }
        activeTick[index] = tick;
        return ++activeLevelCount;
    }

    public boolean remove(long orderId) {
        if (!this.contains(orderId))
            return false;

        // ═══ Groupe 1 ═══
        int slot = (int) this.slotByOrderId.get(orderId);
        int priceInTicks = this.prices[slot];
        byte sideOrder = this.sides[slot];

        this.freeSlots[freeSlotCount] = slot;
        freeSlotCount++;

        // ═══ Groupe 2 — chaînage FIFO doublement lié, par slot ═══

        int previousSlot = this.previousOrderIndex[slot];
        int nextSlot = this.nextOrderIndex[slot];

        if (previousSlot != EMPTY && nextSlot != EMPTY){
            this.nextOrderIndex[previousSlot] = nextSlot;
            this.previousOrderIndex[nextSlot] = previousSlot;
        }else if (previousSlot == EMPTY && nextSlot == EMPTY){
            this.sideHead(sideOrder)[priceInTicks] = EMPTY;
            this.sideTail(sideOrder)[priceInTicks] = EMPTY;

            // ═══ Groupe 3 — niveaux actifs, triés et contigus ═══
            if (sideOrder == SIDE_SELL){
                int activeIndex = this.binarySearchActiveIndex(this.activeSellTicks, this.activeSellLevelCount, priceInTicks, false);
                this.activeSellLevelCount = this.removeActiveTick(this.activeSellTicks, this.activeSellLevelCount, activeIndex);
            }else{
                int activeIndex = this.binarySearchActiveIndex(this.activeBuyTicks, this.activeBuyLevelCount, priceInTicks, true);
                this.activeBuyLevelCount = this.removeActiveTick(this.activeBuyTicks, this.activeBuyLevelCount, activeIndex);
            }

        }else if (previousSlot == EMPTY){
            this.sideHead(sideOrder)[priceInTicks] = nextSlot;
            this.previousOrderIndex[nextSlot] = EMPTY;
        }else{
            this.sideTail(sideOrder)[priceInTicks] = previousSlot;
            this.nextOrderIndex[previousSlot] = EMPTY;
        }
        this.nextOrderIndex[slot] = EMPTY;
        this.previousOrderIndex[slot] = EMPTY;

        this.slotByOrderId.remove(orderId);

        return true;
    }

    private int removeActiveTick(int[] activeTick, int activeLevelCount, int index){
        for (int i = index ; i < activeLevelCount - 1 ; i++){
            activeTick[i] = activeTick[i+1];
        }
        return --activeLevelCount;
    }

    public long bestBidOrderId() {
        if (this.activeBuyLevelCount == 0)
            return -1L;
        int bestTick = this.activeBuyTicks[0];
        int slot = this.buyLevelHead[bestTick];

        return this.orderIds[slot];
    }

    public long bestAskOrderId() {
        if (this.activeSellLevelCount == 0)
            return -1L;
        int bestTick = this.activeSellTicks[0];
        int slot = this.sellLevelHead[bestTick];

        return this.orderIds[slot];
    }

    public boolean contains(long orderId) {
        return this.slotByOrderId.get(orderId) != EMPTY;
    }

    public int priceOf(long orderId) {
        if (slotByOrderId.get(orderId) == EMPTY)
            throw new IllegalArgumentException("No such orderId in Order Book");
        int slot = (int) slotByOrderId.get(orderId);
        return prices[slot];
    }

    public int remainingQuantityOf(long orderId) {
        if (slotByOrderId.get(orderId) == EMPTY)
            throw new IllegalArgumentException("No such orderId in Order Book");
        int slot = (int) slotByOrderId.get(orderId);
        return this.remainingQuantities[slot];
    }

    /**
     * Décrémente la quantité restante d'un ordre résident et met à jour son
     * statut en conséquence (RESTING/PARTIALLY_FILLED -> PARTIALLY_FILLED
     * ou FILLED selon le résultat).
     *
     * Ne retire JAMAIS l'ordre du carnet, même si remainingQuantity atteint
     * 0 -- cette décision revient à l'appelant (MatchingEngineSoA).
     *
     * Invariants repris de Order.fill() (Jalon 0), complétés selon le
     * contrat de la Javadoc initiale de OrderBookSoA :
     *
     * @throws IllegalArgumentException si orderId n'existe pas dans le carnet
     * @throws IllegalStateException si l'ordre est déjà FILLED
     * @throws IllegalArgumentException si decrementedQuantity <= 0
     * @throws IllegalArgumentException si decrementedQuantity dépasse la
     *         quantité restante actuelle
     */
    public void fill(long orderId, int decrementedQuantity) {
        if (!this.contains(orderId))
            throw new IllegalArgumentException("No such orderId in OrderBook");
        int slot = (int) this.slotByOrderId.get(orderId);
        if (this.statuses[slot] == STATUS_FILLED)
            throw new IllegalStateException("Order is already filled");
        if (decrementedQuantity <= 0)
            throw new IllegalArgumentException("Decremented quantity cannot be inferior or equal to zero");
        if (decrementedQuantity > this.remainingQuantities[slot])
            throw new IllegalArgumentException("Decremented quantity cannot be superior to remainning quantity");

        this.remainingQuantities[slot] -= decrementedQuantity;
        this.updateStatusAfterFill(slot);
    }

    private void updateStatusAfterFill(int slot) {
        if (this.remainingQuantities[slot] > 0)
            this.statuses[slot] = STATUS_PARTIALLY_FILLED;
        else
            this.statuses[slot] = STATUS_FILLED;
    }

    public boolean isFilled(long orderId) {
        if (!this.contains(orderId))
            throw new IllegalArgumentException("No such orderId in OrderBook");

        int slot = (int) this.slotByOrderId.get(orderId);
        return this.statuses[slot] == STATUS_FILLED;
    }

    /**************************************************************/

    // ═══ Accesseurs package-private, réservés aux tests ═══

    int slotOf(long orderId) {
        return (int) slotByOrderId.get(orderId);
    }

    int freeSlotCount() {
        return freeSlotCount;
    }

    long orderIdAtSlot(int slot) {
        return orderIds[slot];
    }

    int priceAtSlot(int slot) {
        return prices[slot];
    }

    int remainingQuantityAtSlot(int slot) {
        return remainingQuantities[slot];
    }

    byte sideAtSlot(int slot) {
        return sides[slot];
    }

    byte statusAtSlot(int slot) {
        return statuses[slot];
    }

    int nextOrderIndexAtSlot(int slot) {
        return nextOrderIndex[slot];
    }

    int previousOrderIndexAtSlot(int slot) {
        return previousOrderIndex[slot];
    }

    int buyLevelHeadAt(int tick) {
        return buyLevelHead[tick];
    }

    int buyLevelTailAt(int tick) {
        return buyLevelTail[tick];
    }

    int sellLevelHeadAt(int tick) {
        return sellLevelHead[tick];
    }

    int sellLevelTailAt(int tick) {
        return sellLevelTail[tick];
    }

    int activeBuyLevelCount() {
        return activeBuyLevelCount;
    }

    int activeSellLevelCount() {
        return activeSellLevelCount;
    }

    int activeBuyTickAt(int rank) {
        return activeBuyTicks[rank];
    }

    int activeSellTickAt(int rank) {
        return activeSellTicks[rank];
    }

    static int emptySentinel() {
        return EMPTY;
    }

    static byte statusRestingValue() {
        return STATUS_RESTING;
    }
}