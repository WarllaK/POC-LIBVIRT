package com.monitoring;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.PushGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class PrometheusWriter {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusWriter.class);
    private static final String JOB_NAME = "libvirt_collector";

    private PushGateway pushGateway;
    private CollectorRegistry registry;

    private Gauge cpuTimeSeconds;
    private Gauge vcpuCount;
    private Gauge uptimeSeconds;

    private Gauge memoryUsedKB;
    private Gauge memoryMaxKB;
    private Gauge memoryUsagePercent;

    private Gauge netRxBytes;
    private Gauge netTxBytes;

    private Gauge diskReadBytes;
    private Gauge diskWriteBytes;

    private Gauge stateGauge;

    public PrometheusWriter(String pushGatewayUrl) {
        try {
            String cleanUrl = pushGatewayUrl.replaceFirst("^https?://", "");

            this.pushGateway = new PushGateway(cleanUrl);
            this.registry = new CollectorRegistry();

            cpuTimeSeconds = Gauge.build()
                    .name("vm_cpu_time_seconds")
                    .help("CPU time em segundos")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            vcpuCount = Gauge.build()
                    .name("vm_vcpu_count")
                    .help("Quantidade de vCPUs")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            uptimeSeconds = Gauge.build()
                    .name("vm_uptime_seconds")
                    .help("Uptime estimado da VM")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            memoryUsedKB = Gauge.build()
                    .name("vm_memory_used_kb")
                    .help("Memória utilizada em KB")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            memoryMaxKB = Gauge.build()
                    .name("vm_memory_max_kb")
                    .help("Memória máxima em KB")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            memoryUsagePercent = Gauge.build()
                    .name("vm_memory_usage_percent")
                    .help("Percentual de uso de memória")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            netRxBytes = Gauge.build()
                    .name("vm_network_receive_bytes_total")
                    .help("Bytes recebidos na rede")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            netTxBytes = Gauge.build()
                    .name("vm_network_transmit_bytes_total")
                    .help("Bytes transmitidos na rede")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            diskReadBytes = Gauge.build()
                    .name("vm_disk_read_bytes_total")
                    .help("Bytes lidos em disco")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            diskWriteBytes = Gauge.build()
                    .name("vm_disk_write_bytes_total")
                    .help("Bytes escritos em disco")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            stateGauge = Gauge.build()
                    .name("vm_state_code")
                    .help("Estado da VM")
                    .labelNames("vm_name", "vm_uuid")
                    .register(registry);

            logger.info("Conectado ao Prometheus PushGateway: {} (usando: {})",
                    pushGatewayUrl, cleanUrl);

        } catch (Exception e) {
            logger.error("Erro ao conectar ao Prometheus PushGateway: {}", e.getMessage(), e);
            throw new RuntimeException("Falha na conexão com Prometheus", e);
        }
    }

    public void writeMetrics(Map<String, Object> metrics) {
        try {
            String vmName = (String) metrics.get("vm_name");
            String vmUuid = (String) metrics.get("vm_uuid");

            double cpuSeconds = ((Number) metrics.get("cpu_time_sec")).doubleValue();
            double memoryKB = ((Number) metrics.get("memory_kb")).doubleValue();
            double memoryMax = ((Number) metrics.get("max_memory_kb")).doubleValue();
            double memPercent = ((Number) metrics.get("memory_usage_percent")).doubleValue();
            double vcpus = ((Number) metrics.get("vcpus")).doubleValue();
            double uptime = ((Number) metrics.get("uptime_sec")).doubleValue();
            double state = ((Number) metrics.get("state")).doubleValue();

            double rx = ((Number) metrics.get("net_rx_bytes")).doubleValue();
            double tx = ((Number) metrics.get("net_tx_bytes")).doubleValue();
            double diskR = ((Number) metrics.get("disk_read_bytes")).doubleValue();
            double diskW = ((Number) metrics.get("disk_write_bytes")).doubleValue();

            cpuTimeSeconds.labels(vmName, vmUuid).set(cpuSeconds);
            vcpuCount.labels(vmName, vmUuid).set(vcpus);
            uptimeSeconds.labels(vmName, vmUuid).set(uptime);

            memoryUsedKB.labels(vmName, vmUuid).set(memoryKB);
            memoryMaxKB.labels(vmName, vmUuid).set(memoryMax);
            memoryUsagePercent.labels(vmName, vmUuid).set(memPercent);

            netRxBytes.labels(vmName, vmUuid).set(rx);
            netTxBytes.labels(vmName, vmUuid).set(tx);

            diskReadBytes.labels(vmName, vmUuid).set(diskR);
            diskWriteBytes.labels(vmName, vmUuid).set(diskW);

            stateGauge.labels(vmName, vmUuid).set(state);

            pushGateway.pushAdd(registry, JOB_NAME);

            logger.info(
                    "Métricas enviadas para Prometheus: {} (CPU: {}s, Mem: {} KB, vCPU: {}, NetRX: {}, DiskR: {})",
                    vmName,
                    String.format("%.2f", cpuSeconds),
                    (long) memoryKB,
                    (int) vcpus,
                    (long) rx,
                    (long) diskR
            );

        } catch (Exception e) {
            logger.error("Erro ao escrever métricas no Prometheus: {}", e.getMessage(), e);
        }
    }

    public void close() {
        try {
            if (pushGateway != null) {
                pushGateway.delete(JOB_NAME);
                logger.info("Conexão Prometheus fechada");
            }
        } catch (Exception e) {
            logger.error("Erro ao fechar conexão: {}", e.getMessage(), e);
        }
    }
}