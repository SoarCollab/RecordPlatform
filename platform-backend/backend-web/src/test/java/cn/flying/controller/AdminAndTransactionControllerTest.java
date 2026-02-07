package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import cn.flying.dao.vo.ticket.TicketQueryVO;
import cn.flying.dao.vo.ticket.TicketVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.AnnouncementService;
import cn.flying.service.FileQueryService;
import cn.flying.service.TicketService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端与交易 REST 控制器单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AdminAndTransactionControllerTest {

    @Mock
    private AnnouncementService announcementService;

    @Mock
    private TicketService ticketService;

    @Mock
    private FileQueryService fileQueryService;

    private AdminAnnouncementController adminAnnouncementController;
    private AdminTicketController adminTicketController;
    private TransactionController transactionController;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234")
        );

        adminAnnouncementController = new AdminAnnouncementController();
        ReflectionTestUtils.setField(adminAnnouncementController, "announcementService", announcementService);

        adminTicketController = new AdminTicketController();
        ReflectionTestUtils.setField(adminTicketController, "ticketService", ticketService);

        transactionController = new TransactionController();
        ReflectionTestUtils.setField(transactionController, "fileQueryService", fileQueryService);
    }

    /**
     * 验证管理员公告分页查询 REST 路径。
     */
    @Test
    void shouldQueryAdminAnnouncements() {
        IPage<AnnouncementVO> page = new Page<Announcement>(1, 10).convert(item -> new AnnouncementVO());
        when(announcementService.getAdminList(org.mockito.ArgumentMatchers.any(Page.class))).thenReturn(page);

        Result<IPage<AnnouncementVO>> result = adminAnnouncementController.getAdminAnnouncements(1, 10);
        assertEquals(page, result.getData());
    }

    /**
     * 验证管理员工单 REST 路径的列表、分配、状态更新与待处理统计。
     */
    @Test
    void shouldHandleAdminTicketRestEndpoints() {
        Long adminId = 99L;
        String ticketExternalId = IdUtils.toExternalId(200L);
        String assigneeExternalId = IdUtils.toExternalId(300L);
        TicketQueryVO queryVO = new TicketQueryVO();

        IPage<TicketVO> page = new Page<Ticket>(1, 10).convert(item -> new TicketVO());
        when(ticketService.getAdminTickets(org.mockito.ArgumentMatchers.any(TicketQueryVO.class), org.mockito.ArgumentMatchers.any(Page.class)))
                .thenReturn(page);
        when(ticketService.getAdminPendingCount(adminId)).thenReturn(5);

        Result<IPage<TicketVO>> listResult = adminTicketController.getAdminTickets(queryVO, 1, 10);
        Result<String> assignResult = adminTicketController.assignTicket(adminId, ticketExternalId, assigneeExternalId);
        Result<String> updateResult = adminTicketController.updateTicketStatus(adminId, ticketExternalId, 2);
        Result<Map<String, Integer>> pendingResult = adminTicketController.getAdminPendingCount(adminId);

        assertEquals(page, listResult.getData());
        assertEquals("分配成功", assignResult.getData());
        assertEquals("状态更新成功", updateResult.getData());
        assertEquals(5, pendingResult.getData().get("count"));
        verify(ticketService).assignTicket(adminId, 200L, 300L);
        verify(ticketService).updateStatus(org.mockito.ArgumentMatchers.eq(adminId), org.mockito.ArgumentMatchers.eq(200L), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 验证交易查询 REST 路径。
     */
    @Test
    void shouldQueryTransactionByHash() {
        TransactionVO transactionVO = new TransactionVO(
                "tx-hash",
                "chain-1",
                "group-1",
                "abi",
                "0xfrom",
                "0xto",
                "0xinput",
                "0xsig",
                "100",
                1738920000L
        );
        when(fileQueryService.getTransactionByHash("tx-hash")).thenReturn(transactionVO);

        Result<TransactionVO> result = transactionController.getTransaction("tx-hash");
        assertEquals(transactionVO, result.getData());
    }
}
