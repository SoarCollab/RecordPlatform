import { beforeEach, describe, expect, it, vi } from "vitest";

const clientMocks = vi.hoisted(() => {
  return {
    api: {
      upload: vi.fn(),
    },
  };
});

vi.mock("../client", () => ({
  api: clientMocks.api,
}));

import { uploadAvatar } from "./images";

describe("images endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMocks.api.upload.mockResolvedValue("avatar-url");
  });

  it("uploadAvatar 应使用 FormData 上传文件", async () => {
    const file = new File(["avatar"], "avatar.png", { type: "image/png" });

    const result = await uploadAvatar(file);

    expect(result).toBe("avatar-url");
    expect(clientMocks.api.upload).toHaveBeenCalledWith(
      "/images/upload/avatar",
      expect.any(FormData),
    );

    const [, formData] = clientMocks.api.upload.mock.calls[0];
    expect((formData as FormData).get("file")).toBeInstanceOf(File);
  });
});
