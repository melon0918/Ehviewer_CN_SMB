# Ehviewer_CN_SXJ - CLAUDE.md

## 项目概述

基于 Hippo Seven 原版 Ehviewer 的 E-Hentai/ExHentai Android 客户端, 当前任务是为其添加 SMB 远程存储功能。

## 文档索引

| 文档 | 路径 | 说明 |
|------|------|------|
| 需求规格 | [docs/requirements.md](docs/requirements.md) | 功能需求与约束 |
| 技术设计 | [docs/technical-design.md](docs/technical-design.md) | 架构方案与核心设计 |
| 编码规范 | [docs/coding-standards.md](docs/coding-standards.md) | 开发规范与约定 |
| 实施步骤 | [docs/implementation-steps.md](docs/implementation-steps.md) | 分步执行计划 |
| 开发日志 | [devlog/](devlog/) | 每日完成/待办记录 |

## 工作说明

### 推进方式
- 严格按照 [实施步骤](docs/implementation-steps.md) 逐步推进
- 每完成一个 Step 更新一次 devlog
- 每 Step 结束后确保项目可编译
- 前一步完成并验证后再进入下一步
- **当前阶段**: Phase 5 (健康检测与故障切换) 已完成, 待 Phase 6 打磨

### 开发约束
- **不修改** SpiderDen.java / DownloadManager.java / SpiderQueen.java / GalleryProvider2.java
- 新增代码与项目现有代码风格保持一致 (Java 优先)
- SMB 连接参数不硬编码, 不写死测试值
- 所有 SMB 文件操作通过 UniFile 抽象层, 避免直接耦合 SmbJ

### 安全要求
- 密码使用 EncryptedSharedPreferences 存储
- 不将凭证信息写入日志
- URI 中密码部分不在 UI 明文显示

### 新增文件清单

| 文件 | 说明 |
|------|------|
| `SmbConnectionManager.java` | SMB 连接管理 (connect/connectFast/disconnect/isConnected/healthCheck) |
| `SmbFile.java` | UniFile SMB 实现 (22 个抽象方法, 全局共享连接) |
| `SmbUriHandler.java` | `smb://` URI 解析 |
| `SmbServerConfig.java` | 服务器配置 POJO, fastjson 序列化 |
| `SmbServerListActivity.java` | 多服务器管理 UI |

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `Settings.java` | JSON 列表存储, 索引密码, 迁移方法, getDownloadLocation 适配多服务器 |
| `MainActivity.java` | 健康检测 + 故障切换 AsyncTask, ProgressDialog |
| `SmbConfigActivity.java` | 双模式 (添加/编辑), 保存到服务器列表 |
| `SmbConnectionManager.java` | 新增静态 healthCheck() |

### 沟通方式
- 用户确认后再执行重要决策
- 遇到不确定的接口行为, 先查阅项目现有实现
- 需要跨多 Step 的变更, 先讨论再动手

### 开发日志维护
- devlog 命名: `YYYY-MM-DD.md`
- 日志格式:
  - 今日完成 (bullet list)
  - 决策记录 (如有)
  - 待办 (引用 implementation-steps 中的 Step 编号)
