package cn.flying.service.saga;

import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.service.outbox.OutboxService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SagaCompensationHelper Tests")
@ExtendWith(MockitoExtension.class)
class SagaCompensationHelperTest {

    @Mock
    private FileSagaMapper sagaMapper;

    @InjectMocks
    private SagaCompensationHelper compensationHelper;

    private FileSaga testSaga;

    @BeforeEach
    void setUp() {
        testSaga = new FileSaga();
        testSaga.setId(1L);
        testSaga.setRequestId("req-123");
        testSaga.setStatus(FileSagaStatus.RUNNING.name());
        testSaga.setCurrentStep("INIT");
    }

    @Nested
    @DisplayName("persistPayloadInNewTransaction Tests")
    class PersistPayloadTests {

        @Test
        @DisplayName("should update payload in mapper")
        void persistPayload_updatesMapper() {
            String payloadJson = "{\"fileId\":123,\"chunks\":[]}";

            compensationHelper.persistPayloadInNewTransaction(testSaga, payloadJson);

            verify(sagaMapper).updatePayloadById(testSaga.getId(), payloadJson);
        }

        @Test
        @DisplayName("should set payload on saga entity")
        void persistPayload_setsSagaPayload() {
            String payloadJson = "{\"key\":\"value\"}";

            compensationHelper.persistPayloadInNewTransaction(testSaga, payloadJson);

            assertThat(testSaga.getPayload()).isEqualTo(payloadJson);
        }
    }

    @Nested
    @DisplayName("updateSagaStatusInNewTransaction Tests")
    class UpdateStatusTests {

        @Test
        @DisplayName("should update saga via mapper")
        void updateStatus_callsMapperUpdate() {
            testSaga.setStatus(FileSagaStatus.SUCCEEDED.name());

            compensationHelper.updateSagaStatusInNewTransaction(testSaga);

            verify(sagaMapper).updateById(testSaga);
        }

        @Test
        @DisplayName("should preserve saga state")
        void updateStatus_preservesState() {
            testSaga.setStatus(FileSagaStatus.COMPENSATING.name());
            testSaga.setCurrentStep("ROLLBACK_STORAGE");

            compensationHelper.updateSagaStatusInNewTransaction(testSaga);

            ArgumentCaptor<FileSaga> captor = ArgumentCaptor.forClass(FileSaga.class);
            verify(sagaMapper).updateById(captor.capture());

            FileSaga captured = captor.getValue();
            assertThat(captured.getStatus()).isEqualTo(FileSagaStatus.COMPENSATING.name());
            assertThat(captured.getCurrentStep()).isEqualTo("ROLLBACK_STORAGE");
        }
    }

    @Nested
    @DisplayName("persistCompensationStepCompletion Tests")
    class CompensationStepCompletionTests {

        @Test
        @DisplayName("should update saga with payload")
        void persistCompensation_updatesWithPayload() {
            String payloadJson = "{\"compensatedChunks\":[1,2,3]}";

            compensationHelper.persistCompensationStepCompletion(testSaga, payloadJson);

            assertThat(testSaga.getPayload()).isEqualTo(payloadJson);
            verify(sagaMapper).updateById(testSaga);
        }
    }

    @Nested
    @DisplayName("insertSagaInNewTransaction Tests")
    class InsertSagaTests {

        @Test
        @DisplayName("should insert saga via mapper")
        void insertSaga_callsMapperInsert() {
            compensationHelper.insertSagaInNewTransaction(testSaga);

            verify(sagaMapper).insert(testSaga);
        }

        @Test
        @DisplayName("should insert with correct requestId")
        void insertSaga_preservesRequestId() {
            testSaga.setRequestId("unique-request-456");

            compensationHelper.insertSagaInNewTransaction(testSaga);

            ArgumentCaptor<FileSaga> captor = ArgumentCaptor.forClass(FileSaga.class);
            verify(sagaMapper).insert(captor.capture());

            assertThat(captor.getValue().getRequestId()).isEqualTo("unique-request-456");
        }
    }

    @Nested
    @DisplayName("updateSagaStepInNewTransaction Tests")
    class UpdateStepTests {

        @Test
        @DisplayName("should update step via mapper")
        void updateStep_callsMapperUpdate() {
            testSaga.setCurrentStep("STORE_METADATA");

            compensationHelper.updateSagaStepInNewTransaction(testSaga);

            verify(sagaMapper).updateById(testSaga);
        }

        @Test
        @DisplayName("should preserve step value")
        void updateStep_preservesStepValue() {
            testSaga.setCurrentStep("STORE_CHUNKS");

            compensationHelper.updateSagaStepInNewTransaction(testSaga);

            ArgumentCaptor<FileSaga> captor = ArgumentCaptor.forClass(FileSaga.class);
            verify(sagaMapper).updateById(captor.capture());

            assertThat(captor.getValue().getCurrentStep()).isEqualTo("STORE_CHUNKS");
        }
    }

    @Nested
    @DisplayName("publishEventInNewTransaction Tests")
    class PublishEventTests {

        @Mock
        private OutboxService outboxService;

        @Test
        @DisplayName("should publish event via outbox service")
        void publishEvent_callsOutboxService() {
            String aggregateType = "FILE";
            Long aggregateId = 123L;
            String eventType = "FILE_UPLOADED";
            String payload = "{\"fileId\":123}";

            compensationHelper.publishEventInNewTransaction(
                    outboxService, aggregateType, aggregateId, eventType, payload);

            verify(outboxService).appendEvent(aggregateType, aggregateId, eventType, payload);
        }

        @Test
        @DisplayName("should pass correct parameters")
        void publishEvent_passesCorrectParams() {
            String aggregateType = "SAGA";
            Long aggregateId = 456L;
            String eventType = "COMPENSATION_COMPLETED";
            String payload = "{\"sagaId\":456,\"success\":true}";

            compensationHelper.publishEventInNewTransaction(
                    outboxService, aggregateType, aggregateId, eventType, payload);

            verify(outboxService).appendEvent(
                    eq("SAGA"),
                    eq(456L),
                    eq("COMPENSATION_COMPLETED"),
                    eq("{\"sagaId\":456,\"success\":true}")
            );
        }
    }

    @Nested
    @DisplayName("Transaction Isolation Verification")
    class TransactionIsolationTests {

        @Test
        @DisplayName("methods should be annotated with REQUIRES_NEW")
        void methods_haveRequiresNewAnnotation() throws NoSuchMethodException {
            // Verify persistPayloadInNewTransaction
            var persistPayloadMethod = SagaCompensationHelper.class.getMethod(
                    "persistPayloadInNewTransaction", FileSaga.class, String.class);
            var transactional1 = persistPayloadMethod.getAnnotation(
                    org.springframework.transaction.annotation.Transactional.class);
            assertThat(transactional1).isNotNull();
            assertThat(transactional1.propagation())
                    .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);

            // Verify updateSagaStatusInNewTransaction
            var updateStatusMethod = SagaCompensationHelper.class.getMethod(
                    "updateSagaStatusInNewTransaction", FileSaga.class);
            var transactional2 = updateStatusMethod.getAnnotation(
                    org.springframework.transaction.annotation.Transactional.class);
            assertThat(transactional2).isNotNull();
            assertThat(transactional2.propagation())
                    .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        }
    }
}
