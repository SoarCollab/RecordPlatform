---
layout: home

hero:
  name: RecordPlatform
  text: 企业级文件存证平台
  tagline: 基于区块链与分布式存储技术，为企业提供安全、不可篡改的文件存证服务
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/getting-started/
    - theme: alt
      text: 查看源码
      link: https://github.com/SoarCollab/RecordPlatform

features:
  - icon: 🔗
    title: 区块链存证
    details: 文件元数据存储在 FISCO BCOS 区块链上，确保不可篡改和完整可追溯性。
    link: /zh/architecture/blockchain-integration
    linkText: 了解更多
  - icon: 💾
    title: 分布式存储
    details: 双副本冗余存储，故障域隔离，自动故障转移，满足企业级高可用需求。
    link: /zh/architecture/distributed-storage
    linkText: 了解更多
  - icon: 🔒
    title: 端到端加密
    details: 分块上传支持 AES-GCM 或 ChaCha20-Poly1305 加密，数据在传输和存储中全程保护。
    link: /zh/architecture/security
    linkText: 了解更多
  - icon: 🔄
    title: 断点续传
    details: 支持大文件分块上传，自动断点续传，中断后不丢失任何进度。
  - icon: 📤
    title: 安全分享
    details: 生成分享码，支持访问次数限制、过期时间设置，完整审计追踪。
  - icon: 👥
    title: 多租户隔离
    details: 数据库、缓存、存储路径完全隔离，满足企业级多租户部署需求。
---
