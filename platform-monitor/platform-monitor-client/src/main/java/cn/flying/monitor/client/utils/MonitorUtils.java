package cn.flying.monitor.client.utils;

import cn.flying.monitor.client.entity.BaseDetail;
import cn.flying.monitor.client.entity.RuntimeDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;

import java.io.File;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

/**
 * @program: monitor
 * @description: 获取主机信息工具类
 * @author: 王贝强
 * @create: 2024-07-14 22:32
 */
@Slf4j
@Component
public class MonitorUtils {
    private final double GB_TO_BYTES = 1024 * 1024 * 1024.0;
    private final double MB_TO_BYTES = 1024 * 1024.0;
    private final double KB_TO_BYTES = 1024.0;
    private final SystemInfo info = new SystemInfo();
    private final Properties properties = System.getProperties();

    public BaseDetail monitorBaseDetail() {
        OperatingSystem os = info.getOperatingSystem();
        HardwareAbstractionLayer hardware = info.getHardware();
        double memory = hardware.getMemory().getTotal() / GB_TO_BYTES;
        double diskSize = Arrays.stream(File.listRoots()).mapToLong(File::getTotalSpace).sum() / GB_TO_BYTES;
        NetworkIF nif = this.findNetworkInterface(hardware);
        String ip = "127.0.0.1";
        try {
            if (nif != null && nif.getIPv4addr() != null && nif.getIPv4addr().length > 0) {
                ip = nif.getIPv4addr()[0];
            } else {
                log.warn("未找到可用IPv4网络接口，使用回退地址 127.0.0.1");
            }
        } catch (Exception e) {
            log.warn("解析网络接口地址失败，使用回退地址 127.0.0.1", e);
        }
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

    private NetworkIF findNetworkInterface(HardwareAbstractionLayer hardware) {
        try {
            for (NetworkIF network : hardware.getNetworkIFs()) {
                String[] ipv4Addr = network.getIPv4addr();
                NetworkInterface ni = network.queryNetworkInterface();
                if (ni != null && ni.isUp() && !ni.isLoopback() && ipv4Addr != null && ipv4Addr.length > 0) {
                    return network;
                }
            }
            // 退而求其次：仅按IPv4是否存在选择
            for (NetworkIF network : hardware.getNetworkIFs()) {
                String[] ipv4Addr = network.getIPv4addr();
                if (ipv4Addr != null && ipv4Addr.length > 0) return network;
            }
        } catch (Exception e) {
            log.warn("读取网络接口信息时出错，使用回退策略", e);
        }
        return null;
    }

    public RuntimeDetail monitorRuntimeDetail() {
        double statisticTime = 0.5;
        try {
            HardwareAbstractionLayer hardware = info.getHardware();
            NetworkIF networkInterface = this.findNetworkInterface(hardware);
            CentralProcessor processor = hardware.getProcessor();
            double upload = 0, download = 0;
            double read = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getReadBytes).sum();
            double write = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getWriteBytes).sum();
            long[] ticks = processor.getSystemCpuLoadTicks();
            if (networkInterface != null) {
                upload = networkInterface.getBytesSent();
                download = networkInterface.getBytesRecv();
            }
            Thread.sleep((long) (statisticTime * 1000));
            if (networkInterface != null) {
                networkInterface = this.findNetworkInterface(hardware);
            }
            double uploadNow = 0, downloadNow = 0;
            if (networkInterface != null) {
                uploadNow = networkInterface.getBytesSent();
                downloadNow = networkInterface.getBytesRecv();
            }
            double readNow = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getReadBytes).sum();
            double writeNow = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getWriteBytes).sum();
            double memory = (hardware.getMemory().getTotal() - hardware.getMemory().getAvailable()) / GB_TO_BYTES;
            double disk = Arrays.stream(File.listRoots())
                    .mapToLong(file -> file.getTotalSpace() - file.getFreeSpace()).sum() / GB_TO_BYTES;
            return new RuntimeDetail()
                    .setCpuUsage(this.calculateCpuUsage(processor, ticks))
                    .setMemoryUsage(memory)
                    .setDiskUsage(disk)
                    .setNetworkUpload((uploadNow - upload) / statisticTime / KB_TO_BYTES)
                    .setNetworkDownload((downloadNow - download) / statisticTime / KB_TO_BYTES)
                    .setDiskRead((readNow - read) / statisticTime / MB_TO_BYTES)
                    .setDiskWrite((writeNow - write) / statisticTime / MB_TO_BYTES)
                    .setTimestamp(new Date().getTime());
        } catch (Exception e) {
            log.error("读取运行时数据出现问题", e);
        }
        return null;
    }

    private double calculateCpuUsage(CentralProcessor processor, long[] prevTicks) {
        long[] ticks = processor.getSystemCpuLoadTicks();
        long nice = ticks[CentralProcessor.TickType.NICE.getIndex()]
                - prevTicks[CentralProcessor.TickType.NICE.getIndex()];
        long irq = ticks[CentralProcessor.TickType.IRQ.getIndex()]
                - prevTicks[CentralProcessor.TickType.IRQ.getIndex()];
        long softIrq = ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()]
                - prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        long steal = ticks[CentralProcessor.TickType.STEAL.getIndex()]
                - prevTicks[CentralProcessor.TickType.STEAL.getIndex()];
        long cSys = ticks[CentralProcessor.TickType.SYSTEM.getIndex()]
                - prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()];
        long cUser = ticks[CentralProcessor.TickType.USER.getIndex()]
                - prevTicks[CentralProcessor.TickType.USER.getIndex()];
        long ioWait = ticks[CentralProcessor.TickType.IOWAIT.getIndex()]
                - prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()];
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()]
                - prevTicks[CentralProcessor.TickType.IDLE.getIndex()];
        long totalCpu = cUser + nice + cSys + idle + ioWait + irq + softIrq + steal;
        return (cSys + cUser) * 1.0 / totalCpu;
    }
}
