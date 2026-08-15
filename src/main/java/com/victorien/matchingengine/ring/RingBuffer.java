package com.victorien.matchingengine.ring;

/**
 * Tampon circulaire de taille fixe (puissance de 2), manipulé
 * séquentiellement. Remplace la structure interne d'ArrayBlockingQueue
 * par un mécanisme dont l'indexation est explicite et contrôlée, plutôt
 * que déléguée à la JDK.
 *
 * Portée de cet exercice : AUCUNE notion de concurrence. next()/publish()
 * et get() sont appelés séquentiellement par un unique thread logique --
 * la coordination producteur/consommateur lock-free sera introduite à
 * l'Exercice 6.
 */
public class RingBuffer<T> {

    private final Object[] buffer;
    private final int mask;
    private final Sequence cursor = new Sequence(-1);
    private final Sequence publishedSequence = new Sequence(-1);

    private final Sequence[] gatingSequence;
    private int countGatingSequence = 0;

    public RingBuffer(int capacity) {
        this(capacity, 1);
    }

    public RingBuffer(int capacity, int numberConsumers) {
        // Le ring buffer doit avoir une taille étant une puissance de 2 pour permettre le ET logique avec le
        // masque binaire qui est une forme de modulo sur des puissances de 2

        if (capacity <= 0)
            throw new IllegalArgumentException("Need a capacity that is strictly positive");
        if (!((capacity & (capacity - 1)) == 0))
            throw new IllegalArgumentException("Need a capacity that is a power of 2");

        this.buffer = new Object[capacity];
        this.mask = capacity - 1;

        this.gatingSequence = new Sequence[numberConsumers];
    }

    /**
     * Réclame la prochaine séquence disponible pour écriture.
     * Incrémente le curseur interne et retourne la nouvelle séquence.
     */
    public long next() {
        long candidateSequence = cursor.get() + 1;
        long wrapPoint = candidateSequence - this.capacity();

        while (wrapPoint > this.minGating()) {
            // busy-spin : attente active du consommateur le plus lent
        }

        cursor.set(candidateSequence);
        return candidateSequence;
    }

    private long minGating(){
        if (countGatingSequence == 0)
            return Long.MAX_VALUE;
        long minGating = this.gatingSequence[0].get();
        for (int i = 1 ; i < this.countGatingSequence ; i++){
            long minCandidate = this.gatingSequence[i].get();
            if (minGating > minCandidate)
                minGating = minCandidate;
        }
        return minGating;
    }
    /**
     * Écrit une valeur à l'emplacement physique correspondant à la
     * séquence donnée. Ne rend PAS la valeur visible pour un lecteur --
     * c'est le rôle de publish() (distinction qui deviendra critique à
     * l'Exercice 6, où set() et publish() seront séparés dans le temps
     * par la construction effective de l'objet écrit).
     */
    public void set(long sequence, T value) {
        if (sequence < 0)
            throw new IllegalArgumentException("Cannot assign an index strictly negative");
        this.buffer[(int) (sequence & mask)] = value;
    }

    /**
     * Marque une séquence comme publiée. Dans ce Ring Buffer nu
     * (mono-thread, sans coordination), cette méthode peut être un
     * no-op ou une trace minimale -- réfléchis à ce qu'elle DEVRA faire
     * à l'Exercice 6, et documente cette anticipation en commentaire.
     */
    public void publish(long sequence) {
        this.publishedSequence.set(sequence);
    }

    /**
     * Lit la valeur à l'emplacement physique correspondant à la
     * séquence donnée.
     */
    @SuppressWarnings("unchecked")
    public T get(long sequence) {
        if (sequence < 0)
            throw new IllegalArgumentException("Cannot read at index strictly negative");
        return (T) this.buffer[(int) (sequence & mask)];
    }

    public int capacity() {
        return this.buffer.length;
    }

    /**
     * Retourne la séquence de PUBLICATION la plus récente (visible pour un
     * consommateur), et non le curseur de réclamation interne -- distinction volontaire
     */
    public long getCursor() {
        return publishedSequence.get();
    }

    public void addGatingSequence(Sequence consumerSequence) {
        if (this.countGatingSequence == this.gatingSequence.length)
            throw new IllegalStateException("Cannot add a new consumer as the limit has been reached");
        this.gatingSequence[this.countGatingSequence] = consumerSequence;
        this.countGatingSequence++;
    }
}