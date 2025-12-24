export type ValidationResult =
  | { valid: true }
  | { valid: false; message: string };

/**
 * 必填验证
 */
export function required(
  value: unknown,
  fieldName = "此字段",
): ValidationResult {
  if (value === null || value === undefined || value === "") {
    return { valid: false, message: `${fieldName}不能为空` };
  }
  return { valid: true };
}

/**
 * 最小长度验证
 */
export function minLength(
  value: string,
  min: number,
  fieldName = "此字段",
): ValidationResult {
  if (value.length < min) {
    return { valid: false, message: `${fieldName}长度不能少于${min}个字符` };
  }
  return { valid: true };
}

/**
 * 最大长度验证
 */
export function maxLength(
  value: string,
  max: number,
  fieldName = "此字段",
): ValidationResult {
  if (value.length > max) {
    return { valid: false, message: `${fieldName}长度不能超过${max}个字符` };
  }
  return { valid: true };
}

/**
 * 邮箱验证
 */
export function email(value: string): ValidationResult {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  if (!emailRegex.test(value)) {
    return { valid: false, message: "请输入有效的邮箱地址" };
  }
  return { valid: true };
}

/**
 * 手机号验证 (中国大陆)
 */
export function phone(value: string): ValidationResult {
  const phoneRegex = /^1[3-9]\d{9}$/;
  if (!phoneRegex.test(value)) {
    return { valid: false, message: "请输入有效的手机号" };
  }
  return { valid: true };
}

/**
 * 用户名验证
 */
export function username(value: string): ValidationResult {
  // 4-20 characters, alphanumeric and underscore only
  const usernameRegex = /^[a-zA-Z0-9_]{4,20}$/;
  if (!usernameRegex.test(value)) {
    return {
      valid: false,
      message: "用户名只能包含字母、数字和下划线，长度4-20",
    };
  }
  return { valid: true };
}

/**
 * 密码强度验证
 */
export function password(value: string): ValidationResult {
  // At least 8 characters, must contain letter and number
  if (value.length < 8) {
    return { valid: false, message: "密码长度不能少于8位" };
  }
  if (!/[a-zA-Z]/.test(value)) {
    return { valid: false, message: "密码必须包含字母" };
  }
  if (!/\d/.test(value)) {
    return { valid: false, message: "密码必须包含数字" };
  }
  return { valid: true };
}

/**
 * 确认密码验证
 */
export function confirmPassword(
  value: string,
  original: string,
): ValidationResult {
  if (value !== original) {
    return { valid: false, message: "两次输入的密码不一致" };
  }
  return { valid: true };
}

/**
 * 文件大小验证
 */
export function fileSize(size: number, maxSizeMB: number): ValidationResult {
  const maxBytes = maxSizeMB * 1024 * 1024;
  if (size > maxBytes) {
    return { valid: false, message: `文件大小不能超过${maxSizeMB}MB` };
  }
  return { valid: true };
}

/**
 * 文件类型验证
 */
export function fileType(
  filename: string,
  allowedTypes: string[],
): ValidationResult {
  const ext = filename.split(".").pop()?.toLowerCase() || "";
  if (!allowedTypes.includes(ext)) {
    return {
      valid: false,
      message: `只支持以下格式：${allowedTypes.join(", ")}`,
    };
  }
  return { valid: true };
}

/**
 * 组合验证器
 */
export function validate(
  value: unknown,
  validators: ((v: unknown) => ValidationResult)[],
): ValidationResult {
  for (const validator of validators) {
    const result = validator(value);
    if (!result.valid) {
      return result;
    }
  }
  return { valid: true };
}
