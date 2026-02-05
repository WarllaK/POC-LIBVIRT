package com.monitoring;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.PushGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class PrometheusWriter {
    private static final Logger logger = LoggerFactory.getLogger(PrometheusWriter.class);
    
    private PushGateway pushGateway;
    private CollectorRegistry registry;
    private Gauge cpuTimeGauge;
    private Gauge memoryKBGauge;
    private Gauge maxMemoryKBGauge;
    private Gauge stateGauge;
    
    public PrometheusWriter(String pushGatewayUrl) {
        try {
            String cleanUrl = pushGatewayUrl.replaceFirst("^https?://", "");
            this.pushGateway = new PushGateway(cleanUrl);
            this.registry = new CollectorRegistry();

            this.cpuTimeGauge = Gauge.build()
                    .name("vm_cpu_time_seconds")
                    .help("CPU time em segundos")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);
            
            this.memoryKBGauge = Gauge.build()
                    .name("vm_memory_kb")
                    .help("Memória utilizada em KB")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);
            
            this.maxMemoryKBGauge = Gauge.build()
                    .name("vm_max_memory_kb")
                    .help("Memória máxima em KB")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);
            
            this.stateGauge = Gauge.build()
                    .name("vm_state")
                    .help("Estado da VM (0=NOSTATE, 1=RUNNING, 2=BLOCKED, etc.)")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);
            
            logger.info("Conectado ao Prometheus PushGateway: {} (usando: {})", pushGatewayUrl, cleanUrl);
        } catch (Exception e) {
            logger.error("Erro ao conectar ao Prometheus PushGateway: {}", e.getMessage(), e);
            throw new RuntimeException("Falha na conexão com Prometheus", e);
        }
    }
    
    public void writeMetrics(Map<String, Object> metrics) {
        try {
            String vmName = (String) metrics.get("vm_name");
            String vmUuid = (String) metrics.get("vm_uuid");
            Long cpuTime = ((Number) metrics.get("cpu_time")).longValue();
            Long memoryKB = ((Number) metrics.get("memory_kb")).longValue();
            Long maxMemoryKB = ((Number) metrics.get("max_memory_kb")).longValue();
            Integer state = ((Number) metrics.get("state")).intValue();

            double cpuTimeSeconds = cpuTime / 1_000_000_000.0;

            cpuTimeGauge.labels(vmName, vmUuid).set(cpuTimeSeconds);
            memoryKBGauge.labels(vmName, vmUuid).set(memoryKB);
            maxMemoryKBGauge.labels(vmName, vmUuid).set(maxMemoryKB);
            stateGauge.labels(vmName, vmUuid).set(state);

            pushGateway.pushAdd(registry, "libvirt_collector");
            
            logger.info("Métricas enviadas para Prometheus: {} (CPU: {}s, Mem: {} KB)", 
                       vmName, String.format("%.2f", cpuTimeSeconds), memoryKB);
            
        } catch (Exception e) {
            logger.error("Erro ao escrever métricas no Prometheus: {}", e.getMessage(), e);
        }
    }
    
    public void close() {
        try {
            if (pushGateway != null) {
                pushGateway.delete("libvirt_collector");
                logger.info("Conexão Prometheus fechada");
            }
        } catch (Exception e) {
            logger.error("Erro ao fechar conexão: {}", e.getMessage(), e);
        }
    }
}