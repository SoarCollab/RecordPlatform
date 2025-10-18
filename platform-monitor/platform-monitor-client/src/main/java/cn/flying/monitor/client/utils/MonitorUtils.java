package cn.flying.monitor.client.utils;

import cn.flying.monitor.client.entity.BaseDetail;
import cn.flying.monitor.client.entity.RuntimeDetail;
import cn.flying.monitor.client.plugin.PluginManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OSProcess;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.net.NetworkInterface;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced monitoring utility class with extended system metrics collection
 * Supports network I/O, process monitoring, and configurable metric filtering
 */
@Slf4j
@Component
public class MonitorUtils {
    private final double GB_TO_BYTES = 1024 * 1024 * 1024.0;
    private final double MB_TO_BYTES = 1024 * 1024.0;
    private final double KB_TO_BYTES = 1024.0;
    private final SystemInfo info = new SystemInfo();
    private final Properties properties = System.getProperties();

    @Value("${monitor.client.metrics.enable-network-metrics:true}")
    private boolean enableNetworkMetrics;

    @Value("${monitor.client.metrics.enable-process-metrics:true}")
    private boolean enableProcessMetrics;

    @Value("${monitor.client.metrics.enable-jvm-metrics:true}")
    private boolean enableJvmMetrics;

    @Value("${monitor.client.metrics.top-processes-count:5}")
    private int topProcessesCount;

    @Value("${monitor.client.metrics.sampling-rate:1.0}")
    private double samplingRate;

    @Resource
    private PluginManager pluginManager;

    // Cache for previous measurements to calculate deltas
    private volatile long lastNetworkTimestamp = 0;
    private volatile Map<String, Long> lastNetworkStats = new HashMap<>();
    private volatile long lastDiskTimestamp = 0;
    private volatile Map<String, Long> lastDiskStats = new HashMap<>();

    public BaseDetail monitorBaseDetail() {
        OperatingSystem os = info.getOperatingSystem();
        HardwareAbstractionLayer hardware = info.getHardware();
        double memory = hardware.getMemory().getTotal() / GB_TO_BYTES;
        double diskSize = Arrays.stream(File.listRoots()).mapToLong(File::getTotalSpace).sum() / GB_TO_BYTES;
        String ip = Objects.requireNonNull(this.findNetworkInterface(hardware)).getIPv4addr()[0];
        return new BaseDetail()
                .setOsArch(properties.getProperty("os.arch"))
                .setOsName(os.getFamily())
                .setOsVersion(os.getVersionInfo().getVersion())
                .setOsBit(os.getBitness())
                .setCpuName(hardware.getProcessor().getProcessorIdentifier().getName())
                .setCpuCore(hardware.getProcessor().getLogicalProcessorCount())
                .setMemory(memory)
                .setDisk(diskSize)
                .setIp(ip);
    }

