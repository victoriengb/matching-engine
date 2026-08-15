package com.victorien.matchingengine.ring;

/**
 * Compteur de séquence thread-safe pour usage Single-Writer : UN SEUL
 * thread appelle jamais incrementAndGet() ou set() sur une instance
 * donnée -- c'est cette discipline (imposée par convention, pas par le
 * type système) qui permet d'utiliser un simple champ volatile plutôt
 * qu'un AtomicLong avec CAS. Le CAS protège contre des écritures
 * concurrentes ; volatile seul suffit dès lors qu'il n'y a jamais deux
 * threads qui écrivent en même temps -- seulement un qui écrit et
 * plusieurs qui lisent.
 *
 * Le mot-clé volatile garantit ici la relation happens-before : toute
 * écriture par le thread propriétaire est immédiatement visible pour
 * tout thread qui lit ensuite via get(), sans délai ni mise en cache de
 * cœur périmée.
 *
 * PADDING CONTRE LE FALSE SHARING (7 champs long avant et après value) :
 *
 * Une instance de Sequence ne contrôle pas son adresse de départ sur le
 * tas -- rien ne garantit qu'elle soit alignée sur une frontière de
 * cache line (64 octets). Dans le pire cas, value (8 octets) peut
 * démarrer immédiatement après le début d'une cache line, laissant
 * jusqu'à 63 octets de cette cache line potentiellement partagés avec
 * un objet voisin alloué juste avant sur le tas -- et symétriquement,
 * jusqu'à 63 octets après value peuvent appartenir à un objet voisin
 * alloué juste après.
 *
 * Pour garantir que la cache line qui contient value n'appartient
 * jamais qu'à cette instance, quel que soit son désalignement réel, il
 * faut donc absorber jusqu'à 63 octets de marge de chaque côté. Comme
 * value (8 octets) participe lui-même à cette marge dans le pire cas,
 * le padding strictement nécessaire est de 64 - 8 = 56 octets de chaque
 * côté, soit 7 champs long (7 x 8 = 56).
 *
 * Cette garantie repose sur une hypothèse non vérifiable depuis Java :
 * l'absence d'objets tiers alloués immédiatement adjacents sur le tas
 * pendant toute la durée de vie de l'instance. Le test SequencePaddingTest
 * valide la condition nécessaire (instanceSize >= 64 octets) mais pas la
 * condition suffisante (isolation physique garantie en toutes
 * circonstances) -- limitation documentée, cohérente avec la technique
 * historique employée par LMAX Disruptor (classe Sequence du projet
 * open-source), qui applique le même calcul 7+7.
 */
public class Sequence {


    @SuppressWarnings("unused")
    private long padding1, padding2, padding3, padding4, padding5, padding6, padding7;

    private volatile long value;

    @SuppressWarnings("unused")
    private long padding8, padding9, padding10, padding11, padding12, padding13, padding14;

    public Sequence(long initialValue) {
        this.value = initialValue;
    }

    public long get() {
        return value;
    }

    public void set(long value) {
        this.value = value;
    }

    /**
     * Incrémente puis retourne la nouvelle valeur. Non atomique au sens
     * CAS (lecture puis écriture en deux temps) -- SANS DANGER
     * uniquement parce que le Single-Writer Principle garantit qu'aucun
     * autre thread n'appelle jamais cette méthode concurremment sur la
     * même instance.
     */
    public long incrementAndGet() {
        long next = value + 1;
        value = next;
        return next;
    }
}