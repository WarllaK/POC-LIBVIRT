package com.monitoring;

import org.libvirt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.*;

public class LibvirtCollector {

    private static final Logger logger = LoggerFactory.getLogger(LibvirtCollector.class);

    private static final String LIBVIRT_URI = "qemu:///system";
    private static final String PROMETHEUS_PUSHGATEWAY_URL = "http://localhost:9091";
    private static final int COLLECT_INTERVAL_SECONDS = 30;

    private Connect connection;
    private PrometheusWriter prometheusWriter;

    public LibvirtCollector() {
        try {
            connection = new Connect(LIBVIRT_URI);
            prometheusWriter = new PrometheusWriter(PROMETHEUS_PUSHGATEWAY_URL);

            logger.info("Conectado ao libvirt");
        } catch (Exception e) {
            logger.error("Erro ao inicializar", e);
            throw new RuntimeException(e);
        }
    }

    public void collectMetrics() {
        try {
            int[] ids = connection.listDomains();

            if (ids.length == 0) {
                logger.warn("Nenhuma VM rodando");
                return;
            }

            for (int id : ids) {
                collectFromDomain(id);
            }

        } catch (LibvirtException e) {
            logger.error("Erro listando domínios", e);
        }
    }

    private void collectFromDomain(int id) {
        try {
            Domain domain = connection.domainLookupByID(id);
            Map<String, Object> metrics = buildMetrics(domain);
            prometheusWriter.writeMetrics(metrics);

        } catch (Exception e) {
            logger.warn("Erro VM {} -> {}", id, e.getMessage());
        }
    }

    private Map<String, Object> buildMetrics(Domain domain) throws Exception {

        DomainInfo info = domain.getInfo();

        Map<String, Object> m = new HashMap<>();

        String name = domain.getName();

        long cpuNs = info.cpuTime;
        long cpuSec = cpuNs / 1_000_000_000L;

        int vcpus = info.nrVirtCpu;

        long mem = info.memory;
        long maxMem = domain.getMaxMemory();

        m.put("timestamp", System.currentTimeMillis());
        m.put("vm_name", name);
        m.put("vm_uuid", domain.getUUIDString());

        m.put("cpu_time_ns", cpuNs);
        m.put("cpu_time_sec", cpuSec);
        m.put("vcpus", vcpus);
        // Uptime estimado: CPU time total dividido pelo número de vCPUs
        // Isso dá uma estimativa do tempo de execução da VM
        // Nota: Não é o uptime real, mas uma aproximação baseada no uso de CPU
        m.put("uptime_sec", vcpus > 0 ? cpuSec / vcpus : 0);

        m.put("memory_kb", mem);
        m.put("max_memory_kb", maxMem);
        m.put("memory_usage_percent", percent(mem, maxMem));

        m.put("state", info.state.ordinal());

        addNetworkMetrics(domain, m);
        addDiskMetrics(domain, m);

        return m;
    }

    private void addNetworkMetrics(Domain domain, Map<String, Object> m) {
        try {
            String iface = getFirstInterfaceName(domain);
            if (iface != null) {
                var stats = domain.interfaceStats(iface);
                m.put("net_rx_bytes", stats.rx_bytes);
                m.put("net_tx_bytes", stats.tx_bytes);
                return;
            }
        } catch (Exception e) {
            logger.debug("Network stats erro: {}", e.getMessage());
        }

        m.put("net_rx_bytes", 0);
        m.put("net_tx_bytes", 0);
    }

    private void addDiskMetrics(Domain domain, Map<String, Object> m) {
        try {
            String disk = getFirstDiskDevice(domain);
            if (disk != null) {
                var stats = domain.blockStats(disk);
                m.put("disk_read_bytes", stats.rd_bytes);
                m.put("disk_write_bytes", stats.wr_bytes);
                return;
            }
        } catch (Exception e) {
            logger.debug("Disk stats erro: {}", e.getMessage());
        }

        m.put("disk_read_bytes", 0);
        m.put("disk_write_bytes", 0);
    }

    private String getFirstInterfaceName(Domain domain) throws Exception {
        Document doc = getDomainXML(domain);

        // Procurar dentro de elementos <interface>
        NodeList interfaces = doc.getElementsByTagName("interface");
        for (int i = 0; i < interfaces.getLength(); i++) {
            Element iface = (Element) interfaces.item(i);
            NodeList targets = iface.getElementsByTagName("target");
            for (int j = 0; j < targets.getLength(); j++) {
                Element target = (Element) targets.item(j);
                if (target.hasAttribute("dev")) {
                    return target.getAttribute("dev");
                }
            }
        }
        return null;
    }

    private String getFirstDiskDevice(Domain domain) throws Exception {
        Document doc = getDomainXML(domain);

        // Procurar dentro de elementos <disk>
        NodeList disks = doc.getElementsByTagName("disk");
        for (int i = 0; i < disks.getLength(); i++) {
            Element disk = (Element) disks.item(i);
            NodeList targets = disk.getElementsByTagName("target");
            for (int j = 0; j < targets.getLength(); j++) {
                Element target = (Element) targets.item(j);
                if (target.hasAttribute("dev")) {
                    String dev = target.getAttribute("dev");
                    // Aceitar qualquer dispositivo de disco (vd, sd, hd, xvd, etc)
                    if (dev.matches("^(vd|sd|hd|xvd|nvme)[a-z0-9]+")) {
                        return dev;
                    }
                }
            }
        }
        return null;
    }

    private Document getDomainXML(Domain domain) throws Exception {
        String xml = domain.getXMLDesc(0);

        return DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes()));
    }

    private double percent(long used, long max) {
        if (max == 0) return 0;
        return (used * 100.0) / max;
    }

    public void close() {
        try {
            if (connection != null) connection.close();
            if (prometheusWriter != null) prometheusWriter.close();
        } catch (Exception e) {
            logger.error("Erro ao fechar", e);
        }
    }

    public int getCollectIntervalSeconds() {
        return COLLECT_INTERVAL_SECONDS;
    }
}
