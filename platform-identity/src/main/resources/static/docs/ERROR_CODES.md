# Platform Identity 错误码文档

## 概述

本文档详细说明了 Platform Identity 服务中使用的所有错误码，包括错误码分类、使用场景和处理建议。

## 错误码格式

错误码采用5位数字格式，按功能模块分类：

```
错误码范围分配：
- 1: 成功状态码
- 10001-19999: 参数错误
- 20001-29999: 用户错误  
- 30001-39999: 业务错误
- 40001-49999: 系统错误
- 50001-59999: 数据错误
- 60001-69999: SSO和OAuth错误
- 70001-79999: 权限错误
- 90001-99999: 系统繁忙
```

## 📋 完整错误码列表

### ✅ 成功状态码

| 错误码 | 枚举名称 | 错误信息 | 使用场景 |
|--------|----------|----------|----------|
| 1 | SUCCESS | 操作成功! | 所有成功的操作 |

### ❌ 参数错误 (10001-19999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 10001 | PARAM_IS_INVALID | 参数无效 | 参数格式不正确 | 检查参数格式和类型 |
| 10002 | PARAM_IS_BLANK | 参数为空 | 必填参数为空 | 确保必填参数不为空 |
| 10003 | PARAM_TYPE_BIND_ERROR | 参数格式错误 | 参数类型转换失败 | 检查参数类型是否正确 |
| 10004 | PARAM_NOT_COMPLETE | 参数缺失 | 缺少必要参数 | 补充缺失的参数 |

### 👤 用户错误 (20001-29999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 20001 | USER_NOT_LOGGED_IN | 用户未登录，请先登录 | 访问需要登录的接口 | 引导用户登录 |
| 20002 | USER_LOGIN_ERROR | 账号不存在或密码错误 | 登录凭证错误 | 检查用户名和密码 |
| 20003 | USER_ACCOUNT_FORBIDDEN | 账号已被禁用 | 账号被管理员禁用 | 联系管理员解封 |
| 20004 | USER_NOT_EXIST | 用户不存在 | 查询不存在的用户 | 确认用户ID是否正确 |
| 20005 | USER_HAS_EXISTED | 用户已存在 | 注册已存在的用户 | 使用其他用户名或邮箱 |
| 20006 | USER_PASSWORD_ENCRYPT_ERROR | 密码加密失败 | 密码加密过程异常 | 重试或联系技术支持 |
| 20007 | USER_PASSWORD_VERIFY_ERROR | 密码验证失败 | 密码验证过程异常 | 重试或联系技术支持 |

### 💼 业务错误 (30001-39999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 0 | FAIL | 服务器内部错误，请联系管理员! | 通用业务失败 | 联系技术支持 |
| 30001 | CONTRACT_ERROR | 合约调用失败 | 区块链合约调用失败 | 检查区块链服务状态 |
| 30002 | INVALID_RETURN_VALUE | 合约返回值错误 | 合约返回异常数据 | 检查合约逻辑 |
| 30003 | GET_USER_FILE_ERROR | 获取用户文件失败 | 文件服务异常 | 检查文件服务状态 |
| 30004 | DELETE_USER_FILE_ERROR | 删除用户文件失败 | 文件删除失败 | 检查文件权限 |
| 30005 | GET_USER_SHARE_FILE_ERROR | 获取分享文件失败，文件不存在或访问次数受限 | 分享文件访问异常 | 检查分享链接有效性 |
| 30006 | BLOCKCHAIN_ERROR | 区块链服务请求失败 | 区块链服务不可用 | 检查区块链服务状态 |
| 30007 | TRANSACTION_NOT_FOUND | 交易记录未找到 | 查询不存在的交易 | 确认交易ID是否正确 |
| 30008 | TRANSACTION_RECEIPT_NOT_FOUND | 交易记录回执未找到 | 交易回执查询失败 | 等待交易确认 |
| 30009 | FILE_SERVICE_ERROR | 文件服务请求失败 | 文件服务异常 | 检查文件服务状态 |

### 🔧 系统错误 (40001-49999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 40003 | FILE_MAX_SIZE_OVERFLOW | 上传尺寸过大 | 文件大小超限 | 压缩文件或分片上传 |
| 40004 | FILE_ACCEPT_NOT_SUPPORT | 上传文件格式不支持 | 文件格式不被允许 | 使用支持的文件格式 |

### 📊 数据错误 (50001-59999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 50001 | RESULT_DATA_NONE | 数据未找到 | 查询结果为空 | 确认查询条件 |
| 50002 | DATA_IS_WRONG | 数据有误 | 数据格式或内容错误 | 检查数据完整性 |
| 50003 | DATA_ALREADY_EXISTED | 数据已存在 | 重复创建数据 | 使用更新操作 |
| 50004 | AUTH_CODE_ERROR | 验证码错误 | 验证码不正确或过期 | 重新获取验证码 |
| 50005 | File_UPLOAD_ERROR | 文件上传失败 | 文件上传过程异常 | 重试上传 |
| 50006 | File_DOWNLOAD_ERROR | 文件下载失败 | 文件下载过程异常 | 重试下载 |
| 50007 | FILE_DELETE_ERROR | 文件删除失败 | 文件删除过程异常 | 检查文件权限 |
| 50008 | FILE_NOT_EXIST | 文件不存在 | 访问不存在的文件 | 确认文件路径 |
| 50009 | FILE_EMPTY | 文件为空 | 上传的文件内容为空 | 检查文件内容 |
| 50010 | JSON_PARSE_ERROR | JSON格式化失败 | JSON解析异常 | 检查JSON格式 |

