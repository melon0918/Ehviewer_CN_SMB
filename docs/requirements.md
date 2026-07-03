# Ehviewer SMB 远程存储 — 需求文档

> 最后更新: 2026-07-02

## 1. 项目概述

改造 Ehviewer_CN_SXJ 的下载存储系统，使其支持将画廊下载内容存储在远程 Windows SMB 服务器上。

## 2. 用户需求

| 需求 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| SMB 下载存储 | P0 | ✔ | 下载画廊时直接写入 SMB 服务器 |
| SMB 读取 | P0 | ✔ | 阅读已下载内容时从 SMB 服务器读取 |
| 凭证加密存储 | P0 | ✔ | 用户名密码使用 EncryptedSharedPreferences 加密保存 |
| SMB 文件浏览器 | P0 | ✔ | 在 App 内浏览 SMB 共享目录并选择下载根目录 |
| 单服务器配置 | P1 | ✔ | 仅支持一个 SMB 服务器配置 |
| 网络不可达时暂停 | P1 | ✔ | SMB 不可达时弹出对话框, 可选切换服务器或关闭 SMB |
| 多服务器支持 | P1 | ✔ | 支持配置多个 SMB 服务器, 故障自动/手动切换 |
| 本地缓存加速 | P1 | 待实现 | 利用本地 SD 卡缓存提升阅读体验 (Phase 4) |
| ZeroTier 兼容 | P2 | ✔ | 支持 LAN + ZeroTier VPN 双场景 (底层已支持) |

## 3. 连接方案

| 项 | 方案 | 状态 |
|---|------|------|
| 连接方式 | ZeroTier 虚拟 IP 访问家中 Win10 SMB 共享 | ✔ |
| SMB 协议 | SmbJ 0.13.0，自动协商 SMB 2.0/3.0 | ✔ |
| 密码存储 | EncryptedSharedPreferences (AES256_GCM) | ✔ |
| 配置方式 | 手动输入 IP → 凭据 → 浏览选目录 | ✔ |
| 连接复用 | 全局共享连接 (线程安全 synchronized) | ✔ |
| StrictMode | penaltyLog (主线程网络 I/O 不崩溃) | ✔ |

## 4. 已知限制

| 限制 | 原因 | 后续优化方向 |
|------|------|------------|
| 每次图片读取全量下载到临时文件 | Image.decode JNI 需要 FileInputStream | Phase 4 本地缓存 |
| 所有 SMB 操作串行执行 | 共享 DiskShare 非线程安全 | 连接池 |
| 不支持随机访问 | SMB 协议无此语义 | 可通过 DownloadManager 绕过 |
| 中文路径兼容性依赖 SMB 服务器 | SmbJ 通过 UTF-8 传输 | 无 |

## 5. 不变约束

- SpiderDen.java — 不修改 ✔
- DownloadManager.java — 不修改 ✔
- SpiderQueen.java — 不修改 ✔
- GalleryProvider2.java — 不修改 ✔
- 其他画廊阅读核心代码 — 不修改 ✔
