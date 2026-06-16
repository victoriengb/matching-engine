package com.victorien.matchingengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Test placeholder garantissant que le job `build-and-test` du pipeline a
 * quelque chose à exécuter avant l'implémentation du Jalon 0. À remplacer
 * par les tests fonctionnels du matching engine au fur et à mesure.
 */
class MainTest {

    @Test
    void mainShouldRunWithoutThrowing() {
        assertDoesNotThrow(() -> Main.main(new String[] {}));
    }
}