    /**
     * Find the primary network interface with enhanced logic for multiple interfaces and IPv6 support
     * Priority: Active physical interfaces > Virtual interfaces > Any available interface
     */
    private NetworkIF findNetworkInterface(HardwareAbstractionLayer hardware) {
        try {
            List<NetworkIF> candidates = new ArrayList<>();
            List<NetworkIF> fallbackCandidates = new ArrayList<>();
            
            for (NetworkIF network : hardware.getNetworkIFs()) {
                try {
                    NetworkInterface ni = network.queryNetworkInterface();
                    
                    // Skip loopback and point-to-point interfaces
                    if (ni.isLoopback() || ni.isPointToPoint() || !ni.isUp()) {
                        continue;
                    }
                    
                    String[] ipv4Addr = network.getIPv4addr();
                    String[] ipv6Addr = network.getIPv6addr();
                    
                    // Must have at least one IP address (IPv4 or IPv6)
                    if (ipv4Addr.length == 0 && ipv6Addr.length == 0) {
                        continue;
                    }
                    
                    // Prefer physical interfaces over virtual ones
                    if (!ni.isVirtual() && 
                        (ni.getName().startsWith("eth") || ni.getName().startsWith("en") || 
                         ni.getName().startsWith("wlan") || ni.getName().startsWith("wl"))) {
                        
                        // Prioritize interfaces with IPv4 addresses
                        if (ipv4Addr.length > 0) {
                            candidates.add(network);
                        } else if (ipv6Addr.length > 0) {
                            fallbackCandidates.add(network);
                        }
                    } else {
                        // Virtual or other interfaces as fallback
                        fallbackCandidates.add(network);
                    }
                    
                } catch (IOException e) {
                    log.debug("Error querying network interface {}: {}", network.getName(), e.getMessage());
                    continue;
                }
            }
            
            // Return the best candidate
            if (!candidates.isEmpty()) {
                // Sort by interface name to get consistent results
                candidates.sort(Comparator.comparing(NetworkIF::getName));
                NetworkIF selected = candidates.get(0);
                log.debug("Selected primary network interface: {} with IPv4: {}", 
                         selected.getName(), 
                         selected.getIPv4addr().length > 0 ? selected.getIPv4addr()[0] : "none");
                return selected;
            }
            
            if (!fallbackCandidates.isEmpty()) {
                fallbackCandidates.sort(Comparator.comparing(NetworkIF::getName));
                NetworkIF selected = fallbackCandidates.get(0);
                log.debug("Selected fallback network interface: {} with IPv6: {}", 
                         selected.getName(),
                         selected.getIPv6addr().length > 0 ? selected.getIPv6addr()[0] : "none");
                return selected;
            }
            
            log.warn("No suitable network interface found");
            return null;
            
        } catch (Exception e) {
            log.error("Error finding network interface", e);
            return null;
        }
    }
    
