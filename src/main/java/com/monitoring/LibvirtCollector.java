package com.monitoring;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Coletor simples de métricas libvirt para POC
 */
public class LibvirtCollector {
    private static final Logger logger = LoggerFactory.getLogger(LibvirtCollector.class);

    private static final String LIBVIRT_URI = "qemu:///system";
    private static final String PROMETHEUS_PUSHGATEWAY_URL = "http://localhost:9091";
    private static final int COLLECT_INTERVAL_SECONDS = 30;
    
    private Connect connection;
    private PrometheusWriter prometheusWriter;
    
    public LibvirtCollector() {
        try {

            this.connection = new Connect(LIBVIRT_URI);
            logger.info("Conectado ao libvirt: {}", LIBVIRT_URI);

            this.prometheusWriter = new PrometheusWriter(PROMETHEUS_PUSHGATEWAY_URL);
            
        } catch (Exception e) {
            logger.error("Erro ao inicializar: {}", e.getMessage(), e);
            throw new RuntimeException("Falha na inicialização", e);
        }
    }
    
    /**
     * Coleta métricas de todas as VMs em execução
     */
    public void collectMetrics() {
        try {
            int[] domainIds = connection.listDomains();
            logger.info("Encontradas {} VMs em execução", domainIds.length);

            if (domainIds.length == 0) {
                logger.warn("Nenhuma VM em execução. Crie VMs para coletar métricas.");
                logger.info("Para criar VMs de teste: ./scripts/criar-vm-simples.sh 3");
                return;
            }

            if (domainIds.length == 0) {
                logger.warn("Nenhuma VM encontrada. Verifique: virsh list");
                try {
                    String[] allDomains = connection.listDefinedDomains();
                    if (allDomains.length > 0) {
                        logger.info("VMs definidas mas paradas: {}", String.join(", ", allDomains));
                        logger.info("Dica: Inicie as VMs com: virsh start <nome>");
                    }
                } catch (Exception e) {
                    logger.debug("Erro ao listar domínios definidos: {}", e.getMessage());
                }
            }

            for (int domainId : domainIds) {
                try {
                    Domain domain = connection.domainLookupByID(domainId);
                    collectDomainMetrics(domain);
                } catch (LibvirtException e) {
                    logger.warn("Erro ao coletar métricas da VM ID {}: {}", domainId, e.getMessage());
                }
            }
            
        } catch (LibvirtException e) {
            logger.error("Erro ao listar domínios: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Coleta métricas básicas de uma VM
     */
    private void collectDomainMetrics(Domain domain) throws LibvirtException {
        try {
            String vmName = domain.getName();
            String uuid = domain.getUUIDString();

            DomainInfo domainInfo = domain.getInfo();
            long cpuTime = domainInfo.cpuTime;
            long memoryKB = domainInfo.memory;
            long maxMemoryKB = domain.getMaxMemory();
            int state = domainInfo.state.ordinal();

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("timestamp", System.currentTimeMillis());
            metrics.put("vm_name", vmName);
            metrics.put("vm_uuid", uuid);
            metrics.put("cpu_time", cpuTime);
            metrics.put("memory_kb", memoryKB);
            metrics.put("max_memory_kb", maxMemoryKB);
            metrics.put("state", state);

            prometheusWriter.writeMetrics(metrics);
            
            logger.debug("Métricas coletadas para VM: {} (CPU: {}, Mem: {} KB)", 
                        vmName, cpuTime, memoryKB);
            
        } catch (Exception e) {
            logger.error("Erro ao coletar métricas: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Fecha conexões
     */
    public void close() {
        try {
            if (connection != null) {
                connection.close();
                logger.info("Conexão libvirt fechada");
            }
            if (prometheusWriter != null) {
                prometheusWriter.close();
            }
        } catch (Exception e) {
            logger.error("Erro ao fechar conexões: {}", e.getMessage(), e);
        }
    }
    
    public int getCollectIntervalSeconds() {
        return COLLECT_INTERVAL_SECONDS;
    }
}
