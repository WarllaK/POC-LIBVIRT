package com.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        LibvirtCollector collector = null;
        ScheduledExecutorService scheduler = null;
        
        try {
            logger.info("Iniciando Libvirt Collector POC...");

            collector = new LibvirtCollector();
            final LibvirtCollector finalCollector = collector;

            scheduler = Executors.newSingleThreadScheduledExecutor();
            int intervalSeconds = collector.getCollectIntervalSeconds();
            
            logger.info("Iniciando coleta de métricas a cada {} segundos", intervalSeconds);

            scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        finalCollector.collectMetrics();
                    } catch (Exception e) {
                        logger.error("Erro durante coleta: {}", e.getMessage(), e);
                    }
                },
                0,
                intervalSeconds,
                TimeUnit.SECONDS
            );

            logger.info("Coletor em execução. Pressione Ctrl+C para parar.");
            Thread.sleep(Long.MAX_VALUE);
            
        } catch (InterruptedException e) {
            logger.info("Coleta interrompida pelo usuário");
        } catch (Exception e) {
            logger.error("Erro durante execução: {}", e.getMessage(), e);
        } finally {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                }
            }

            if (collector != null) {
                collector.close();
            }
            
            logger.info("Aplicação encerrada");
        }
    }
}