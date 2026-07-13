package com.victorien.matchingengine.model;

/**
 * Intention émise par le générateur d'ordres : créer, annuler ou modifier
 * un ordre. Instantané immuable, sans état qui évolue (contrairement à
 * {@link Order}).
 *
 * @param commandType nature de la commande
 * @param orderId     identifiant de l'ordre concerné
 * @param side        sens de l'ordre (ignoré pour CANCEL et MODIFY, qui
 *                    référencent un ordre déjà existant)
 * @param price       prix borné, cf. exigences non-fonctionnelles
 * @param quantity    quantité initiale pour NEW, nouvelle quantité pour
 *                    MODIFY, ignorée pour CANCEL
 * @param createdAt   horodatage d'émission de la commande
 */
public record OrderCommand(
        CommandType commandType,
        long orderId,
        Side side,
        double price,
        int quantity,
        long createdAt
) {
    public enum CommandType {
        NEW,
        CANCEL,
        MODIFY
    }
}
