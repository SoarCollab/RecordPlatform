package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.TicketPriority;
import cn.flying.common.constant.TicketStatus;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.entity.TicketReply;
import cn.flying.dao.mapper.TicketAttachmentMapper;
import cn.flying.dao.mapper.TicketMapper;
import cn.flying.dao.mapper.TicketReplyMapper;
import cn.flying.dao.vo.ticket.TicketCreateVO;
import cn.flying.dao.vo.ticket.TicketReplyVO;
import cn.flying.dao.vo.ticket.TicketUpdateVO;
import cn.flying.service.AccountService;
import cn.flying.service.generator.TicketNoGenerator;
import cn.flying.test.builders.AccountTestBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for TicketServiceImpl.
 * Verifies ticket lifecycle, status transitions, and reply handling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TicketService Tests")
class TicketServiceTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketReplyMapper ticketReplyMapper;

    @Mock
    private TicketAttachmentMapper ticketAttachmentMapper;

    @Mock
    private AccountService accountService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TicketNoGenerator ticketNoGenerator;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private static MockedStatic<TenantContext> tenantContextMock;
    private static MockedStatic<IdUtils> idUtilsMock;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ADMIN_ID = 200L;

    @BeforeAll
    static void setUpClass() {
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::requireTenantId).thenReturn(TENANT_ID);

        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString())).thenAnswer(invocation -> {
            String externalId = invocation.getArgument(0);
            if (externalId != null && externalId.startsWith("EXT_")) {
                return Long.parseLong(externalId.substring(4));
            }
            return null;
        });
        idUtilsMock.when(() -> IdUtils.toExternalId(anyLong())).thenAnswer(invocation -> {
            Long internalId = invocation.getArgument(0);
            return "EXT_" + internalId;
        });
    }

    @AfterAll
    static void tearDownClass() {
        tenantContextMock.close();
        idUtilsMock.close();
    }

    @BeforeEach
    void setUp() {
        AccountTestBuilder.resetIdCounter();
        // ServiceImpl uses baseMapper internally, need to inject the mock
        ReflectionTestUtils.setField(ticketService, "baseMapper", ticketMapper);
        when(ticketNoGenerator.generateTicketNo()).thenReturn("TK202501050001");
    }

    @Nested
    @DisplayName("Create Ticket")
    class CreateTicket {

        @Test
        @DisplayName("should create ticket with valid parameters")
        void shouldCreateTicket() {
            // Given
            TicketCreateVO vo = createTicketVO("Test Ticket", "This is a test ticket content", TicketPriority.HIGH.getCode());

            // When
            Ticket result = ticketService.createTicket(USER_ID, vo);

            // Then
            assertNotNull(result);
            assertEquals("TK202501050001", result.getTicketNo());
            assertEquals("Test Ticket", result.getTitle());
            assertEquals(TicketPriority.HIGH.getCode(), result.getPriority());
            assertEquals(TicketStatus.PENDING.getCode(), result.getStatus());
            assertEquals(USER_ID, result.getCreatorId());
        }

        @Test
        @DisplayName("should use default priority if not specified")
        void shouldUseDefaultPriority() {
            // Given
            TicketCreateVO vo = createTicketVO("Test Ticket", "Content", null);

            // When
            Ticket result = ticketService.createTicket(USER_ID, vo);

            // Then
            assertEquals(TicketPriority.MEDIUM.getCode(), result.getPriority());
        }
    }

    @Nested
    @DisplayName("Get Ticket Detail")
    class GetTicketDetail {

        @Test
        @DisplayName("should throw exception for non-existent ticket")
        void shouldThrowForNonExistent() {
            // Given
            when(ticketMapper.selectById(anyLong())).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.getTicketDetail(USER_ID, 999L, false));

            assertEquals(ResultEnum.TICKET_NOT_FOUND.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject non-owner access for user")
        void shouldRejectNonOwnerAccess() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            Long differentUserId = 999L;

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.getTicketDetail(differentUserId, 1L, false));

            assertEquals(ResultEnum.TICKET_NOT_OWNER.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should allow admin to access any ticket")
        void shouldAllowAdminAccess() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(accountService.findAccountById(USER_ID)).thenReturn(AccountTestBuilder.anAccountWithId(USER_ID));

            // When & Then - no exception
            assertDoesNotThrow(() -> ticketService.getTicketDetail(ADMIN_ID, 1L, true));
        }
    }

    @Nested
    @DisplayName("Reply Ticket")
    class ReplyTicket {

        @Test
        @DisplayName("should add reply to ticket")
        void shouldAddReply() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            TicketReplyVO vo = createReplyVO("This is a reply");

            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(ticketReplyMapper.insert(any(TicketReply.class))).thenReturn(1);

            // When
            TicketReply result = ticketService.replyTicket(USER_ID, 1L, vo, false);

            // Then
            assertNotNull(result);
            assertEquals("This is a reply", result.getContent());
            assertEquals(USER_ID, result.getReplierId());
            assertEquals(0, result.getIsInternal()); // Not internal for user reply
        }

        /**
         * 验证用户回复后会触发工单 update_time 刷新，并同步更新回复者的 last_view_time，确保未读统计生效且不会把自己的回复计为未读。
         */
        @Test
        @DisplayName("should refresh ticket update_time and view_time for unread tracking on user reply")
        void shouldRefreshTicketActivityOnUserReply() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            TicketReplyVO vo = createReplyVO("User reply");

            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(ticketReplyMapper.insert(any(TicketReply.class))).thenReturn(1);

            // When
            ticketService.replyTicket(USER_ID, 1L, vo, false);

            // Then
            ArgumentCaptor<Wrapper<Ticket>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(ticketMapper).update(isNull(), wrapperCaptor.capture());

            Wrapper<Ticket> wrapper = wrapperCaptor.getValue();
            assertInstanceOf(UpdateWrapper.class, wrapper);

            String sqlSet = ((UpdateWrapper<Ticket>) wrapper).getSqlSet();
            assertNotNull(sqlSet);
            assertTrue(sqlSet.contains("update_time"));
            assertTrue(sqlSet.contains("creator_last_view_time"));
        }

        /**
         * 验证管理员内部备注会更新工单 update_time，同时刷新 creator_last_view_time，避免创建者因为不可见的内部备注出现未读提示。
         */
        @Test
        @DisplayName("should not count internal admin reply as unread for creator")
        void shouldRefreshCreatorViewTimeOnInternalAdminReply() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            ticket.setAssigneeId(ADMIN_ID);

            TicketReplyVO vo = createReplyVO("Internal admin note");
            vo.setIsInternal(true);

            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(ticketReplyMapper.insert(any(TicketReply.class))).thenReturn(1);

            // When
            ticketService.replyTicket(ADMIN_ID, 1L, vo, true);

            // Then
            ArgumentCaptor<Wrapper<Ticket>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(ticketMapper).update(isNull(), wrapperCaptor.capture());

            Wrapper<Ticket> wrapper = wrapperCaptor.getValue();
            assertInstanceOf(UpdateWrapper.class, wrapper);

            String sqlSet = ((UpdateWrapper<Ticket>) wrapper).getSqlSet();
            assertNotNull(sqlSet);
            assertTrue(sqlSet.contains("update_time"));
            assertTrue(sqlSet.contains("creator_last_view_time"));
            assertTrue(sqlSet.contains("assignee_last_view_time"));
        }

        @Test
        @DisplayName("should reject reply to closed ticket")
        void shouldRejectReplyToClosedTicket() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.CLOSED);
            TicketReplyVO vo = createReplyVO("Reply");

            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.replyTicket(USER_ID, 1L, vo, false));

            assertEquals(ResultEnum.TICKET_ALREADY_CLOSED.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject non-owner reply for user")
        void shouldRejectNonOwnerReply() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            TicketReplyVO vo = createReplyVO("Reply");

            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            Long differentUserId = 999L;

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.replyTicket(differentUserId, 1L, vo, false));

            assertEquals(ResultEnum.TICKET_NOT_OWNER.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should update status to PROCESSING on first admin reply")
        void shouldUpdateStatusOnFirstAdminReply() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            TicketReplyVO vo = createReplyVO("Admin reply");

            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(ticketReplyMapper.insert(any(TicketReply.class))).thenReturn(1);
            when(accountService.findAccountById(ADMIN_ID)).thenReturn(AccountTestBuilder.anAccountWithId(ADMIN_ID));

            // When
            ticketService.replyTicket(ADMIN_ID, 1L, vo, true);

            // Then
            assertEquals(TicketStatus.PROCESSING.getCode(), ticket.getStatus());
            assertEquals(ADMIN_ID, ticket.getAssigneeId());
        }
    }

    @Nested
    @DisplayName("Update Status")
    class UpdateStatus {

        @Test
        @DisplayName("should update status with valid transition")
        void shouldUpdateStatus() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When
            ticketService.updateStatus(ADMIN_ID, 1L, TicketStatus.CONFIRMING);

            // Then
            assertEquals(TicketStatus.CONFIRMING.getCode(), ticket.getStatus());
            verify(eventPublisher).publishEvent(any());
        }

        @Test
        @DisplayName("should reject invalid status transition")
        void shouldRejectInvalidTransition() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.CLOSED);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When & Then - CLOSED -> PENDING is invalid
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.updateStatus(ADMIN_ID, 1L, TicketStatus.PENDING));

            assertEquals(ResultEnum.INVALID_TICKET_STATUS.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should set close time when closing")
        void shouldSetCloseTime() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When
            ticketService.updateStatus(ADMIN_ID, 1L, TicketStatus.CLOSED);

            // Then
            assertEquals(TicketStatus.CLOSED.getCode(), ticket.getStatus());
            assertNotNull(ticket.getCloseTime());
        }
    }

    @Nested
    @DisplayName("Assign Ticket")
    class AssignTicket {

        @Test
        @DisplayName("should assign ticket to admin")
        void shouldAssignTicket() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            Account assignee = AccountTestBuilder.anAccountWithId(ADMIN_ID);

            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(accountService.findAccountById(ADMIN_ID)).thenReturn(assignee);

            // When
            ticketService.assignTicket(ADMIN_ID, 1L, ADMIN_ID);

            // Then
            assertEquals(ADMIN_ID, ticket.getAssigneeId());
            assertEquals(TicketStatus.PROCESSING.getCode(), ticket.getStatus());
        }

        @Test
        @DisplayName("should reject assignment to non-existent user")
        void shouldRejectNonExistentAssignee() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);
            when(accountService.findAccountById(999L)).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.assignTicket(ADMIN_ID, 1L, 999L));

            assertEquals(ResultEnum.USER_NOT_EXIST.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Close Ticket")
    class CloseTicket {

        @Test
        @DisplayName("should allow owner to close ticket")
        void shouldAllowOwnerToClose() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When
            ticketService.closeTicket(USER_ID, 1L);

            // Then
            assertEquals(TicketStatus.CLOSED.getCode(), ticket.getStatus());
            assertNotNull(ticket.getCloseTime());
        }

        @Test
        @DisplayName("should reject non-owner from closing")
        void shouldRejectNonOwnerClose() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.closeTicket(ADMIN_ID, 1L));

            assertEquals(ResultEnum.TICKET_NOT_OWNER.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Confirm Ticket")
    class ConfirmTicket {

        @Test
        @DisplayName("should confirm ticket in CONFIRMING status")
        void shouldConfirmTicket() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.CONFIRMING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When
            ticketService.confirmTicket(USER_ID, 1L);

            // Then
            assertEquals(TicketStatus.COMPLETED.getCode(), ticket.getStatus());
            assertNotNull(ticket.getCloseTime());
        }

        @Test
        @DisplayName("should reject confirmation for non-CONFIRMING status")
        void shouldRejectNonConfirmingStatus() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.confirmTicket(USER_ID, 1L));

            assertEquals(ResultEnum.INVALID_TICKET_STATUS.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Update Ticket")
    class UpdateTicket {

        @Test
        @DisplayName("should update pending ticket")
        void shouldUpdatePendingTicket() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            TicketUpdateVO vo = createUpdateVO("Updated Title", TicketPriority.HIGH.getCode());

            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When
            Ticket result = ticketService.updateTicket(USER_ID, 1L, vo);

            // Then
            assertEquals("Updated Title", result.getTitle());
            assertEquals(TicketPriority.HIGH.getCode(), result.getPriority());
        }

        @Test
        @DisplayName("should reject update for non-PENDING ticket")
        void shouldRejectNonPendingUpdate() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PROCESSING);
            TicketUpdateVO vo = createUpdateVO("Updated", null);

            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.updateTicket(USER_ID, 1L, vo));

            assertEquals(ResultEnum.INVALID_TICKET_STATUS.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject non-owner update")
        void shouldRejectNonOwnerUpdate() {
            // Given
            Ticket ticket = createTicket(1L, USER_ID, TicketStatus.PENDING);
            TicketUpdateVO vo = createUpdateVO("Updated", null);

            when(ticketMapper.selectById(1L)).thenReturn(ticket);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> ticketService.updateTicket(ADMIN_ID, 1L, vo));

            assertEquals(ResultEnum.TICKET_NOT_OWNER.getCode(), ex.getResultEnum().getCode());
        }
    }

    // Helper method to create test tickets
    private Ticket createTicket(Long id, Long creatorId, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo("TK" + id);
        ticket.setTitle("Test Ticket " + id);
        ticket.setContent("Content");
        ticket.setCreatorId(creatorId);
        ticket.setStatus(status.getCode());
        ticket.setPriority(TicketPriority.MEDIUM.getCode());
        return ticket;
    }

    // Helper method to create TicketCreateVO (uses standard setters, not chainable)
    private TicketCreateVO createTicketVO(String title, String content, Integer priority) {
        TicketCreateVO vo = new TicketCreateVO();
        vo.setTitle(title);
        vo.setContent(content);
        if (priority != null) {
            vo.setPriority(priority);
        }
        return vo;
    }

    // Helper method to create TicketReplyVO (uses standard setters, not chainable)
    private TicketReplyVO createReplyVO(String content) {
        TicketReplyVO vo = new TicketReplyVO();
        vo.setContent(content);
        return vo;
    }

    // Helper method to create TicketUpdateVO (uses standard setters, not chainable)
    private TicketUpdateVO createUpdateVO(String title, Integer priority) {
        TicketUpdateVO vo = new TicketUpdateVO();
        vo.setTitle(title);
        if (priority != null) {
            vo.setPriority(priority);
        }
        return vo;
    }
}