    /**
     * Find all active network interfaces (including IPv6)
     */
    public List<NetworkIF> findAllNetworkInterfaces(HardwareAbstractionLayer hardware) {
        List<NetworkIF> activeInterfaces = new ArrayList<>();
        
        try {
            for (NetworkIF network : hardware.getNetworkIFs()) {
                try {
                    NetworkInterface ni = network.queryNetworkInterface();
                    
                    // Include all up interfaces that have IP addresses
                    if (ni.isUp() && !ni.isLoopback() && 
                        (network.getIPv4addr().length > 0 || network.getIPv6addr().length > 0)) {
                        activeInterfaces.add(network);
                    }
                    
                } catch (IOException e) {
                    log.debug("Error querying network interface {}: {}", network.getName(), e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.error("Error finding all network interfaces", e);
        }
        
        return activeInterfaces;
    }

    public RuntimeDetail monitorRuntimeDetail() {
        // Apply sampling rate for performance optimization
        if (Math.random() > samplingRate) {
            log.debug("Skipping metric collection due to sampling rate: {}", samplingRate);
            return null;
        }

        double statisticTime = 0.5;
        try {
            long currentTime = System.currentTimeMillis();
            HardwareAbstractionLayer hardware = info.getHardware();
            OperatingSystem os = info.getOperatingSystem();
            
            RuntimeDetail detail = new RuntimeDetail();
            detail.setTimestamp(currentTime);

            // Basic CPU and memory metrics (existing functionality)
            collectBasicMetrics(detail, hardware, statisticTime);

            // Enhanced network metrics with proper interface discovery
            if (enableNetworkMetrics) {
                collectEnhancedNetworkMetrics(detail, hardware, currentTime);
            }

            // Process metrics
            if (enableProcessMetrics) {
                collectProcessMetrics(detail, os);
            }

            // JVM metrics (if applicable)
            if (enableJvmMetrics) {
                collectJvmMetrics(detail);
            }

            // System load metrics
            collectSystemLoadMetrics(detail, hardware);

            // Enhanced memory breakdown
            collectMemoryBreakdown(detail, hardware);

            // Enhanced disk metrics with I/O statistics
            collectEnhancedDiskMetrics(detail, hardware, currentTime);

            // Collect custom metrics from plugins
            if (pluginManager != null) {
                pluginManager.collectPluginMetrics(detail);
            }

            return detail;

        } catch (Exception e) {
            log.error("Error collecting runtime metrics", e);
            return null;
        }
    }

    /**
     * Collect basic CPU, memory, and disk metrics (existing functionality)
     */
    private void collectBasicMetrics(RuntimeDetail detail, HardwareAbstractionLayer hardware, double statisticTime) throws InterruptedException {
        NetworkIF networkInterface = this.findNetworkInterface(hardware);
        if (networkInterface == null) {
            log.warn("No suitable network interface found for basic metrics");
            return;
        }

        CentralProcessor processor = hardware.getProcessor();
        double upload = networkInterface.getBytesSent();
        double download = networkInterface.getBytesRecv();
        double read = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getReadBytes).sum();
        double write = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getWriteBytes).sum();
        long[] ticks = processor.getSystemCpuLoadTicks();

        Thread.sleep((long) (statisticTime * 1000));

        networkInterface = this.findNetworkInterface(hardware);
        if (networkInterface != null) {
            upload = (networkInterface.getBytesSent() - upload) / statisticTime;
            download = (networkInterface.getBytesRecv() - download) / statisticTime;
        }

        read = (hardware.getDiskStores().stream().mapToLong(HWDiskStore::getReadBytes).sum() - read) / statisticTime;
        write = (hardware.getDiskStores().stream().mapToLong(HWDiskStore::getWriteBytes).sum() - write) / statisticTime;

        double memory = (hardware.getMemory().getTotal() - hardware.getMemory().getAvailable()) / GB_TO_BYTES;
        double disk = Arrays.stream(File.listRoots())
                .mapToLong(file -> file.getTotalSpace() - file.getFreeSpace()).sum() / GB_TO_BYTES;

        detail.setCpuUsage(this.calculateCpuUsage(processor, ticks))
              .setMemoryUsage(memory)
              .setDiskUsage(disk)
              .setNetworkUpload(upload / KB_TO_BYTES)
              .setNetworkDownload(download / KB_TO_BYTES)
              .setDiskRead(read / MB_TO_BYTES)
              .setDiskWrite(write / MB_TO_BYTES);
    }

    /**
     * Collect enhanced network metrics for all interfaces with rate calculations
     */
    private void collectEnhancedNetworkMetrics(RuntimeDetail detail, HardwareAbstractionLayer hardware, long currentTime) {
        try {
            Map<String, RuntimeDetail.NetworkInterfaceMetrics> networkMetrics = new HashMap<>();
            double totalPacketsIn = 0, totalPacketsOut = 0, totalErrors = 0;
            Map<String, Long> currentNetworkStats = new HashMap<>();

            for (NetworkIF networkIF : hardware.getNetworkIFs()) {
                String interfaceName = networkIF.getName();
                
                // Calculate rates if we have previous measurements
                double bytesRecvRate = 0, bytesSentRate = 0;
                if (lastNetworkTimestamp > 0 && currentTime > lastNetworkTimestamp) {
                    double timeDelta = (currentTime - lastNetworkTimestamp) / 1000.0; // seconds
                    
                    Long prevBytesRecv = lastNetworkStats.get(interfaceName + "_recv");
                    Long prevBytesSent = lastNetworkStats.get(interfaceName + "_sent");
                    
                    if (prevBytesRecv != null && prevBytesSent != null) {
                        bytesRecvRate = (networkIF.getBytesRecv() - prevBytesRecv) / timeDelta;
                        bytesSentRate = (networkIF.getBytesSent() - prevBytesSent) / timeDelta;
                    }
                }

                // Store current values for next calculation
                currentNetworkStats.put(interfaceName + "_recv", networkIF.getBytesRecv());
                currentNetworkStats.put(interfaceName + "_sent", networkIF.getBytesSent());

                RuntimeDetail.NetworkInterfaceMetrics metrics = new RuntimeDetail.NetworkInterfaceMetrics()
                        .setInterfaceName(interfaceName)
                        .setBytesReceived(networkIF.getBytesRecv())
                        .setBytesSent(networkIF.getBytesSent())
                        .setPacketsReceived(networkIF.getPacketsRecv())
                        .setPacketsSent(networkIF.getPacketsSent())
                        .setErrorsReceived(networkIF.getInErrors())
                        .setErrorsSent(networkIF.getOutErrors())
                        .setUp(networkIF.getSpeed() > 0)
                        .setBytesReceivedRate(bytesRecvRate)
                        .setBytesSentRate(bytesSentRate);

                // Set IP address (prefer IPv4, fallback to IPv6)
                String[] ipv4Addresses = networkIF.getIPv4addr();
                String[] ipv6Addresses = networkIF.getIPv6addr();
                
                if (ipv4Addresses.length > 0) {
                    metrics.setIpAddress(ipv4Addresses[0]);
                } else if (ipv6Addresses.length > 0) {
                    metrics.setIpAddress(ipv6Addresses[0]);
                }

                networkMetrics.put(interfaceName, metrics);
                totalPacketsIn += networkIF.getPacketsRecv();
                totalPacketsOut += networkIF.getPacketsSent();
                totalErrors += networkIF.getInErrors() + networkIF.getOutErrors();
            }

            // Update cached values
            lastNetworkTimestamp = currentTime;
            lastNetworkStats = currentNetworkStats;

            detail.setNetworkInterfaces(networkMetrics);
            detail.setTotalNetworkPacketsIn(totalPacketsIn);
            detail.setTotalNetworkPacketsOut(totalPacketsOut);
            detail.setNetworkErrorRate(totalErrors / Math.max(1, totalPacketsIn + totalPacketsOut));

        } catch (Exception e) {
            log.warn("Error collecting enhanced network metrics", e);
        }
    }

    /**
     * Collect top processes by CPU and memory usage
     */
    private void collectProcessMetrics(RuntimeDetail detail, OperatingSystem os) {
        try {
            List<OSProcess> processes = os.getProcesses(null, null, topProcessesCount);
            
            List<RuntimeDetail.ProcessMetrics> topProcesses = processes.stream()
                    .map(process -> new RuntimeDetail.ProcessMetrics()
                            .setPid(process.getProcessID())
                            .setName(process.getName())
                            .setCommand(process.getCommandLine())
                            .setCpuUsage(process.getProcessCpuLoadCumulative())
                            .setMemoryUsage(process.getResidentSetSize() / MB_TO_BYTES)
                            .setMemoryBytes(process.getResidentSetSize())
                            .setState(process.getState().name())
                            .setStartTime(process.getStartTime()))
                    .collect(Collectors.toList());

            detail.setTopProcesses(topProcesses);
            detail.setTotalProcessCount(os.getProcessCount());
            detail.setRunningProcessCount((int) processes.stream()
                    .filter(p -> p.getState() == OSProcess.State.RUNNING)
                    .count());

        } catch (Exception e) {
            log.debug("Error collecting process metrics", e);
        }
    }

    /**
     * Collect JVM metrics if running on JVM
     */
    private void collectJvmMetrics(RuntimeDetail detail) {
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            
            RuntimeDetail.JvmMetrics jvmMetrics = new RuntimeDetail.JvmMetrics()
                    .setHeapUsedMB(memoryBean.getHeapMemoryUsage().getUsed() / MB_TO_BYTES)
                    .setHeapMaxMB(memoryBean.getHeapMemoryUsage().getMax() / MB_TO_BYTES)
                    .setNonHeapUsedMB(memoryBean.getNonHeapMemoryUsage().getUsed() / MB_TO_BYTES)
                    .setNonHeapMaxMB(memoryBean.getNonHeapMemoryUsage().getMax() / MB_TO_BYTES)
                    .setThreadCount(threadBean.getThreadCount())
                    .setDaemonThreadCount(threadBean.getDaemonThreadCount());

            // GC metrics
            ManagementFactory.getGarbageCollectorMXBeans().forEach(gcBean -> {
                jvmMetrics.setGcCollectionCount(jvmMetrics.getGcCollectionCount() + gcBean.getCollectionCount());
                jvmMetrics.setGcCollectionTime(jvmMetrics.getGcCollectionTime() + gcBean.getCollectionTime());
            });

            detail.setJvmMetrics(jvmMetrics);

        } catch (Exception e) {
            log.debug("Error collecting JVM metrics", e);
        }
    }

