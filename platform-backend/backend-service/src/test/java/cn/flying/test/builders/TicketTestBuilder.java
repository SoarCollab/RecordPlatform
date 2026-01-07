package cn.flying.test.builders;

import cn.flying.common.constant.TicketCategory;
import cn.flying.common.constant.TicketPriority;
import cn.flying.common.constant.TicketStatus;
import cn.flying.dao.entity.Ticket;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class TicketTestBuilder {

    private static final AtomicLong idCounter = new AtomicLong(1L);
    private static final AtomicInteger ticketNoCounter = new AtomicInteger(1);

    public static Ticket aTicket() {
        return new Ticket()
                .setId(idCounter.getAndIncrement())
                .setTenantId(1L)
                .setTicketNo("TKT" + String.format("%06d", ticketNoCounter.getAndIncrement()))
                .setTitle("Test Ticket")
                .setContent("This is a test ticket content")
                .setPriority(TicketPriority.MEDIUM.getCode())
                .setCategory(TicketCategory.OTHER.getCode())
                .setStatus(TicketStatus.PENDING.getCode())
                .setCreatorId(100L)
                .setCreateTime(new Date())
                .setUpdateTime(new Date())
                .setDeleted(0);
    }

    public static Ticket aTicket(Consumer<Ticket> customizer) {
        Ticket ticket = aTicket();
        customizer.accept(ticket);
        return ticket;
    }

    public static Ticket aTicketForUser(Long creatorId) {
        return aTicket(t -> t.setCreatorId(creatorId));
    }

    public static Ticket aTicketWithTitle(String title) {
        return aTicket(t -> t.setTitle(title));
    }

    public static Ticket aBugTicket() {
        return aTicket(t -> t
                .setCategory(TicketCategory.BUG.getCode())
                .setTitle("Bug Report: Something is broken"));
    }

    public static Ticket aFeatureRequestTicket() {
        return aTicket(t -> t
                .setCategory(TicketCategory.FEATURE_REQUEST.getCode())
                .setTitle("Feature Request: New functionality"));
    }

    public static Ticket aHighPriorityTicket() {
        return aTicket(t -> t.setPriority(TicketPriority.HIGH.getCode()));
    }

    public static Ticket aLowPriorityTicket() {
        return aTicket(t -> t.setPriority(TicketPriority.LOW.getCode()));
    }

    public static Ticket aPendingTicket() {
        return aTicket(t -> t.setStatus(TicketStatus.PENDING.getCode()));
    }

    public static Ticket aProcessingTicket() {
        return aTicket(t -> t
                .setStatus(TicketStatus.PROCESSING.getCode())
                .setAssigneeId(200L));
    }

    public static Ticket aCompletedTicket() {
        return aTicket(t -> t
                .setStatus(TicketStatus.COMPLETED.getCode())
                .setCloseTime(new Date()));
    }

    public static Ticket aClosedTicket() {
        return aTicket(t -> t
                .setStatus(TicketStatus.CLOSED.getCode())
                .setCloseTime(new Date()));
    }

    public static Ticket aTicketAssignedTo(Long assigneeId) {
        return aTicket(t -> t
                .setAssigneeId(assigneeId)
                .setStatus(TicketStatus.PROCESSING.getCode()));
    }

    public static void resetIdCounter() {
        idCounter.set(1L);
        ticketNoCounter.set(1);
    }
}
