package com.victorien.matchingengine.dod;

import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Side;
import com.victorien.matchingengine.model.Trade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Moteur de matching sur OrderBookSoA (Jalon 5b).
 *
 * Décliné de MatchingEngine (Jalon 0) : même logique de price-time
 * priority, mais manipule des orderId/slots via OrderBookSoA plutôt que
 * des références Order. Différences structurelles principales :
 *
 *   - match() prend le side du taker en paramètre explicite (évite un
 *     aller-retour de lecture, puisque l'appelant le connaît déjà)
 *   - la quantité restante et les prix sont relus via
 *     remainingQuantityOf()/priceOf() à chaque itération, plutôt que
 *     suivis dans une variable locale -- une seule source de vérité
 *   - handleModify() ne fait plus appel à un équivalent de
 *     Order.cancel() : OrderBookSoA n'a pas de statut CANCELED
 *     persistant (cf. décision Exercice 12 -- remove() est immédiat,
 *     aucune fenêtre où l'ordre existe avec un statut CANCELED visible)
 */
public class MatchingEngineSoA {

    private final OrderBookSoA orderBook;
    private final AtomicLong tradeIdGenerator = new AtomicLong(0);

    public MatchingEngineSoA(int capacity, int priceTicks) {
        this.orderBook = new OrderBookSoA(capacity, priceTicks);
    }

    /**
     * Point d'entrée unique du moteur. Dispatche la commande vers le
     * handler approprié selon son type.
     *
     * @return liste des trades produits par cette commande (vide si
     *         aucun match, jamais null)
     */
    public List<Trade> process(OrderCommand command) {
        List<Trade> trade = new ArrayList<>();
        switch(command.commandType()){
            case OrderCommand.CommandType.NEW -> {
                trade = handleNew(command);
            }
            case OrderCommand.CommandType.CANCEL -> {
                trade = handleCancel(command);
            }
            case OrderCommand.CommandType.MODIFY -> {
                trade = handleModify(command);
            }
        }
        return trade;
    }

    /**
     * Traite une commande NEW : insère l'ordre dans le carnet et tente
     * immédiatement un matching.
     *
     * @return liste des trades produits (peut contenir plusieurs trades
     *         si l'ordre entrant matche contre plusieurs résidents)
     */
    private List<Trade> handleNew(OrderCommand command) {
        this.orderBook.insert(command.orderId(), command.side(), this.toPriceInTicks(command.price()), command.quantity(), command.createdAt());
        return this.match(command.orderId(), command.side(), command.createdAt());
    }

    /**
     * Tente de matcher le taker contre la meilleure contrepartie
     * disponible, jusqu'à épuisement du taker ou décroisement du
     * carnet.
     *
     * @param takerId   l'orderId de l'ordre entrant, déjà résident dans
     *                  le carnet au moment de l'appel
     * @param takerSide le side du taker, transmis explicitement pour
     *                  éviter une lecture supplémentaire via le carnet
     * @return liste des trades produits lors de cette passe de matching
     */
    private List<Trade> match(long takerId, Side takerSide, long executionTime) {
        List<Trade> trades = new ArrayList<>();

        while (orderBook.remainingQuantityOf(takerId) > 0) {
            long makerId = bestCounterparty(takerSide);

            if (makerId == -1L)
                break;

            int takerPrice = this.orderBook.priceOf(takerId);
            int makerPrice = this.orderBook.priceOf(makerId);

            if (!isCrossed(takerSide, takerPrice, makerPrice))
                break;

            int quantity = Math.min(
                    this.orderBook.remainingQuantityOf(takerId),
                    this.orderBook.remainingQuantityOf(makerId));

            this.orderBook.fill(takerId, quantity);
            this.orderBook.fill(makerId, quantity);
            trades.add(buildTrade(takerId, makerId, takerSide, makerPrice, quantity, executionTime));

            if (this.orderBook.isFilled(makerId))
                this.orderBook.remove(makerId);

            if (this.orderBook.isFilled(takerId)) {
                this.orderBook.remove(takerId);
                break;
            }
        }
        return trades;
    }

    private boolean isCrossed(Side takerSide, int takerPrice, int makerPrice) {
        return takerSide == Side.BUY
                ? takerPrice >= makerPrice
                : takerPrice <= makerPrice;
    }

    private Trade buildTrade(long takerId, long makerId, Side takerSide,
                             int executionPrice, int quantity, long executionTime) {
        long id = tradeIdGenerator.getAndIncrement();

        double monetaryPrice = executionPrice / 100.0;
        if (takerSide == Side.BUY)
            return new Trade(id, takerId, makerId, monetaryPrice, quantity, executionTime);
        else
            return new Trade(id, makerId, takerId, monetaryPrice, quantity, executionTime);
    }

    private long bestCounterparty(Side takerSide) {
        if (takerSide == Side.SELL)
            return this.orderBook.bestBidOrderId();
        return this.orderBook.bestAskOrderId();
    }

    /**
     * Traite une commande CANCEL : retire l'ordre du carnet.
     *
     * @return liste vide
     */
    private List<Trade> handleCancel(OrderCommand command) {
        boolean removed = this.orderBook.remove(command.orderId());
        if (!removed)
            throw new IllegalArgumentException("Cannot cancel unknown order: " + command.orderId());
        return new ArrayList<>();
    }

    /**
     * Traite une commande MODIFY : retire l'ordre existant et le
     * réinsère au nouveau prix, avec sa quantité résiduelle.
     *
     * @return liste des trades éventuellement produits par le nouveau
     *         prix
     */
    private List<Trade> handleModify(OrderCommand command) {
        int remainingQuantity = this.orderBook.remainingQuantityOf(command.orderId());
        boolean removed = this.orderBook.remove(command.orderId());

        if (!removed)
            throw new IllegalArgumentException("Cannot modify unknown order: " + command.orderId());

        this.orderBook.insert(command.orderId(), command.side(), this.toPriceInTicks(command.price()), remainingQuantity, command.createdAt());

        return this.match(command.orderId(), command.side(), command.createdAt());
    }

    /**
     * Convertit un prix flottant (OrderCommand.price(), en unités
     * monétaires) en tick entier (centimes) consommé par OrderBookSoA.
     *
     * Aucune perte de précision possible en pratique : un prix monétaire
     * n'a de sens économique qu'au centime près (il n'existe pas de
     * fraction de centime dans aucune convention de marché réel), donc
     * price * 100 est toujours une valeur entière avant troncature.
     * Cohérent avec le bornage des prix en centimes déjà posé dans les
     * exigences non-fonctionnelles du system design.
     */
    private int toPriceInTicks(double price) {
        return (int) Math.round(price * 100);
    }
}