    /**
     * Collect system load metrics
     */
    private void collectSystemLoadMetrics(RuntimeDetail detail, HardwareAbstractionLayer hardware) {
        try {
            CentralProcessor processor = hardware.getProcessor();
            double[] loadAverage = processor.getSystemLoadAverage(3);
            
            if (loadAverage.length >= 1) detail.setLoadAverage1min(loadAverage[0]);
            if (loadAverage.length >= 2) detail.setLoadAverage5min(loadAverage[1]);
            if (loadAverage.length >= 3) detail.setLoadAverage15min(loadAverage[2]);

        } catch (Exception e) {
            log.debug("Error collecting system load metrics", e);
        }
    }

    /**
     * Collect detailed memory breakdown
     */
    private void collectMemoryBreakdown(RuntimeDetail detail, HardwareAbstractionLayer hardware) {
        try {
            var memory = hardware.getMemory();
            
            detail.setMemoryUsedGB((memory.getTotal() - memory.getAvailable()) / GB_TO_BYTES);
            detail.setMemoryAvailableGB(memory.getAvailable() / GB_TO_BYTES);
            
            var swapMemory = memory.getVirtualMemory();
            detail.setSwapUsedGB(swapMemory.getSwapUsed() / GB_TO_BYTES);
            detail.setSwapTotalGB(swapMemory.getSwapTotal() / GB_TO_BYTES);

        } catch (Exception e) {
            log.debug("Error collecting memory breakdown", e);
        }
    }

