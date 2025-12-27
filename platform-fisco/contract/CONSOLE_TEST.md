# FISCO BCOS 智能合约控制台测试文档

> **版本**：v1.0  
> **更新日期**：2024-12-21  
> **合约**：Sharing.sol (继承 Storage.sol)

---

## 使用说明

- 使用 `latest` 关键字自动调用最新部署的 Sharing 合约
- `bytes32[]` 数组中的元素需要用双引号包裹，如 `["0x123...","0x456..."]`
- 字符串参数使用双引号包裹
- 测试前请确保合约已部署：`deploy Sharing`

---

## 一、Storage 合约接口测试

### 1. storeFile - 存储文件

**函数签名**：`storeFile(string fileName, string uploader, string content, string param) returns (bytes32)`

```bash
# 1.1 正常存储文件
call Sharing latest storeFile "test_file.txt" "alice" "QmHashAbC123XyZ789" "size=1024"

# 1.2 存储另一个文件（不同用户）
call Sharing latest storeFile "document.pdf" "bob" "QmHashDef456AbC012" "size=2048,type=pdf"

# 1.3 存储第三个文件（用于后续批量测试）
call Sharing latest storeFile "image.png" "alice" "QmHashGhi789Jkl345" "size=512,type=image"
```

**边界测试**：

```bash
# 1.4 文件名为空（预期失败：File name cannot be empty）
call Sharing latest storeFile "" "alice" "QmHashXyz" "param"

# 1.5 上传者为空（预期失败：Uploader name cannot be empty）
call Sharing latest storeFile "test.txt" "" "QmHashXyz" "param"

# 1.6 内容为空（预期失败：File content cannot be empty）
call Sharing latest storeFile "test.txt" "alice" "" "param"
```

---

### 2. getUserFiles - 查询用户所有文件

**函数签名**：`getUserFiles(string uploader) returns (FileInfo[])`

```bash
# 2.1 查询已存储文件的用户
call Sharing latest getUserFiles "alice"

# 2.2 查询另一个用户
call Sharing latest getUserFiles "bob"

# 2.3 查询没有文件的用户（预期：返回空数组）
call Sharing latest getUserFiles "newuser"
```

**边界测试**：

```bash
# 2.4 用户名为空（预期失败：Uploader name cannot be empty）
call Sharing latest getUserFiles ""
```

---

### 3. getFile - 查询单个文件

**函数签名**：`getFile(string uploader, bytes32 fileHash) returns (File)`

```bash
# 3.1 查询存在的文件（使用 storeFile 返回的 fileHash）
call Sharing latest getFile "alice" "0x<fileHash>"
```

**边界测试**：

```bash
# 3.2 查询不存在的文件（预期失败：File does not exist）
call Sharing latest getFile "alice" "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

# 3.3 用错误的用户名查询（预期失败：File does not belong to this user）
call Sharing latest getFile "bob" "0x<alice的fileHash>"
```

---

### 4. deleteFile - 删除单个文件

**函数签名**：`deleteFile(string uploader, bytes32 fileHash) returns (bool)`

```bash
# 4.1 正常删除自己的文件
call Sharing latest deleteFile "alice" "0x<fileHash>"
```

**边界测试**：

```bash
# 4.2 删除不存在的文件（预期失败：File does not exist）
call Sharing latest deleteFile "alice" "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

# 4.3 删除别人的文件（预期失败：Not file owner）
call Sharing latest deleteFile "bob" "0x<alice的fileHash>"
```

---

### 5. deleteFiles - 批量删除文件

**函数签名**：`deleteFiles(string uploader, bytes32[] fileHashes) returns (bool)`

```bash
# 5.1 批量删除多个文件
call Sharing latest deleteFiles "alice" ["0x<hash1>","0x<hash2>"]
```

**边界测试**：

```bash
# 5.2 包含不存在的文件（预期失败：One of the files does not exist）
call Sharing latest deleteFiles "alice" ["0x<valid_hash>","0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"]

# 5.3 空数组（预期失败：File hashes array cannot be empty）
call Sharing latest deleteFiles "alice" []
```

---

## 二、Sharing 合约接口测试

### 6. shareFiles - 分享文件

**函数签名**：`shareFiles(string uploader, bytes32[] fileHashes, uint256 expireMinutes) returns (string)`

