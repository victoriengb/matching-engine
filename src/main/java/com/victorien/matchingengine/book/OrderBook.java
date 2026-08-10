package com.victorien.matchingengine.book;

import com.victorien.matchingengine.model.Order;
import com.victorien.matchingengine.model.Side;

import java.util.Comparator;
import java.util.Deque;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayDeque;

/**
 * Carnet d'ordres baseline (Jalon 0) : structure volontairement non
 * optimisée du JDK, point de référence pour les jalons suivants.
 *
 * Représentation retenue :
 *   - un TreeMap<Double, Deque<Order>> par côté, qui maintient les
 *     niveaux de prix triés et, à l'intérieur de chaque niveau, une file
 *     FIFO qui matérialise la priorité temporelle (price-time priority)
 *   - un HashMap<Long, Order> auxiliaire, nécessaire car un TreeMap
 *     trié par prix ne permet pas de retrouver un ordre par son orderId
 *     en temps logarithmique : sans cet index, annuler un ordre
 *     demanderait de parcourir tous les niveaux de prix
 *
 * Point d'attention sémantique : les deux côtés n'ont PAS le même ordre
 * naturel. Le côté BUY doit exposer le prix le PLUS ÉLEVÉ en tête (le
 * meilleur acheteur), le côté SELL le prix le PLUS BAS en tête (le
 * meilleur vendeur). Un TreeMap trie par défaut en ordre croissant : un
 * des deux côtés doit donc utiliser un comparateur inversé.
 */
public class OrderBook {

    private final TreeMap<Double, Deque<Order>> buyLevels =
            new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Double, Deque<Order>> sellLevels =
            new TreeMap<>();

    private final Map<Long, Order> ordersById = new HashMap<>();

    /**
     * Insère un nouvel ordre resting dans le carnet, au niveau de prix
     * correspondant, en fin de file (priorité temporelle : dernier
     * arrivé, dernier servi à prix égal).
     *
     * N'oublie pas de mettre à jour les deux structures (niveaux de prix
     * ET index par identifiant) -- elles doivent rester cohérentes.
     */
    public void insert(Order order) {

        if (Objects.isNull(order))
            throw new IllegalArgumentException("Order cannot be null");
        if (this.ordersById.containsKey(order.orderId()))
            throw new IllegalArgumentException("Order already exists");

        this.levelsFor(order.side())
                .computeIfAbsent(order.price(), s -> new ArrayDeque<>())
                .offerLast(order);

        this.ordersById.put(order.orderId(), order);
    }

    /**
     * Retire un ordre du carnet par son identifiant (cas CANCEL).
     *
     * Doit retirer l'ordre à la fois de la structure par niveau de prix
     * ET de l'index ordersById. Attention : l'ordre à retirer n'est pas
     * nécessairement en tête de sa file -- une annulation peut viser
     * n'importe quel ordre resting, pas seulement le plus prioritaire.
     *
     * Complexité : O(log n) pour localiser le niveau de prix via ordersById
     * + O(k) pour removeIf sur la file, k étant le nombre d'ordres au niveau
     * de prix concerné. Cette dégradation linéaire sur les annulations est une
     * limitation documentée de la baseline, mesurable sous forte contention.
     *
     * @return l'ordre retiré, ou null s'il n'existait pas (déjà exécuté,
     *         déjà annulé, ou jamais inséré)
     */
    public Order remove(long orderId) {

        Order order = this.ordersById.get(orderId);

        if (Objects.isNull(order))
            return null;

        this.ordersById.remove(orderId);

        this.levelsFor(order.side()).get(order.price()).removeIf((Order toRemove) -> toRemove.orderId() == orderId);

        if(this.levelsFor(order.side()).get(order.price()).isEmpty()){
            this.levelsFor(order.side()).remove(order.price());
        }

        return order;
    }

    /**
     * Retourne le meilleur ordre d'achat (prix le plus élevé, le plus
     * ancien à ce prix), ou null si le côté achat est vide.
     */
    public Order bestBid() {
        return this.maxOrder(Side.BUY);
    }

    /**
     * Retourne le meilleur ordre de vente (prix le plus bas, le plus
     * ancien à ce prix), ou null si le côté vente est vide.
     */
    public Order bestAsk() {
        return this.maxOrder(Side.SELL);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ORDER BOOK ===\n");

        sb.append("-- SELL --\n");
        sellLevels.descendingMap().forEach((price, orders) ->
            orders.forEach(o -> sb.append(String.format("  [SELL] id=%-6d price=%-10.2f qty=%d/%d status=%s%n",
                o.orderId(), o.price(), o.remainingQuantity(), o.originalQuantity(), o.status())))
        );

        sb.append("-- BUY  --\n");
        buyLevels.forEach((price, orders) ->
            orders.forEach(o -> sb.append(String.format("  [BUY ] id=%-6d price=%-10.2f qty=%d/%d status=%s%n",
                o.orderId(), o.price(), o.remainingQuantity(), o.originalQuantity(), o.status())))
        );

        sb.append("==================");
        return sb.toString();
    }

    private TreeMap<Double, Deque<Order>> levelsFor(Side side) {
        return side == Side.BUY ? buyLevels : sellLevels;
    }

    private Order maxOrder(Side side){
        if(this.levelsFor(side).isEmpty())
            return null;

        Order maxOrder = this.levelsFor(side).firstEntry().getValue().peekFirst();

        if (Objects.isNull(maxOrder)){
            throw new IllegalStateException("Price level exists with no orders — internal state violated");
        }
        return maxOrder;
    }

    public boolean isEmpty(){
        return this.ordersById.isEmpty();
    }
}