    /**
     * Collect enhanced disk metrics per mount point with I/O rate calculations
     */
    private void collectEnhancedDiskMetrics(RuntimeDetail detail, HardwareAbstractionLayer hardware, long currentTime) {
        try {
            Map<String, RuntimeDetail.DiskMetrics> diskMetrics = new HashMap<>();
            Map<String, Long> currentDiskStats = new HashMap<>();
            
            // File system metrics
            for (File root : File.listRoots()) {
                String mountPoint = root.getAbsolutePath();
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                long used = total - free;
                
                RuntimeDetail.DiskMetrics metrics = new RuntimeDetail.DiskMetrics()
                        .setMountPoint(mountPoint)
                        .setTotalGB(total / GB_TO_BYTES)
                        .setUsedGB(used / GB_TO_BYTES)
                        .setAvailableGB(free / GB_TO_BYTES)
                        .setUsagePercent(total > 0 ? (double) used / total * 100 : 0);
                
                diskMetrics.put(mountPoint, metrics);
            }
            
            // Disk I/O metrics with rate calculations
            for (HWDiskStore diskStore : hardware.getDiskStores()) {
                String diskName = diskStore.getName();
                long readBytes = diskStore.getReadBytes();
                long writeBytes = diskStore.getWriteBytes();
                long reads = diskStore.getReads();
                long writes = diskStore.getWrites();
                
                // Calculate rates if we have previous measurements
                double readBytesRate = 0, writeBytesRate = 0, readOpsRate = 0, writeOpsRate = 0;
                
                if (lastDiskTimestamp > 0 && currentTime > lastDiskTimestamp) {
                    double timeDelta = (currentTime - lastDiskTimestamp) / 1000.0; // seconds
                    
                    Long prevReadBytes = lastDiskStats.get(diskName + "_readBytes");
                    Long prevWriteBytes = lastDiskStats.get(diskName + "_writeBytes");
                    Long prevReads = lastDiskStats.get(diskName + "_reads");
                    Long prevWrites = lastDiskStats.get(diskName + "_writes");
                    
                    if (prevReadBytes != null && prevWriteBytes != null && 
                        prevReads != null && prevWrites != null && timeDelta > 0) {
                        readBytesRate = (readBytes - prevReadBytes) / timeDelta / MB_TO_BYTES; // MB/s
                        writeBytesRate = (writeBytes - prevWriteBytes) / timeDelta / MB_TO_BYTES; // MB/s
                        readOpsRate = (reads - prevReads) / timeDelta;
                        writeOpsRate = (writes - prevWrites) / timeDelta;
                    }
                }
                
                // Store current values for next calculation
                currentDiskStats.put(diskName + "_readBytes", readBytes);
                currentDiskStats.put(diskName + "_writeBytes", writeBytes);
                currentDiskStats.put(diskName + "_reads", reads);
                currentDiskStats.put(diskName + "_writes", writes);
                
                // Apply I/O metrics to the first disk metrics (simplified mapping)
                // In a more sophisticated implementation, you'd map disk stores to mount points
                if (!diskMetrics.isEmpty()) {
                    RuntimeDetail.DiskMetrics firstDisk = diskMetrics.values().iterator().next();
                    firstDisk.setReadBytesPerSec(Math.max(firstDisk.getReadBytesPerSec(), readBytesRate))
                            .setWriteBytesPerSec(Math.max(firstDisk.getWriteBytesPerSec(), writeBytesRate))
                            .setReadOpsPerSec(Math.max(firstDisk.getReadOpsPerSec(), readOpsRate))
                            .setWriteOpsPerSec(Math.max(firstDisk.getWriteOpsPerSec(), writeOpsRate));
                }
            }
            
            // Update cached values
            lastDiskTimestamp = currentTime;
            lastDiskStats = currentDiskStats;
            
            detail.setDiskMountPoints(diskMetrics);

        } catch (Exception e) {
            log.warn("Error collecting enhanced disk metrics", e);
        }
    }

