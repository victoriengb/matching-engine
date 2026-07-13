package com.victorien.matchingengine.model;

/**
 * Résultat d'un match entre un ordre d'achat et un ordre de vente.
 * Instantané immuable produit par le module de matching, consommé par le
 * module de sortie et de persistance.
 *
 * @param tradeId        identifiant unique de la transaction
 * @param buyOrderId     identifiant de l'ordre d'achat impliqué
 * @param sellOrderId    identifiant de l'ordre de vente impliqué
 * @param executionPrice prix de l'ordre résident (cf. règle de
 *                       price-time priority : le prix déjà présent dans
 *                       le carnet fait foi, pas celui de l'ordre arrivant)
 * @param quantity       quantité échangée lors de ce match
 * @param executedAt     horodatage d'exécution de la transaction
 */
public record Trade(
        long tradeId,
        long buyOrderId,
        long sellOrderId,
        double executionPrice,
        int quantity,
        long executedAt
) {
}
