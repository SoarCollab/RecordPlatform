# Web Crypto API 要求和故障排除

## 概述

本应用使用 Web Crypto API 进行文件解密功能。Web Crypto API 是现代浏览器提供的加密功能接口，但它有特定的安全要求。

## 安全上下文要求

Web Crypto API **只能在安全上下文中使用**，这意味着：

### ✅ 支持的环境
- `https://` 协议的网站
- `localhost` (任何端口)
- `127.0.0.1` (任何端口)
- `*.localhost` 域名

### ❌ 不支持的环境
- `http://` 协议的网站（除了 localhost）
- 局域网 IP 地址（如 `192.168.x.x`, `10.x.x.x`, `198.18.x.x`）
- 非安全的远程访问

## 当前问题诊断

如果您遇到 `TypeError: Cannot read properties of undefined (reading 'importKey')` 错误，这通常意味着：

1. **非安全上下文**: 您正在通过 HTTP 协议或非 localhost IP 地址访问应用
2. **浏览器不支持**: 使用的浏览器版本过旧
3. **功能被禁用**: 浏览器安全设置禁用了 Web Crypto API

## 解决方案

### 方案 1: 使用 HTTPS（推荐）
```bash
# 为开发服务器配置 HTTPS
# 在 vite.config.js 中添加：
server: {
  https: true,
  // ... 其他配置
}
```

### 方案 2: 使用 localhost
```bash
# 修改 vite.config.js 中的 host 配置
server: {
  host: 'localhost',  // 而不是 '0.0.0.0'
  port: 13999
}
```

然后通过 `http://localhost:13999` 访问应用。

### 方案 3: 配置本地域名
在 `/etc/hosts` (Linux/Mac) 或 `C:\Windows\System32\drivers\etc\hosts` (Windows) 中添加：
```
127.0.0.1 myapp.localhost
```

然后通过 `http://myapp.localhost:13999` 访问。

### 方案 4: 使用反向代理
使用 nginx 或其他反向代理服务器提供 HTTPS 访问。

## 浏览器兼容性

### 支持的浏览器版本
- Chrome 37+
- Firefox 34+
- Safari 7+
- Edge 12+

### 检查浏览器支持
在浏览器控制台中运行：
```javascript
console.log('Web Crypto API 支持:', !!window.crypto?.subtle);
```

## 测试功能

应用中提供了"测试解密功能"按钮，可以：
1. 检查 Web Crypto API 可用性
2. 运行基本的加密/解密测试
3. 提供详细的诊断信息

## 开发建议

### 本地开发
```bash
# 推荐的开发配置
npm run dev
# 然后访问 http://localhost:13999
```

### 生产部署
- 必须使用 HTTPS
- 确保 SSL 证书有效
- 考虑使用 CDN 和负载均衡

## 常见错误和解决方法

### 错误: "Web Crypto API 不可用"
**原因**: 非安全上下文
**解决**: 使用 HTTPS 或 localhost

### 错误: "浏览器不支持 SubtleCrypto API"
**原因**: 浏览器版本过旧
**解决**: 更新浏览器到最新版本

### 错误: "加密操作失败"
**原因**: 数据格式或密钥问题
**解决**: 检查数据完整性和密钥格式

## 技术细节

### 使用的加密算法
- **对称加密**: AES-GCM (256位密钥)
- **哈希算法**: SHA-256
- **密钥导入格式**: Raw bytes

### 安全特性
- 认证加密 (AES-GCM)
- 完整性验证 (SHA-256 哈希)
- 密钥链验证

## 联系支持

如果问题仍然存在，请提供以下信息：
1. 浏览器版本和类型
2. 访问的完整 URL
3. 控制台错误信息
4. 诊断报告（通过"测试解密功能"按钮获取）
