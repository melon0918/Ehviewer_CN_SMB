# Ehviewer SMB 远程存储 — 实施步骤

> 最后更新: 2026-07-02
> ✔ = 已完成

---

## Phase 0: 项目准备

### Step 0.1 — 项目基础设施搭建 [✔]
- [x] 创建 docs/ 和 devlog/ 目录结构
- [x] 编写需求文档、技术设计、编码规范、实施步骤
- [x] 创建 CLAUDE.md

---

## Phase 1: 基础依赖与 SmbFile 骨架 [✔]

### Step 1.1 — 添加 SmbJ 和加密库依赖 [✔]
- [x] `app/build.gradle`: `com.hierynomus:smbj:0.13.0`
- [x] `app/build.gradle`: `androidx.security:security-crypto:1.1.0-alpha06`
- [x] `./gradlew :app:dependencies` 验证成功

### Step 1.2 — 创建 SmbConnectionManager [✔]
- [x] `com.hippo.ehviewer.client.SmbConnectionManager`
- [x] `connect(host, share, username, password)` → DiskShare
- [x] `disconnect()`, `isConnected()`, `getShare()`
- [x] 超时控制 (SmbConfig: withTimeout + withSoTimeout)
- [x] 重试逻辑 (指数退避 1s/2s/4s, 最多 3 次)

### Step 1.3 — 创建 SmbFile (UniFile 实现) [✔]
- [x] `com.hippo.unifile.SmbFile`
- [x] 实现所有 UniFile 抽象方法
- [x] SmbJ API 适配: `openFile()` → `getInputStream()`/`getOutputStream()`
- [x] 线程安全: synchronized(sLock) + 共享连接

### Step 1.4 — 创建 SmbUriHandler 并注册 [✔]
- [x] `com.hippo.unifile.SmbUriHandler`
- [x] 解析 `smb://` URI
- [x] `EhApplication.onCreate()` 注册

---

## Phase 2: 配置与 UI [✔]

### Step 2.1 — 添加 SMB 配置存储 [✔]
- [x] Settings.java: Key 常量 (host/share/path/username/enabled)
- [x] EncryptedSharedPreferences 加密密码
- [x] getter/setter 方法
- [x] 修改 `getDownloadLocation()` SMB 模式返回 SmbFile

### Step 2.2 — 创建 SmbConfigActivity [✔]
- [x] UI: TextInputLayout ×4 + Connect + 目录浏览器 + 按钮
- [x] 连接测试 (AsyncTask + SmbConnectionManager)
- [x] 目录浏览 (`openDirectory()` + `Directory.list()`)
- [x] 目录导航 (进入子目录 + ".." 返回)
- [x] 新建文件夹
- [x] 选择目录并保存

### Step 2.3 — 整合到下载设置页面 [✔]
- [x] `download_settings.xml`: 添加 SMB PreferenceCategory
- [x] `DownloadFragment.java`: 处理开关和配置按钮
- [x] `AndroidManifest.xml`: 注册 SmbConfigActivity

---

## Phase 3: 集成与下载验证 [✔]

### Step 3.1 — 下载流程集成 [✔]
- [x] `Settings.getDownloadLocation()` SMB 模式返回 SmbFile ✔ (Step 2.1)
- [x] SpiderDen 通过 UniFile 自动使用 SmbFile
- [x] 下载完整画廊到 SMB 服务器

### Bug 修复记录
- [x] **ensureDir() 路径加倍**: `createDirectory(getName())` → `mkdir(mPath)` (见 Bug 1)
- [x] **连接风暴**: 共享连接 + synchronized 串行化 (见 Bug 2)
- [x] **Image.decode 需要 FileInputStream**: 下载到临时文件后返回 (见 Bug 3)
- [x] **NetworkOnMainThread**: StrictMode penaltyLog + relaxStrictMode() (见 Bug 4)
- [x] **MainActivity.checkDownloadLocation()**: SMB 模式异步执行 (见 Bug 4)

### Step 3.2 — 阅读流程集成 [✔]
- [x] 从 SMB 读取已下载画廊
- [x] Image.decode(FileInputStream) 通过临时文件方式兼容
- [x] 翻页正常

### Step 3.3 — 回退测试 [ ]
- [ ] SMB 不可达时下载按钮状态
- [ ] 阅读时提示内容不可用
- [ ] SMB 断开/恢复流程

---

## Phase 4: 缓存优化 [ ]

### Step 4.1 — 缓存策略调整
- [ ] MODE_READ: 从 SMB 读取后写入本地缓存
- [ ] MODE_DOWNLOAD: 写入 SMB 同时预热缓存
- [ ] 增大缓存上限 (通过 Settings 可配置)

### Step 4.2 — 缩略图缓存
- [ ] 分析当前缩略图加载流程
- [ ] 如有必要: 添加缩略图本地缓存层

---

## Phase 5: 打磨 [ ]

### Step 5.1 — 错误处理与用户反馈
- [ ] SMB 连接失败时的 Toast/Snackbar 提示
- [ ] 下载设置页显示连接状态
- [ ] 网络切换时的合理行为

### Step 5.2 — 清理与文档
- [ ] 移除调试日志 (Toast 调试输出, Log.e 等)
- [ ] 确认无硬编码测试值
- [ ] 更新 devlog

---

## 已知 Bug 列表 (供后续跟进)

| # | 问题 | 原因 | 修复 | 文件 |
|---|------|------|------|------|
| 1 | ensureDir 创建 `.../dir/dir` | `createDirectory(getName())` 拼出双路径 | 直接 `mkdir(mPath)` | SmbFile.java |
| 2 | 多线程认证风暴 | 同时创建多个 SMB 连接 | 全局共享连接 + synchronized | SmbFile.java |
| 3 | Image.decode 强转 FileInputStream 失败 | JNI 需要文件描述符 | 下载到临时文件返回 | SmbFile.java |
| 4 | NetworkOnMainThread 崩溃 | 主线程调用 SMB 网络 I/O | StrictMode penaltyLog | EhApplication.java |
| 5 | 目录列表不显示 | RecyclerView + emoji 渲染问题 | ScrollView + 纯文本 | SmbConfigActivity.java |
| 6 | SmbJ getPathForList 双反斜杠 | `list("")` 产生 `\\\\*` | 改用 `openDirectory().list()` | SmbConfigActivity.java |

## 增量更新指南 (原版 App 更新后)

当原版 Ehviewer 更新时，需要:

1. **git merge** 上游变更
2. 重新验证以下文件的修改:
   - `app/build.gradle` — 确认依赖未冲突
   - `EhApplication.java` — 确认 onCreate() 注册链未变
   - `Settings.java` — 确认 getDownloadLocation() 签名未变
   - `DownloadFragment.java` — 确认 Preference key 未变
   - `MainActivity.java` — 确认 checkDownloadLocation() 签名未变
   - `AndroidManifest.xml` — 确认 Activity 注册区未变
3. 重新编译: `./gradlew :app:compileAppReleaseDebugJavaWithJavac`
4. 运行测试: 连接 SMB → 下载 → 阅读