### 🔐 SSO和OAuth错误 (60001-69999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 60001 | SSO_ERROR | SSO认证失败 | SSO单点登录失败 | 检查SSO配置 |
| 60002 | SSO_UNKNOWN_ERROR | SSO未知错误 | SSO过程中的未知错误 | 查看详细日志 |
| 60003 | OAUTH_ERROR | OAuth认证失败 | OAuth认证过程失败 | 检查OAuth配置 |
| 60004 | OAUTH_CODE_INVALID | OAuth授权码无效 | 授权码过期或无效 | 重新获取授权码 |
| 60005 | OAUTH_TOKEN_INVALID | OAuth令牌无效 | 访问令牌过期或无效 | 刷新令牌或重新授权 |

### 🛡️ 权限错误 (70001-79999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 70001 | PERMISSION_UNAUTHENTICATED | 此操作需要登陆系统! | 未认证访问 | 先进行登录 |
| 70002 | PERMISSION_UNAUTHORIZED | 权限不足，无权操作! | 权限不足 | 联系管理员分配权限 |
| 70003 | PERMISSION_EXPIRE | 登录状态过期！ | 登录状态过期 | 重新登录 |
| 70004 | PERMISSION_TOKEN_EXPIRED | token已过期 | Token过期 | 刷新Token |
| 70005 | PERMISSION_LIMIT | 访问次数受限制 | 访问频率过高 | 降低访问频率 |
| 70006 | PERMISSION_TOKEN_INVALID | 无效token | Token格式错误 | 检查Token格式 |
| 70007 | PERMISSION_SIGNATURE_ERROR | 签名失败 | 签名验证失败 | 检查签名算法 |

### ⚠️ 系统繁忙 (90001-99999)

| 错误码 | 枚举名称 | 错误信息 | 使用场景 | 处理建议 |
|--------|----------|----------|----------|----------|
| 90001 | SYSTEM_ERROR | 系统繁忙，请稍后重试！ | 系统异常 | 稍后重试或联系技术支持 |
| 90002 | SYSTEM_BUSY | 系统繁忙，请稍后重试！ | 系统负载过高 | 稍后重试 |

## 🔍 错误处理最佳实践

### 1. 客户端处理
```javascript
// 示例：JavaScript 错误处理
function handleApiResponse(response) {
    if (response.code === 1) {
        // 成功处理
        return response.data;
    } else {
        // 错误处理
        switch (response.code) {
            case 20001:
                // 未登录，跳转到登录页
                redirectToLogin();
                break;
            case 20002:
                // 登录失败，显示错误信息
                showError('用户名或密码错误');
                break;
            case 50004:
                // 验证码错误，刷新验证码
                refreshVerifyCode();
                break;
            default:
                // 其他错误，显示通用错误信息
                showError(response.message);
        }
    }
}
```

### 2. 服务端处理
```java
// 示例：Java 错误处理
public Result<String> login(String username, String password) {
    try {
        // 业务逻辑
        return Result.success(token);
    } catch (UserNotFoundException e) {
        return Result.error(ResultEnum.USER_NOT_EXIST, null);
    } catch (PasswordMismatchException e) {
        return Result.error(ResultEnum.USER_LOGIN_ERROR, null);
    } catch (Exception e) {
        log.error("登录失败", e);
        return Result.error(ResultEnum.SYSTEM_ERROR, null);
    }
}
```

### 3. 错误日志记录
- **记录级别**: ERROR级别记录系统错误，WARN级别记录业务错误
- **记录内容**: 包含错误码、错误信息、用户ID、请求参数等
- **敏感信息**: 避免在日志中记录密码等敏感信息

## 📈 错误监控

### 1. 错误统计
- 按错误码统计错误频率
- 按时间段分析错误趋势
- 按用户分析错误分布

### 2. 告警机制
- 系统错误达到阈值时触发告警
- 特定错误码频繁出现时告警
- 错误率超过正常范围时告警


### 当前版本: v1.0.0


## 📞 技术支持

如遇到错误码相关问题，请提供以下信息：
- 错误码和错误信息
- 请求的API接口和参数
- 用户ID和操作时间
- 相关的日志信息

联系方式：
- **技术文档**: [API变更文档](API_CHANGES.md)
- **配置文档**: [配置说明](CONFIGURATION.md)
- **问题反馈**: 请通过系统日志或技术支持渠道反馈
