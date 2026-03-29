/**
 * 审计模块常量定义
 *
 * 模块和操作类型值来源于后端 @OperationLog 注解。
 * 敏感操作定义用于前端标记，不依赖后端视图。
 */

/** 操作模块选项 */
export const AUDIT_MODULES = [
  { value: "文件操作", label: "文件操作" },
  { value: "文件分片上传模块", label: "文件分片上传" },
  { value: "图片上传模块", label: "图片上传" },
  { value: "分享", label: "分享" },
  { value: "好友分享", label: "好友分享" },
  { value: "好友", label: "好友" },
  { value: "工单模块", label: "工单" },
  { value: "站内信", label: "站内信" },
  { value: "公告模块", label: "公告" },
  { value: "用户模块", label: "用户" },
  { value: "登录校验模块", label: "登录校验" },
  { value: "权限管理", label: "权限管理" },
  { value: "系统审计", label: "系统审计" },
  { value: "管理员-文件审计", label: "管理员-文件审计" },
  { value: "管理员-配额灰度审计", label: "配额灰度审计" },
  { value: "文件配额", label: "文件配额" },
  { value: "integrity", label: "完整性检查" },
] as const;

/** 操作类型选项 */
export const AUDIT_OPERATION_TYPES = [
  { value: "查询", label: "查询" },
  { value: "新增", label: "新增" },
  { value: "修改", label: "修改" },
  { value: "删除", label: "删除" },
  { value: "上传", label: "上传" },
  { value: "下载", label: "下载" },
  { value: "分享", label: "分享" },
  { value: "保存", label: "保存" },
  { value: "上报", label: "上报" },
  { value: "检查", label: "检查" },
  { value: "备份", label: "备份" },
  { value: "写入", label: "写入" },
  { value: "授权", label: "授权" },
  { value: "撤销", label: "撤销" },
  { value: "提交", label: "提交" },
  { value: "query", label: "Query" },
  { value: "execute", label: "Execute" },
  { value: "update", label: "Update" },
] as const;

/** 模块 → 可用操作类型映射 */
export const MODULE_OPERATION_MAP: Record<string, string[]> = {
  文件操作: ["查询", "删除", "新增", "分享", "下载", "保存", "上报"],
  文件分片上传模块: ["上传"],
  图片上传模块: ["上传", "下载"],
  分享: ["查询"],
  好友分享: ["新增", "删除"],
  好友: ["新增", "update", "删除"],
  工单模块: ["查询", "新增", "修改"],
  站内信: ["查询", "修改", "新增", "删除"],
  公告模块: ["查询", "修改", "新增", "删除"],
  用户模块: ["查询", "修改"],
  登录校验模块: ["提交"],
  权限管理: ["查询", "新增", "修改", "删除", "授权", "撤销"],
  系统审计: ["查询", "检查", "备份", "修改"],
  "管理员-文件审计": ["查询", "update", "删除"],
  "管理员-配额灰度审计": ["写入", "查询"],
  文件配额: ["查询"],
  integrity: ["query", "execute", "update"],
};

/** 敏感操作类型 — 涉及数据变更、不可逆操作、权限变更 */
export const SENSITIVE_OPERATION_TYPES = new Set([
  "删除",
  "授权",
  "撤销",
  "备份",
  "上报",
]);

/** 判断是否为敏感操作 */
export function isSensitiveOperation(operationType: string): boolean {
  return SENSITIVE_OPERATION_TYPES.has(operationType);
}

/** 根据选中的模块获取可用操作类型列表 */
export function getOperationTypesForModule(
  module: string,
): Array<{ value: string; label: string }> {
  const types = MODULE_OPERATION_MAP[module];
  if (!types) return [...AUDIT_OPERATION_TYPES];
  return AUDIT_OPERATION_TYPES.filter((t) => types.includes(t.value));
}