```bash
# 6.1 分享单个文件（有效期60分钟）
call Sharing latest shareFiles "alice" ["0x<fileHash>"] 60

# 6.2 分享多个文件（有效期24小时 = 1440分钟）
call Sharing latest shareFiles "alice" ["0x<hash1>","0x<hash2>"] 1440

# 6.3 分享文件（有效期30天 = 43200分钟，最大值）
call Sharing latest shareFiles "alice" ["0x<fileHash>"] 43200
```

**边界测试**：

```bash
# 6.4 过期时间为0（预期失败：Expire minutes must be greater than 0）
call Sharing latest shareFiles "alice" ["0x<fileHash>"] 0

# 6.5 过期时间超过30天（预期失败：Expire minutes cannot exceed 30 days）
call Sharing latest shareFiles "alice" ["0x<fileHash>"] 43201

# 6.6 分享不属于自己的文件（预期失败：Not owner of one of the files）
call Sharing latest shareFiles "bob" ["0x<alice的fileHash>"] 60

# 6.7 空文件数组（预期失败：File hashes array cannot be empty）
call Sharing latest shareFiles "alice" [] 60

# 6.8 上传者为空（预期失败：Uploader name cannot be empty）
call Sharing latest shareFiles "" ["0x<fileHash>"] 60
```

---

### 7. getSharedFiles - 获取分享的文件

**函数签名**：`getSharedFiles(string shareCode) returns (string uploader, File[] files, int256 expireTime)`（取消分享时 expireTime 返回 -1）

```bash
# 7.1 使用有效分享码获取文件（使用 shareFiles 返回的分享码）
call Sharing latest getSharedFiles "<shareCode>"
```

**边界测试**：

```bash
# 7.2 使用不存在的分享码（预期失败：Share code does not exist）
call Sharing latest getSharedFiles "XXXXXX"

# 7.3 分享码长度不正确（预期失败：Invalid share code length）
call Sharing latest getSharedFiles "ABC"

# 7.4 分享码长度不正确（预期失败：Invalid share code length）
call Sharing latest getSharedFiles "ABCDEFGHIJ"
```

---

### 8. getShareInfo - 获取分享详情（不校验有效性）

**函数签名**：`getShareInfo(string shareCode) returns (string uploader, bytes32[] fileHashes, uint256 expireTime, bool isValid)`

```bash
# 8.1 查询有效分享码的信息
call Sharing latest getShareInfo "<shareCode>"

# 8.2 查询已取消的分享码信息（isValid 应为 false）
call Sharing latest getShareInfo "<cancelledShareCode>"
```

**边界测试**：

```bash
# 8.3 分享码不存在（预期失败：Share code does not exist）
call Sharing latest getShareInfo "NOTEXS"

# 8.4 分享码长度不正确（预期失败：Invalid share code length）
call Sharing latest getShareInfo "AB"
```

---

### 9. cancelShare - 取消分享

**函数签名**：`cancelShare(string shareCode)`

```bash
# 9.1 取消有效的分享
call Sharing latest cancelShare "<shareCode>"
```

**边界测试**：

```bash
# 9.2 再次取消同一分享（预期失败：Share not found or already cancelled）
call Sharing latest cancelShare "<shareCode>"

# 9.3 取消不存在的分享码（预期失败：Share not found or already cancelled）
call Sharing latest cancelShare "NOTEXS"

# 9.4 分享码长度不正确（预期失败：Invalid share code length）
call Sharing latest cancelShare "ABC"
```

---

### 10. getUserShareCodes - 获取用户所有分享码

**函数签名**：`getUserShareCodes(string uploader) returns (string[])`

```bash
# 10.1 查询有分享记录的用户
call Sharing latest getUserShareCodes "alice"

# 10.2 查询没有分享记录的用户（预期：返回空数组）
call Sharing latest getUserShareCodes "newuser"
```

---

## 三、完整测试流程

按顺序执行以下命令进行完整的功能测试：

