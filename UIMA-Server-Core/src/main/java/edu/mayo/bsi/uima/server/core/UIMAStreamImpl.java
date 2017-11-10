package edu.mayo.bsi.uima.server.core;

import edu.mayo.bsi.uima.server.api.UIMAStream;
import edu.mayo.bsi.uima.server.core.cc.StreamResultHandlerCasConsumer;
import edu.mayo.bsi.uima.server.core.cr.BlockingStreamCollectionReader;
import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.cas.CAS;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.fit.factory.AggregateBuilder;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.CollectionReaderFactory;
import org.apache.uima.fit.pipeline.SimplePipeline;
import org.apache.uima.resource.ResourceInitializationException;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UIMAStreamImpl implements UIMAStream {

    private Logger logger;
    private String name;
    private ExecutorService threadPool;
    private static CollectionReaderDescription STREAM_READER_DESC;

    static {
        try {
            STREAM_READER_DESC = CollectionReaderFactory.createReaderDescription(BlockingStreamCollectionReader.class);
        } catch (ResourceInitializationException e) {
            e.printStackTrace();
            System.exit(1); // This should never be reached, but cannot be handled/is world-ending if it does.
        }
    }

    public UIMAStreamImpl(String streamName, AnalysisEngineDescription metadataDesc, AnalysisEngineDescription pipelineDesc) throws ResourceInitializationException {
        logger = Logger.getLogger("UIMA-Stream-" + streamName);
        name = streamName;
        int numPipelines = 1;
        String threadProp;
        if ((threadProp = System.getProperty("uima.streams.%pipeline%.threads".replace("%pipeline%", name))) == null) {
            logger.log(Level.WARNING, "The number of pipeline threads for this stream was not set via " +
                    "-Duima.server.%pipeline%.threads. Please set the value of this property as your CPU allows " +
                    "to improve performance".replace("%pipeline%", name));
        } else {
            try {
                numPipelines = Integer.valueOf(threadProp);
            } catch (NumberFormatException e) {
                logger.log(Level.SEVERE, "The number of pipeline threads to run set in " +
                        "-Duima.server.%pipeline%.threads, ".replace("%pipeline%", name) + threadProp + ", could not be parsed as an integer.");
            }
        }
        logger.log(Level.INFO, "Starting UIMA Stream " + name + " with " + numPipelines + " pipeline threads");

        // We don't really need to use a thread pool for this initial application, but it is included as there is
        // no real overhead cost and is way easier to expand on in the future
        threadPool = Executors.newFixedThreadPool(numPipelines);
        try {
            AggregateBuilder pipelineBuilder = new AggregateBuilder();
            pipelineBuilder.add(metadataDesc);
            pipelineBuilder.add(pipelineDesc);
            pipelineBuilder.add(AnalysisEngineFactory.createEngineDescription(StreamResultHandlerCasConsumer.class));
            for (int i = 0; i < numPipelines; i++) {
                threadPool.submit(() -> {
                    try {
                        SimplePipeline.runPipeline(
                                STREAM_READER_DESC,
                                pipelineBuilder.createAggregateDescription());
                    } catch (UIMAException | IOException e) {
                        e.printStackTrace();
                        threadPool.shutdownNow();
                    }
                });

            }
        } catch (Exception e) {
            threadPool.shutdownNow();
            throw e;
        }
    }


    @Override
    public CompletableFuture<CAS> submit(String document, String metadata) {
        return BlockingStreamCollectionReader.submitMessage(UUID.randomUUID(), document, metadata);
    }

    @Override
    public void shutdown() {
        logger.log(Level.INFO, name + " UIMA Stream is no longer accepting new requests");
        BlockingStreamCollectionReader.shutdownQueue();
        threadPool.shutdown();
        while (true) {
            try {
                if (threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.log(Level.INFO, "All UIMA Pipelines for UIMA Stream " + name + " have been shut down");
                    break;
                }

            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, "Shutdown interrupted for UIMA Stream " + name, e);
            }
        }
    }

    @Override
    public Future<?> shutdownAsync() {
        logger.log(Level.INFO, name + " UIMA Stream is no longer accepting new requests");
        BlockingStreamCollectionReader.shutdownQueue();
        threadPool.shutdown();
        return Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    if (threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                        logger.log(Level.INFO, "All UIMA Pipelines for UIMA Stream " + name + " have been shut down");
                        return;
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, "Shutdown interrupted for UIMA Stream " + name, e);
                }
            }
        });
    }

    @Override
    public void shutdownNow() {
        logger.log(Level.INFO, "Force shutting down UIMA stream " + name);
        threadPool.shutdownNow();
    }
}