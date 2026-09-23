package com.victorien.matchingengine.orchestration;

import com.victorien.matchingengine.model.Trade;
import com.victorien.matchingengine.ring.RingBuffer;
import com.victorien.matchingengine.ring.Sequence;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PersistenceWorker implements Runnable {

    private static final String CSV_HEADER =
            "tradeId,buyOrderId,sellOrderId,executionPrice,quantity,executedAt\n";

    private final RingBuffer<Trade> inputRingBufferTrade;
    private final Writer csvWriter;

    private final Sequence sequenceInputRingBuffer = new Sequence(-1L);
    public PersistenceWorker(RingBuffer<Trade> inputRingBufferTrade, Writer csvWriter) {
        this.inputRingBufferTrade = inputRingBufferTrade;
        this.inputRingBufferTrade.addGatingSequence(sequenceInputRingBuffer);
        this.csvWriter = csvWriter;
    }

    @Override
    public void run() {
        try{
            csvWriter.write(CSV_HEADER);
            Trade trade;
            while (true){
                while (this.inputRingBufferTrade.getCursor() <= this.sequenceInputRingBuffer.get()){
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("Interrupted while spinning on inputRingBufferTrade");
                    }
                }

                long nextToRead = this.sequenceInputRingBuffer.get() + 1;
                trade = inputRingBufferTrade.get(nextToRead);
                this.sequenceInputRingBuffer.set(nextToRead);

                if (trade.equals(MatchingWorker.SHUTDOWN_SIGNAL))
                    break;

                this.csvWriter.write(toCsvLine(trade));
                this.csvWriter.flush();
            }
        } catch (IOException e){
            Logger.getLogger(PersistenceWorker.class.getName()).log(Level.SEVERE, "Unexpected I/O error occurred while writing to file");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.getLogger(PersistenceWorker.class.getName())
                    .log(Level.SEVERE, "Thread interrupted unexpectedly");
        } finally{
            try {
                this.csvWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String toCsvLine(Trade trade) {
        return String.format(Locale.US, "%d,%d,%d,%.2f,%d,%d\n",
                trade.tradeId(), trade.buyOrderId(), trade.sellOrderId(),
                trade.executionPrice(), trade.quantity(), trade.executedAt());
    }
}