```bash
# ==================== 准备阶段 ====================

# 1. 部署合约（如果尚未部署）
deploy Sharing

# ==================== 文件存储测试 ====================

# 2. 存储第一个文件，记录返回的 fileHash1
call Sharing latest storeFile "photo.jpg" "alice" "QmPhoto123ABC" "size=4096,type=image"

# 3. 存储第二个文件，记录返回的 fileHash2
call Sharing latest storeFile "video.mp4" "alice" "QmVideo456DEF" "size=10240,type=video"

# 4. 存储第三个文件，记录返回的 fileHash3
call Sharing latest storeFile "music.mp3" "alice" "QmMusic789GHI" "size=2048,type=audio"

# 5. 查询 alice 的所有文件列表
call Sharing latest getUserFiles "alice"

# 6. 查询单个文件详情（替换为实际的 fileHash1）
call Sharing latest getFile "alice" "0x<fileHash1>"

# ==================== 文件分享测试 ====================

# 7. 创建分享（有效期24小时），记录返回的 shareCode1
call Sharing latest shareFiles "alice" ["0x<fileHash1>","0x<fileHash2>"] 1440

# 8. 通过分享码获取文件
call Sharing latest getSharedFiles "<shareCode1>"

# 9. 查询分享详情
call Sharing latest getShareInfo "<shareCode1>"

# 10. 查询用户的所有分享码
call Sharing latest getUserShareCodes "alice"

# ==================== 取消分享测试 ====================

# 11. 取消分享
call Sharing latest cancelShare "<shareCode1>"

# 12. 验证分享已取消（isValid 应为 false）
call Sharing latest getShareInfo "<shareCode1>"

# 13. 使用已取消的分享码获取文件（预期返回 expireTime = -1）
call Sharing latest getSharedFiles "<shareCode1>"

# ==================== 文件删除测试 ====================

# 14. 删除单个文件
call Sharing latest deleteFile "alice" "0x<fileHash1>"

# 15. 验证文件已删除
call Sharing latest getUserFiles "alice"

# 16. 批量删除剩余文件
call Sharing latest deleteFiles "alice" ["0x<fileHash2>","0x<fileHash3>"]

# 17. 验证所有文件已删除
call Sharing latest getUserFiles "alice"
```

---

## 四、参数格式参考

| 参数类型    | 格式说明                        | 示例                      |
| ----------- | ------------------------------- | ------------------------- |
| `string`    | 双引号包裹                      | `"hello world"`           |
| `bytes32`   | 双引号包裹的 0x + 64 位十六进制 | `"0x123...abc"`           |
| `bytes32[]` | 中括号，元素用双引号包裹        | `["0x123...","0x456..."]` |
| `uint256`   | 直接数字（不用引号）            | `60`                      |
| `bool`      | 小写 true/false                 | `true`                    |

---

## 五、常见错误信息

| 错误信息                                | 原因                     |
| --------------------------------------- | ------------------------ |
| `File name cannot be empty`             | storeFile 时文件名为空   |
| `Uploader name cannot be empty`         | 上传者/用户名为空        |
| `File content cannot be empty`          | storeFile 时内容为空     |
| `File already exists`                   | 相同参数的文件已存在     |
| `File does not exist`                   | 查询/删除不存在的文件    |
| `File does not belong to this user`     | 用错误用户名查询文件     |
| `Not file owner`                        | 尝试删除别人的文件       |
| `One of the files does not exist`       | 批量删除中有不存在的文件 |
| `File hashes array cannot be empty`     | 批量操作时数组为空       |
| `Expire minutes must be greater than 0` | 过期时间为 0             |
| `Expire minutes cannot exceed 30 days`  | 过期时间超过 43200 分钟  |
| `Not owner of one of the files`         | 分享不属于自己的文件     |
| `Invalid share code length`             | 分享码不是 6 位          |
| `Share code does not exist`             | 分享码不存在             |
| `Share has expired`                     | 分享已过期               |
| `Share not found or already cancelled`  | 分享不存在或已取消       |

---

## 六、事件日志

合约会触发以下事件，可在交易回执中查看：

| 事件名称         | 触发函数    | 参数                                        |
| ---------------- | ----------- | ------------------------------------------- |
| `FileStored`     | storeFile   | fileName, uploader, fileHash, uploadTime    |
| `FileDeleted`    | deleteFile  | uploader, fileHash                          |
| `FilesDeleted`   | deleteFiles | uploader, fileHashes                        |
| `FileShared`     | shareFiles  | shareCode, uploader, fileHashes, expireTime |
| `ShareCancelled` | cancelShare | shareCode, uploader                         |
