import { describe, expect, it } from "vitest";
import { readInvitationTokenFromFragment } from "./invitation-token";

describe("invitation token URL boundary", () => {
  it("reads the opaque token from the fragment", () => {
    expect(
      readInvitationTokenFromFragment(
        new URL("https://record.test/invitations/accept#token=opaque-value"),
      ),
    ).toBe("opaque-value");
  });

  it("never accepts a query-string token that would reach access logs", () => {
    expect(
      readInvitationTokenFromFragment(
        new URL("https://record.test/invitations/accept?token=leaked-value"),
      ),
    ).toBe("");
  });
});
