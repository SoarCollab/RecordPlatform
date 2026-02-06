import { describe, expect, it } from "vitest";
import { load as loadAnnouncementDetail } from "./(app)/announcements/[id]/+page";
import { load as loadFileDetail } from "./(app)/files/[hash]/+page";
import { load as loadMessageDetail } from "./(app)/messages/[id]/+page";
import { load as loadMessageNew } from "./(app)/messages/new/+page";
import { load as loadTicketDetail } from "./(app)/tickets/[id]/+page";
import { load as loadSharePage } from "./share/[code]/+page";

/**
 * 创建包含 params 的 PageLoadEvent 最小上下文。
 *
 * @param params 页面参数。
 * @returns 仅包含 params/url 的最小事件对象。
 */
function createPageEvent(params: Record<string, string> = {}, url = "http://localhost/") {
  return {
    params,
    url: new URL(url),
  } as never;
}

describe("route +page.ts loaders", () => {
  it("announcements/[id] 应返回 announcementId", () => {
    const result = loadAnnouncementDetail(createPageEvent({ id: "a-1" }));
    expect(result).toEqual({ announcementId: "a-1" });
  });

  it("files/[hash] 应返回 hash", () => {
    const result = loadFileDetail(createPageEvent({ hash: "h-1" }));
    expect(result).toEqual({ hash: "h-1" });
  });

  it("messages/[id] 应返回 conversationId", () => {
    const result = loadMessageDetail(createPageEvent({ id: "c-1" }));
    expect(result).toEqual({ conversationId: "c-1" });
  });

  it("messages/new 应从 query 读取 receiverId", () => {
    const withTo = loadMessageNew(createPageEvent({}, "http://localhost/messages/new?to=u-1"));
    const withoutTo = loadMessageNew(createPageEvent({}, "http://localhost/messages/new"));

    expect(withTo).toEqual({ receiverId: "u-1" });
    expect(withoutTo).toEqual({ receiverId: "" });
  });

  it("tickets/[id] 应返回 ticketId", () => {
    const result = loadTicketDetail(createPageEvent({ id: "t-1" }));
    expect(result).toEqual({ ticketId: "t-1" });
  });

  it("share/[code] 应返回 code", () => {
    const result = loadSharePage(createPageEvent({ code: "share-1" }));
    expect(result).toEqual({ code: "share-1" });
  });
});
