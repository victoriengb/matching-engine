package com.victorien.matchingengine.engine;

import com.victorien.matchingengine.book.OrderBook;
import com.victorien.matchingengine.model.Order;
import com.victorien.matchingengine.model.OrderCommand;
import com.victorien.matchingengine.model.Side;
import com.victorien.matchingengine.model.Trade;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Moteur de matching baseline (Jalon 0).
 *
 * Responsabilité unique : traduire des OrderCommand en Trade en appliquant
 * le price-time priority. Délègue entièrement la gestion de la structure
 * du carnet à OrderBook.
 *
 * Invariant de thread : cette classe n'est PAS thread-safe. Dans la
 * baseline, l'accès concurrent est géré par le verrou externe (synchronized
 * sur le bloc d'appel dans l'orchestrateur). La thread-safety interne sera
 * introduite au Jalon 1 via le modèle Single-Writer du Disruptor.
 */
public class MatchingEngine {

    private final OrderBook orderBook = new OrderBook();
    private final AtomicLong tradeIdGenerator = new AtomicLong(0);

    /**
     * Point d'entrée unique du moteur. Dispatche la commande vers le
     * handler approprié selon son type.
     *
     * @return liste des trades produits par cette commande (vide si aucun
     *         match, jamais null)
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
     * L'ordre entrant est d'abord inséré dans le carnet (il devient
     * résident), puis la boucle de matching tente de le croiser avec la
     * meilleure contrepartie disponible. Cette approche garantit que si
     * l'ordre n'est que partiellement exécuté, sa fraction résiduelle
     * reste dans le carnet en attente.
     *
     * @return liste des trades produits (peut contenir plusieurs trades
     *         si l'ordre entrant matche contre plusieurs résidents)
     */
    private List<Trade> handleNew(OrderCommand command) {
        Order incoming = new Order(command.orderId(), command.side(), command.price(), command.quantity(), command.createdAt());
        this.orderBook.insert(incoming);
        return this.match(incoming);
    }

    /**
     * Tente de matcher le meilleur bid contre le meilleur ask.
     * Produit un ou plusieurs trades jusqu'à ce que le carnet soit
     * décroisé ou qu'un des deux côtés soit vide.
     *
     * Rappel sémantique : le prix d'exécution est toujours celui de
     * l'ordre résident (celui qui était déjà dans le carnet avant
     * l'arrivée de la commande courante).
     *
     * @return liste des trades produits lors de cette passe de matching
     */
    private List<Trade> match(Order taker) {
        List<Trade> trades = new ArrayList<>();

        while (taker.remainingQuantity() > 0) {
            Order maker = bestCounterparty(taker);

            if (maker == null || !isCrossed(taker, maker))
                break;

            int quantity = Math.min(taker.remainingQuantity(), maker.remainingQuantity());

            taker.fill(quantity);
            maker.fill(quantity);

            trades.add(buildTrade(taker, maker, quantity));

            if (maker.isEmpty())
                orderBook.remove(maker.orderId());

            if (taker.isEmpty())
                orderBook.remove(taker.orderId());
        }

        return trades;
    }

    private boolean isCrossed(Order taker, Order maker) {
        return taker.side() == Side.BUY
                ? taker.price() >= maker.price()
                : taker.price() <= maker.price();
    }

    private Trade buildTrade(Order taker, Order maker, int quantity) {
        long id = tradeIdGenerator.getAndIncrement();
        // TODO(Jalon 1): remplacer par timestamp injecté dans OrderCommand.createdAt()
        // LIMITATION BASELINE : Instant.now() effectue un appel système à
        // chaque trade produit, sur le chemin critique du matching. C'est une
        // source de latence non déterministe. Ce choix est
        // volontaire pour la baseline : il sera remplacé par un timestamp
        // injecté directement dans OrderCommand (horodatage à la source,
        // côté générateur) dès le Jalon 1, éliminant l'appel système du
        // chemin critique du moteur de matching.
        long executionTime = Instant.now().getEpochSecond();
        if (taker.side() == Side.BUY)
            return new Trade(id, taker.orderId(), maker.orderId(), maker.price(), quantity, executionTime);
        else
            return new Trade(id, maker.orderId(), taker.orderId(), maker.price(), quantity, executionTime);
    }

    private Order bestCounterparty(Order order) {
        return order.side() == Side.BUY ? this.orderBook.bestAsk() : this.orderBook.bestBid();
    }

    /**
     * Traite une commande CANCEL : retire l'ordre du carnet.
     * Aucun trade ne peut être produit par une annulation.
     *
     * @return liste vide
     */
    private List<Trade> handleCancel(OrderCommand command) {
        Order removed = this.orderBook.remove(command.orderId());
        if (removed == null)
            throw new IllegalArgumentException("Cannot cancel unknown order: " + command.orderId());
        removed.cancel();
        return new ArrayList<>();
    }

    /**
     * Traite une commande MODIFY : modifie le prix d'un ordre existant.
     *
     * Implémentation retenue pour la baseline : remove + insert avec le
     * nouveau prix. Cette approche remet l'ordre en fin de file à son
     * nouveau niveau de prix — il perd sa priorité temporelle. C'est une
     * décision sémantique forte : modifier un prix équivaut à annuler
     * l'ordre et en soumettre un nouveau.
     *
     * Conséquence : un MODIFY peut déclencher un match immédiat si le
     * nouveau prix croise le carnet.
     *
     * @return liste des trades éventuellement produits par le nouveau prix
     */
    private List<Trade> handleModify(OrderCommand command) {
        Order existing = this.orderBook.remove(command.orderId());
        if (existing == null)
            throw new IllegalArgumentException("Cannot modify unknown order: " + command.orderId());
        existing.cancel();
        Order modified = new Order(command.orderId(), existing.side(), command.price(), existing.remainingQuantity(), command.createdAt());
        this.orderBook.insert(modified);
        return this.match(modified);
    }
}