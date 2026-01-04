import { defineConfig } from "vitepress";
import { withMermaid } from "vitepress-plugin-mermaid";

export default withMermaid(
  defineConfig({
    base: '/RecordPlatform/',
    title: "RecordPlatform",
    description:
      "Enterprise-grade file attestation platform powered by blockchain and distributed storage",

    head: [["link", { rel: "icon", href: "/favicon.ico" }]],

    // Ignore localhost links (development environment URLs)
    ignoreDeadLinks: [/^http:\/\/localhost/],

    locales: {
      en: {
        label: "English",
        lang: "en",
        link: "/en/",
        themeConfig: {
          nav: [
            { text: "Guide", link: "/en/getting-started/", activeMatch: "/en/getting-started/" },
            { text: "Architecture", link: "/en/architecture/", activeMatch: "/en/architecture/" },
            { text: "Deployment", link: "/en/deployment/", activeMatch: "/en/deployment/" },
            { text: "API", link: "/en/api/", activeMatch: "/en/api/" },
          ],
          sidebar: {
            "/en/getting-started/": [
              {
                text: "Getting Started",
                collapsed: false,
                items: [
                  { text: "Overview", link: "/en/getting-started/" },
                  {
                    text: "Prerequisites",
                    link: "/en/getting-started/prerequisites",
                  },
                  {
                    text: "Installation",
                    link: "/en/getting-started/installation",
                  },
                  {
                    text: "Configuration",
                    link: "/en/getting-started/configuration",
                  },
                  {
                    text: "Frontend",
                    link: "/en/getting-started/frontend",
                  },
                ],
              },
            ],
            "/en/architecture/": [
              {
                text: "Architecture",
                collapsed: false,
                items: [
                  { text: "Overview", link: "/en/architecture/" },
                  {
                    text: "System Overview",
                    link: "/en/architecture/system-overview",
                  },
                  {
                    text: "Distributed Storage",
                    link: "/en/architecture/distributed-storage",
                  },
                  {
                    text: "Blockchain Integration",
                    link: "/en/architecture/blockchain-integration",
                  },
                  { text: "Security", link: "/en/architecture/security" },
                ],
              },
            ],
            "/en/deployment/": [
              {
                text: "Deployment",
                collapsed: false,
                items: [
                  { text: "Overview", link: "/en/deployment/" },
                  {
                    text: "Docker Compose",
                    link: "/en/deployment/docker-compose",
                  },
                  { text: "Production", link: "/en/deployment/production" },
                  { text: "Monitoring", link: "/en/deployment/monitoring" },
                ],
              },
            ],
            "/en/api/": [
              {
                text: "API Reference",
                collapsed: false,
                items: [
                  { text: "Overview", link: "/en/api/" },
                  { text: "Error Codes", link: "/en/api/error-codes" },
                ],
              },
            ],
            "/en/troubleshooting/": [
              {
                text: "Troubleshooting",
                collapsed: false,
                items: [
                  { text: "Overview", link: "/en/troubleshooting/" },
                  {
                    text: "Common Issues",
                    link: "/en/troubleshooting/common-issues",
                  },
                ],
              },
            ],
          },
        },
      },
      zh: {
        label: "中文",
        lang: "zh-CN",
        link: "/zh/",
        themeConfig: {
          nav: [
            { text: "指南", link: "/zh/getting-started/", activeMatch: "/zh/getting-started/" },
            { text: "架构", link: "/zh/architecture/", activeMatch: "/zh/architecture/" },
            { text: "部署", link: "/zh/deployment/", activeMatch: "/zh/deployment/" },
            { text: "API", link: "/zh/api/", activeMatch: "/zh/api/" },
          ],
          sidebar: {
            "/zh/getting-started/": [
              {
                text: "快速开始",
                collapsed: false,
                items: [
                  { text: "概述", link: "/zh/getting-started/" },
                  {
                    text: "环境准备",
                    link: "/zh/getting-started/prerequisites",
                  },
                  {
                    text: "安装部署",
                    link: "/zh/getting-started/installation",
                  },
                  {
                    text: "配置说明",
                    link: "/zh/getting-started/configuration",
                  },
                  {
                    text: "前端开发",
                    link: "/zh/getting-started/frontend",
                  },
                ],
              },
            ],
            "/zh/architecture/": [
              {
                text: "系统架构",
                collapsed: false,
                items: [
                  { text: "概述", link: "/zh/architecture/" },
                  {
                    text: "系统总览",
                    link: "/zh/architecture/system-overview",
                  },
                  {
                    text: "分布式存储",
                    link: "/zh/architecture/distributed-storage",
                  },
                  {
                    text: "区块链集成",
                    link: "/zh/architecture/blockchain-integration",
                  },
                  { text: "安全设计", link: "/zh/architecture/security" },
                ],
              },
            ],
            "/zh/deployment/": [
              {
                text: "部署指南",
                collapsed: false,
                items: [
                  { text: "概述", link: "/zh/deployment/" },
                  {
                    text: "Docker Compose",
                    link: "/zh/deployment/docker-compose",
                  },
                  { text: "生产环境", link: "/zh/deployment/production" },
                  { text: "监控配置", link: "/zh/deployment/monitoring" },
                ],
              },
            ],
            "/zh/api/": [
              {
                text: "API 参考",
                collapsed: false,
                items: [
                  { text: "概述", link: "/zh/api/" },
                  { text: "错误码参考", link: "/zh/api/error-codes" },
                ],
              },
            ],
            "/zh/troubleshooting/": [
              {
                text: "故障排除",
                collapsed: false,
                items: [
                  { text: "概述", link: "/zh/troubleshooting/" },
                  {
                    text: "常见问题",
                    link: "/zh/troubleshooting/common-issues",
                  },
                ],
              },
            ],
          },
        },
      },
    },

    themeConfig: {
      logo: "/logo.svg",
      socialLinks: [
        {
          icon: "github",
          link: "https://github.com/SoarCollab/RecordPlatform",
        },
      ],
      search: {
        provider: "local",
      },
      footer: {
        message: "Released under the Apache 2.0 License.",
        copyright: "Copyright © 2025-2026 RecordPlatform",
      },
    },

    // Mermaid configuration - 使用 default 主题以支持自动深色模式切换
    mermaid: {
      // 使用 default 主题允许插件自动在明暗模式间切换
      // 'base' 主题会锁定颜色，不会响应主题变化
      flowchart: {
        curve: "basis",
        padding: 20,
        nodeSpacing: 50,
        rankSpacing: 60,
        htmlLabels: true,
        useMaxWidth: true,
      },
      sequence: {
        mirrorActors: false,
        bottomMarginAdj: 10,
        actorFontSize: 14,
        noteFontSize: 13,
        messageFontSize: 14,
        useMaxWidth: true,
      },
    },
    mermaidPlugin: {
      class: "mermaid",
    },
  })
);
