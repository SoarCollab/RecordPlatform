import { api } from "../client";

const BASE = "/images";

/**
 * 上传头像
 * @param file 头像文件
 */
export async function uploadAvatar(file: File): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);

  return api.upload<string>(`${BASE}/upload/avatar`, formData);
}
