package cn.flying.storage.service;

import cn.flying.storage.config.FaultDomainConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StandbyPoolManager Unit Tests")
class StandbyPoolManagerTest {

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private S3Monitor s3Monitor;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private StandbyPoolManager manager;

    @Nested
    @DisplayName("Skip Conditions Tests")
    class SkipConditionsTests {

        @Test
        @DisplayName("Should skip check when fault domains not enabled")
        void shouldSkipCheckWhenFaultDomainsNotEnabled() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(false);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).isStandbyEnabled();
            verify(faultDomainManager, never()).getActiveDomains();
        }

        @Test
        @DisplayName("Should skip check when standby not enabled")
        void shouldSkipCheckWhenStandbyNotEnabled() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(true);
            when(faultDomainManager.isStandbyEnabled()).thenReturn(false);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).getActiveDomains();
        }
    }

    @Nested
    @DisplayName("Health Check Tests")
    class HealthCheckTests {

        @BeforeEach
        void setUp() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(true);
            when(faultDomainManager.isStandbyEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Should not promote when healthy nodes meet minimum requirement")
        void shouldNotPromoteWhenHealthyNodesMeetMinimum() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(2);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).getHealthyStandbyNodes();
            verify(faultDomainManager, never()).changeNodeDomain(anyString(), anyString());
        }

        @Test
        @DisplayName("Should check all active domains")
        void shouldCheckAllActiveDomains() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.countHealthyNodesInDomain(anyString())).thenReturn(2);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig(anyString())).thenReturn(config);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager).countHealthyNodesInDomain("domain-A");
            verify(faultDomainManager).countHealthyNodesInDomain("domain-B");
        }

        @Test
        @DisplayName("Should use default minimum of 1 when config not set")
        void shouldUseDefaultMinimumWhenConfigNotSet() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(1);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(null);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).getHealthyStandbyNodes();
        }

        @Test
        @DisplayName("Should use default minimum when minNodes is null in config")
        void shouldUseDefaultMinimumWhenMinNodesIsNull() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(1);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(null);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).getHealthyStandbyNodes();
        }
    }

    @Nested
    @DisplayName("Standby Node Promotion Tests")
    class StandbyNodePromotionTests {

        @BeforeEach
        void setUp() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(true);
            when(faultDomainManager.isStandbyEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Should promote standby nodes when healthy nodes below minimum")
        void shouldPromoteStandbyNodesWhenBelowMinimum() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(1);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1", "standby-2"));
            when(faultDomainManager.changeNodeDomain("standby-1", "domain-A")).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager).changeNodeDomain("standby-1", "domain-A");
            verify(eventPublisher).publishEvent(any(NodeTopologyChangeEvent.class));
        }

        @Test
        @DisplayName("Should promote multiple standby nodes based on deficit")
        void shouldPromoteMultipleStandbyNodesBasedOnDeficit() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(0);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1", "standby-2", "standby-3"));
            when(faultDomainManager.changeNodeDomain(anyString(), eq("domain-A"))).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, times(2)).changeNodeDomain(anyString(), eq("domain-A"));
            verify(eventPublisher, times(2)).publishEvent(any(NodeTopologyChangeEvent.class));
        }

        @Test
        @DisplayName("Should not promote more than needed")
        void shouldNotPromoteMoreThanNeeded() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(1);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1", "standby-2", "standby-3"));
            when(faultDomainManager.changeNodeDomain(anyString(), eq("domain-A"))).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, times(1)).changeNodeDomain(anyString(), eq("domain-A"));
        }

        @Test
        @DisplayName("Should handle no healthy standby nodes available")
        void shouldHandleNoHealthyStandbyNodesAvailable() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(0);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Collections.emptyList());

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).changeNodeDomain(anyString(), anyString());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Should handle promotion failure gracefully")
        void shouldHandlePromotionFailureGracefully() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(0);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(2);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1", "standby-2"));
            when(faultDomainManager.changeNodeDomain("standby-1", "domain-A")).thenReturn(false);
            when(faultDomainManager.changeNodeDomain("standby-2", "domain-A")).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager).changeNodeDomain("standby-1", "domain-A");
            verify(faultDomainManager).changeNodeDomain("standby-2", "domain-A");
            verify(eventPublisher, times(1)).publishEvent(any(NodeTopologyChangeEvent.class));
        }

        @Test
        @DisplayName("Should continue promoting after one failure")
        void shouldContinuePromotingAfterOneFailure() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(0);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(3);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1", "standby-2", "standby-3", "standby-4"));
            when(faultDomainManager.changeNodeDomain("standby-1", "domain-A")).thenReturn(true);
            when(faultDomainManager.changeNodeDomain("standby-2", "domain-A")).thenReturn(false);
            when(faultDomainManager.changeNodeDomain("standby-3", "domain-A")).thenReturn(true);
            when(faultDomainManager.changeNodeDomain("standby-4", "domain-A")).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(eventPublisher, times(3)).publishEvent(any(NodeTopologyChangeEvent.class));
        }
    }

    @Nested
    @DisplayName("Event Publishing Tests")
    class EventPublishingTests {

        @Captor
        private ArgumentCaptor<NodeTopologyChangeEvent> eventCaptor;

        @BeforeEach
        void setUp() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(true);
            when(faultDomainManager.isStandbyEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Should publish correct event on promotion")
        void shouldPublishCorrectEventOnPromotion() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(0);

            FaultDomainConfig config = new FaultDomainConfig();
            config.setMinNodes(1);
            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(config);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1"));
            when(faultDomainManager.changeNodeDomain("standby-1", "domain-A")).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(eventPublisher).publishEvent(eventCaptor.capture());

            NodeTopologyChangeEvent event = eventCaptor.getValue();
            assertThat(event.getNodeName()).isEqualTo("standby-1");
            assertThat(event.getChangeType()).isEqualTo(NodeTopologyChangeEvent.TopologyChangeType.NODE_DOMAIN_CHANGED);
            assertThat(event.getFaultDomain()).isEqualTo("domain-A");
            assertThat(event.getSource()).isEqualTo(manager);
        }
    }

    @Nested
    @DisplayName("Multiple Domains Tests")
    class MultipleDomainsTests {

        @BeforeEach
        void setUp() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(true);
            when(faultDomainManager.isStandbyEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Should handle multiple domains with different health statuses")
        void shouldHandleMultipleDomainsWithDifferentHealthStatuses() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));

            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(2);
            when(faultDomainManager.countHealthyNodesInDomain("domain-B")).thenReturn(0);

            FaultDomainConfig configA = new FaultDomainConfig();
            configA.setMinNodes(2);
            FaultDomainConfig configB = new FaultDomainConfig();
            configB.setMinNodes(1);

            when(faultDomainManager.getDomainConfig("domain-A")).thenReturn(configA);
            when(faultDomainManager.getDomainConfig("domain-B")).thenReturn(configB);

            when(faultDomainManager.getHealthyStandbyNodes()).thenReturn(Arrays.asList("standby-1"));
            when(faultDomainManager.changeNodeDomain("standby-1", "domain-B")).thenReturn(true);

            manager.checkDomainHealthAndPromote();

            verify(faultDomainManager, never()).changeNodeDomain(anyString(), eq("domain-A"));
            verify(faultDomainManager).changeNodeDomain("standby-1", "domain-B");
        }
    }

    @Nested
    @DisplayName("Manual Trigger Tests")
    class ManualTriggerTests {

        @Test
        @DisplayName("Should trigger health check manually")
        void shouldTriggerHealthCheckManually() {
            when(faultDomainManager.isUsingFaultDomains()).thenReturn(true);
            when(faultDomainManager.isStandbyEnabled()).thenReturn(true);
            when(faultDomainManager.getActiveDomains()).thenReturn(Collections.emptyList());

            manager.triggerHealthCheck();

            verify(faultDomainManager).isUsingFaultDomains();
            verify(faultDomainManager).isStandbyEnabled();
            verify(faultDomainManager).getActiveDomains();
        }
    }
}
