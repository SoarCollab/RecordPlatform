package cn.flying.monitor.client.config;

import cn.flying.monitor.client.entity.ConnectionConfig;
import cn.flying.monitor.client.security.CertificateManager;
import cn.flying.monitor.client.utils.MonitorUtils;
import cn.flying.monitor.client.utils.NetUtils;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Enhanced server configuration with certificate-based authentication support
 */
@Slf4j
@Component
public class ServerConfiguration implements ApplicationRunner {

    @Resource
    private NetUtils net;

    @Resource
    private MonitorUtils monitor;

    @Resource
    private CertificateManager certificateManager;

    @Value("${monitor.client.id}")
    private String clientId;

    private ConnectionConfig currentConfig;

    @Bean
    public ConnectionConfig connectionConfig() {
        log.info("Creating ConnectionConfig bean...");
        if (currentConfig == null) {
            currentConfig = loadOrCreateConfiguration();
        }
        return currentConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("Starting client initialization for client ID: {}", clientId);
            
            // Initialize certificate manager first
            certificateManager.initialize();
            
            // Register with server using certificate authentication
            if (registerWithServer(currentConfig)) {
                log.info("Client registration successful, updating base system information...");
                net.updateBaseDetails(monitor.monitorBaseDetail());
                log.info("Client initialization completed successfully");
            } else {
                log.error("Client registration failed, please check configuration and server connectivity");
                System.exit(1);
            }
            
        } catch (Exception e) {
            log.error("Error during client initialization", e);
            System.exit(1);
        }
    }

    private ConnectionConfig loadOrCreateConfiguration() {
        log.info("Loading client configuration...");
        
        ConnectionConfig config = readFromLocalJSONFile();
        if (config == null) {
            log.info("No existing configuration found, creating new configuration...");
            config = createNewConfiguration();
        } else {
            // Update configuration with current settings
            config.setClientId(clientId);
            config.setCertificateAuthEnabled(true);
            log.info("Loaded existing configuration for client: {}", config.getClientId());
        }
        
        return config;
    }

    private ConnectionConfig readFromLocalJSONFile() {
        File configurationFile = new File("config/server.json");
        if (configurationFile.exists()) {
            try (FileInputStream stream = new FileInputStream(configurationFile)) {
                String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                ConnectionConfig config = JSONObject.parseObject(raw).to(ConnectionConfig.class);
                
                // Migrate old configuration to new format
                if (config.getClientId() == null) {
                    config.setClientId(clientId);
                    config.setCertificateAuthEnabled(true);
                    saveConfigurationToFile(config);
                    log.info("Migrated configuration to certificate-based authentication");
                }
                
                return config;
            } catch (IOException e) {
                log.error("Error reading configuration file", e);
            }
        }
        return null;
    }

    private ConnectionConfig createNewConfiguration() {
        try (Scanner scanner = new Scanner(System.in)) {
            String address;
            
            log.info("=== Monitor Client Configuration ===");
            log.info("Client ID: {}", clientId);
            log.info("Certificate fingerprint: {}", certificateManager.getCertificateFingerprint());
            
            do {
                log.info("Please enter the server address (e.g., 'https://192.168.0.100:8080'):");
                address = scanner.nextLine().trim();
                
                if (address.isEmpty()) {
                    log.warn("Server address cannot be empty");
                    continue;
                }
                
                // Create temporary config for registration test
                ConnectionConfig tempConfig = new ConnectionConfig();
                tempConfig.setAddress(address);
                tempConfig.setClientId(clientId);
                tempConfig.setCertificateAuthEnabled(true);
                
                if (testServerConnection(tempConfig)) {
                    break;
                } else {
                    log.error("Failed to connect to server at: {}", address);
                    log.info("Please verify the server address and ensure the server is running");
                }
                
            } while (true);
            
            ConnectionConfig config = new ConnectionConfig();
            config.setAddress(address);
            config.setClientId(clientId);
            config.setCertificateAuthEnabled(true);
            config.setCompressionEnabled(true);
            
            saveConfigurationToFile(config);
            return config;
            
        } catch (Exception e) {
            log.error("Error creating configuration", e);
            throw new RuntimeException("Failed to create client configuration", e);
        }
    }

    private boolean testServerConnection(ConnectionConfig config) {
        try {
            // Test connection without modifying the current config
            return net.isServerReachable();
        } catch (Exception e) {
            log.debug("Server connection test failed", e);
            return false;
        }
    }

    private boolean registerWithServer(ConnectionConfig config) {
        try {
            // Register using certificate authentication
            return net.registerToServer(config.getAddress());
            
        } catch (Exception e) {
            log.error("Error during server registration", e);
            return false;
        }
    }

    private void saveConfigurationToFile(ConnectionConfig config) {
        File dir = new File("config");
        if (!dir.exists() && dir.mkdirs()) {
            log.info("Created configuration directory: config/");
        }
        
        File file = new File("config/server.json");
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(JSONObject.from(config).toJSONString());
            log.info("Configuration saved successfully to: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Error saving configuration file", e);
        }
    }
}
