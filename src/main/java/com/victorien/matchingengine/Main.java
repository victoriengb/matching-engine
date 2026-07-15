package com.victorien.matchingengine;

import com.victorien.matchingengine.model.Order;
import com.victorien.matchingengine.model.Side;
import org.openjdk.jol.info.ClassLayout;

import java.time.*;
import java.util.Date;

/**
 * Point d'entrée en ligne de commandes du matching engine.
 *
 * Ceci est un placeholder destiné à valider l'infrastructure CI/CD avant
 * l'implémentation du Jalon 0. La logique réelle (génération d'ordres,
 * matching price-time priority, persistance) sera ajoutée à ce moment-là.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        LocalDate date = LocalDate.of(2023, Month.FEBRUARY, 15);
        Instant instant = date.atStartOfDay().atZone(ZoneId.of("UTC")).toInstant();

        Order order = new Order(1L, Side.BUY, 100.0, 10, instant.getEpochSecond());
        System.out.println(ClassLayout.parseInstance(order).toPrintable());
    }
}
