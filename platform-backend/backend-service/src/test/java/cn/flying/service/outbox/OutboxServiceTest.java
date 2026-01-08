package cn.flying.service.outbox;

import cn.flying.common.util.Const;
import cn.flying.dao.entity.OutboxEvent;
import cn.flying.dao.mapper.OutboxEventMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("OutboxService Tests")
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventMapper outboxMapper;

    @InjectMocks
    private OutboxService outboxService;

    private static final String TEST_AGGREGATE_TYPE = "FILE";
    private static final Long TEST_AGGREGATE_ID = 123L;
    private static final String TEST_EVENT_TYPE = "file.uploaded";
    private static final String TEST_PAYLOAD = "{\"fileId\":123,\"hash\":\"abc123\"}";
    private static final String TEST_TRACE_ID = "trace-abc-123";

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("appendEvent Tests")
    class AppendEventTests {

        @Test
        @DisplayName("should create event with all required fields")
        void shouldCreateEventWithAllFields() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getAggregateType()).isEqualTo(TEST_AGGREGATE_TYPE);
            assertThat(capturedEvent.getAggregateId()).isEqualTo(TEST_AGGREGATE_ID);
            assertThat(capturedEvent.getEventType()).isEqualTo(TEST_EVENT_TYPE);
            assertThat(capturedEvent.getPayload()).isEqualTo(TEST_PAYLOAD);
            assertThat(capturedEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(capturedEvent.getRetryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should generate UUID for event ID")
        void shouldGenerateUuidForEventId() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getId()).isNotNull();
            assertThat(capturedEvent.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }

        @Test
        @DisplayName("should set nextAttemptAt to current time")
        void shouldSetNextAttemptAtToCurrentTime() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            Date beforeCall = new Date();
            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);
            Date afterCall = new Date();

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getNextAttemptAt())
                    .isAfterOrEqualTo(beforeCall)
                    .isBeforeOrEqualTo(afterCall);
        }

        @Test
        @DisplayName("should capture traceId from MDC when present")
        void shouldCaptureTraceIdFromMdc() {
            MDC.put(Const.TRACE_ID, TEST_TRACE_ID);
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getTraceId()).isEqualTo(TEST_TRACE_ID);
        }

        @Test
        @DisplayName("should handle null traceId from MDC gracefully")
        void shouldHandleNullTraceIdFromMdc() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getTraceId()).isNull();
        }

        @Test
        @DisplayName("should handle empty string traceId from MDC")
        void shouldHandleEmptyStringTraceIdFromMdc() {
            MDC.put(Const.TRACE_ID, "");
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getTraceId()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Event Data Scenarios")
    class EventDataScenarios {

        @Test
        @DisplayName("should handle complex JSON payload")
        void shouldHandleComplexJsonPayload() {
            String complexPayload = """
                    {
                        "fileId": 123,
                        "hash": "sha256:abc123",
                        "metadata": {
                            "size": 1024,
                            "mimeType": "application/pdf",
                            "tags": ["important", "confidential"]
                        },
                        "storedPaths": {
                            "node1": "tenant/1/abc123",
                            "node2": "tenant/1/abc123"
                        }
                    }
                    """;
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, complexPayload);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getPayload()).isEqualTo(complexPayload);
        }

        @Test
        @DisplayName("should handle different aggregate types")
        void shouldHandleDifferentAggregateTypes() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            String[] aggregateTypes = {"FILE", "USER", "SHARE", "SAGA", "SAGA_DEAD_LETTER"};

            for (String aggregateType : aggregateTypes) {
                outboxService.appendEvent(aggregateType, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);
            }

            verify(outboxMapper, times(aggregateTypes.length)).insert(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("should handle different event types")
        void shouldHandleDifferentEventTypes() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            String[] eventTypes = {
                    "file.uploaded",
                    "file.deleted",
                    "file.shared",
                    "saga.completed",
                    "saga.compensation.failed"
            };

            for (String eventType : eventTypes) {
                outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, eventType, TEST_PAYLOAD);
            }

            verify(outboxMapper, times(eventTypes.length)).insert(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("should handle null payload")
        void shouldHandleNullPayload() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, null);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getPayload()).isNull();
        }

        @Test
        @DisplayName("should handle large aggregate ID")
        void shouldHandleLargeAggregateId() {
            Long largeId = Long.MAX_VALUE;
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, largeId, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getAggregateId()).isEqualTo(largeId);
        }
    }

    @Nested
    @DisplayName("Status Initial State")
    class StatusInitialState {

        @Test
        @DisplayName("should set initial status to PENDING")
        void shouldSetInitialStatusToPending() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        }

        @Test
        @DisplayName("should set initial retry count to zero")
        void shouldSetInitialRetryCountToZero() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper).insert(eventCaptor.capture());

            OutboxEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getRetryCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Unique Event IDs")
    class UniqueEventIds {

        @Test
        @DisplayName("should generate unique event IDs for each call")
        void shouldGenerateUniqueEventIdsForEachCall() {
            when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);

            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);
            outboxService.appendEvent(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID, TEST_EVENT_TYPE, TEST_PAYLOAD);

            ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxMapper, times(2)).insert(eventCaptor.capture());

            var capturedEvents = eventCaptor.getAllValues();
            assertThat(capturedEvents.get(0).getId()).isNotEqualTo(capturedEvents.get(1).getId());
        }
    }
}
