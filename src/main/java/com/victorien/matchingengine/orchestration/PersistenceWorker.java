package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.model.Trade;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread consommateur du module de sortie et de persistance : lit les
 * Trade depuis inputQueue, les affiche sur la sortie standard, et les
 * journalise de manière SYNCHRONE dans un fichier CSV (choix baseline
 * délibéré, cf. exigences non-fonctionnelles -- durabilité).
 */
public class PersistenceWorker implements Runnable {

    private static final String CSV_HEADER =
            "tradeId,buyOrderId,sellOrderId,executionPrice,quantity,executedAt\n";

    private final BlockingQueue<Trade> inputQueue;
    private final Writer csvWriter;

    public PersistenceWorker(BlockingQueue<Trade> inputQueue, Writer csvWriter) {
        this.inputQueue = inputQueue;
        this.csvWriter = csvWriter;
    }

    @Override
    public void run() {
        // TODO
        // Écrire l'en-tête CSV, puis boucle : prendre un Trade (bloquant),
        // vérifier le signal d'arrêt, sinon formater en ligne CSV,
        // écrire de manière SYNCHRONE (flush après chaque ligne -- c'est
        // la limitation documentée dans les NFR), et afficher sur la
        // sortie standard. Gère la fermeture propre de csvWriter à la
        // sortie de la boucle.
        //
        // Question de conception : comment représentes-tu le signal
        // d'arrêt pour un Trade, puisque Trade n'a pas d'équivalent de
        // OrderGenerator.SHUTDOWN_SIGNAL ? Réfléchis à 2-3 options avant
        // de choisir (Trade nullable ? Optional ? un sentinel Trade à
        // tradeId = -1 comme pour OrderCommand ? une classe wrapper
        // Either<Trade, ShutdownSignal> ?) et documente ton choix.
        try{
            csvWriter.write(CSV_HEADER);
            Trade trade;
            while (true){
                trade = this.inputQueue.take();
                if (trade.equals(MatchingWorker.SHUTDOWN_SIGNAL))
                    break;
                System.out.println(trade);
                this.csvWriter.write(toCsvLine(trade));
                this.csvWriter.flush();
            }
        }catch (IOException e){
            Logger.getLogger(PersistenceWorker.class.getName()).log(Level.SEVERE, "Unexpected I/O error occurred while writing to file");
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            Logger.getLogger(PersistenceWorker.class.getName()).log(Level.SEVERE, "Thread interrupted unexpectedly");
        }
    }

    private String toCsvLine(Trade trade) {
        return String.format(Locale.US, "%d,%d,%d,%.2f,%d,%d\n",
                trade.tradeId(), trade.buyOrderId(), trade.sellOrderId(),
                trade.executionPrice(), trade.quantity(), trade.executedAt());
    }
}