    /**
     * Add custom metric (for plugin support)
     */
    public void addCustomMetric(RuntimeDetail detail, String key, Object value) {
        if (detail.getCustomMetrics() == null) {
            detail.setCustomMetrics(new HashMap<>());
        }
        detail.getCustomMetrics().put(key, value);
    }

    /**
     * Filter metrics based on configuration (for sampling)
     */
    public RuntimeDetail filterMetrics(RuntimeDetail detail) {
        if (detail == null) return null;
        
        // Apply metric filtering based on configuration
        if (!enableNetworkMetrics) {
            detail.setNetworkInterfaces(null);
            detail.setTotalNetworkPacketsIn(0);
            detail.setTotalNetworkPacketsOut(0);
            detail.setNetworkErrorRate(0);
        }
        
        if (!enableProcessMetrics) {
            detail.setTopProcesses(null);
            detail.setTotalProcessCount(0);
            detail.setRunningProcessCount(0);
        }
        
        if (!enableJvmMetrics) {
            detail.setJvmMetrics(null);
        }
        
        return detail;
    }

    /**
     * Calculate CPU usage with proper tick handling and error checking
     */
    private double calculateCpuUsage(CentralProcessor processor, long[] prevTicks) {
        try {
            long[] ticks = processor.getSystemCpuLoadTicks();
            
            // Validate input arrays
            if (prevTicks == null || ticks == null || 
                prevTicks.length != ticks.length || 
                ticks.length < CentralProcessor.TickType.values().length) {
                log.warn("Invalid CPU tick arrays, returning 0 CPU usage");
                return 0.0;
            }
            
            // Calculate tick differences with overflow protection
            long nice = safeDifference(ticks[CentralProcessor.TickType.NICE.getIndex()],
                                     prevTicks[CentralProcessor.TickType.NICE.getIndex()]);
            long irq = safeDifference(ticks[CentralProcessor.TickType.IRQ.getIndex()],
                                    prevTicks[CentralProcessor.TickType.IRQ.getIndex()]);
            long softIrq = safeDifference(ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()],
                                        prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()]);
            long steal = safeDifference(ticks[CentralProcessor.TickType.STEAL.getIndex()],
                                      prevTicks[CentralProcessor.TickType.STEAL.getIndex()]);
            long cSys = safeDifference(ticks[CentralProcessor.TickType.SYSTEM.getIndex()],
                                     prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()]);
            long cUser = safeDifference(ticks[CentralProcessor.TickType.USER.getIndex()],
                                      prevTicks[CentralProcessor.TickType.USER.getIndex()]);
            long ioWait = safeDifference(ticks[CentralProcessor.TickType.IOWAIT.getIndex()],
                                       prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()]);
            long idle = safeDifference(ticks[CentralProcessor.TickType.IDLE.getIndex()],
                                     prevTicks[CentralProcessor.TickType.IDLE.getIndex()]);
            
            long totalCpu = cUser + nice + cSys + idle + ioWait + irq + softIrq + steal;
            
            // Avoid division by zero
            if (totalCpu <= 0) {
                log.debug("Total CPU ticks is zero or negative, returning 0 CPU usage");
                return 0.0;
            }
            
            double cpuUsage = (cSys + cUser) * 1.0 / totalCpu;
            
            // Clamp to valid range [0.0, 1.0]
            return Math.max(0.0, Math.min(1.0, cpuUsage));
            
        } catch (Exception e) {
            log.warn("Error calculating CPU usage: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate per-core CPU usage
     */
    public double[] calculatePerCoreCpuUsage(CentralProcessor processor, long[][] prevTicks) {
        try {
            int logicalProcessorCount = processor.getLogicalProcessorCount();
            double[] perCoreUsage = new double[logicalProcessorCount];
            
            long[][] currentTicks = processor.getProcessorCpuLoadTicks();
            
            // Validate input
            if (prevTicks == null || currentTicks == null || 
                prevTicks.length != currentTicks.length ||
                prevTicks.length != logicalProcessorCount) {
                log.warn("Invalid per-core CPU tick arrays");
                return new double[logicalProcessorCount]; // Return zeros
            }
            
            for (int i = 0; i < logicalProcessorCount; i++) {
                if (prevTicks[i] != null && currentTicks[i] != null &&
                    prevTicks[i].length == currentTicks[i].length) {
                    perCoreUsage[i] = calculateCpuUsage(processor, prevTicks[i]);
                } else {
                    perCoreUsage[i] = 0.0;
                }
            }
            
            return perCoreUsage;
            
        } catch (Exception e) {
            log.warn("Error calculating per-core CPU usage: {}", e.getMessage());
            return new double[processor.getLogicalProcessorCount()];
        }
    }
    
    /**
     * Safe difference calculation to handle counter overflow
     */
    private long safeDifference(long current, long previous) {
        if (current >= previous) {
            return current - previous;
        } else {
            // Handle counter overflow (assuming 64-bit counters)
            log.debug("CPU counter overflow detected: current={}, previous={}", current, previous);
            return current + (Long.MAX_VALUE - previous);
        }
    }
    
    /**
     * Get CPU usage breakdown with detailed metrics
     */
    public Map<String, Double> getCpuUsageBreakdown(CentralProcessor processor, long[] prevTicks) {
        Map<String, Double> breakdown = new HashMap<>();
        
        try {
            long[] ticks = processor.getSystemCpuLoadTicks();
            
            if (prevTicks == null || ticks == null || 
                prevTicks.length != ticks.length || 
                ticks.length < CentralProcessor.TickType.values().length) {
                return breakdown; // Return empty map
            }
            
            long nice = safeDifference(ticks[CentralProcessor.TickType.NICE.getIndex()],
                                     prevTicks[CentralProcessor.TickType.NICE.getIndex()]);
            long irq = safeDifference(ticks[CentralProcessor.TickType.IRQ.getIndex()],
                                    prevTicks[CentralProcessor.TickType.IRQ.getIndex()]);
            long softIrq = safeDifference(ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()],
                                        prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()]);
            long steal = safeDifference(ticks[CentralProcessor.TickType.STEAL.getIndex()],
                                      prevTicks[CentralProcessor.TickType.STEAL.getIndex()]);
            long cSys = safeDifference(ticks[CentralProcessor.TickType.SYSTEM.getIndex()],
                                     prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()]);
            long cUser = safeDifference(ticks[CentralProcessor.TickType.USER.getIndex()],
                                      prevTicks[CentralProcessor.TickType.USER.getIndex()]);
            long ioWait = safeDifference(ticks[CentralProcessor.TickType.IOWAIT.getIndex()],
                                       prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()]);
            long idle = safeDifference(ticks[CentralProcessor.TickType.IDLE.getIndex()],
                                     prevTicks[CentralProcessor.TickType.IDLE.getIndex()]);
            
            long totalCpu = cUser + nice + cSys + idle + ioWait + irq + softIrq + steal;
            
            if (totalCpu > 0) {
                breakdown.put("user", cUser * 1.0 / totalCpu);
                breakdown.put("system", cSys * 1.0 / totalCpu);
                breakdown.put("nice", nice * 1.0 / totalCpu);
                breakdown.put("idle", idle * 1.0 / totalCpu);
                breakdown.put("iowait", ioWait * 1.0 / totalCpu);
                breakdown.put("irq", irq * 1.0 / totalCpu);
                breakdown.put("softirq", softIrq * 1.0 / totalCpu);
                breakdown.put("steal", steal * 1.0 / totalCpu);
                breakdown.put("total_active", (cSys + cUser) * 1.0 / totalCpu);
            }
            
        } catch (Exception e) {
            log.warn("Error calculating CPU usage breakdown: {}", e.getMessage());
        }
        
        return breakdown;
    }
}
