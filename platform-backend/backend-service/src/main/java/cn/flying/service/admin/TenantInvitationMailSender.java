package cn.flying.service.admin;

/** Delivers a one-time invitation without persisting its raw token in a queue or database. */
public interface TenantInvitationMailSender {

    /** Sends the one-time acceptance URL directly to the invited address. */
    void sendInvitation(String email, String invitationUrl);
}
