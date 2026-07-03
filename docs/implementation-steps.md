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

### Step 3.3 — 回退测试 [✔]
- [x] SMB 不可达时弹出"SMB 服务器不可达"对话框
- [x] 可手动切换到下一台服务器或关闭 SMB
- [x] 自动切换开关启用时静默切换
- [x] 全部不可达时自动关闭 SMB 总开关

---

## Phase 4: 多服务器支持 [✔]

### Step 4.1 — 数据模型 [✔]
- [x] `SmbServerConfig.java`: host/share/path/username 字段, fastjson 序列化
- [x] `Settings.java`: JSON 数组存储 `smb_servers`, 索引键密码 `smb_password_N`
- [x] `getSmbServers()` / `setSmbServers()` / `getActiveSmbServer()` / 迁移方法
- [x] `getDownloadLocation()` 改为从活跃服务器创建 SmbFile

### Step 4.2 — 服务器管理 UI [✔]
- [x] `SmbServerListActivity.java`: 服务器列表管理 (增删改)
- [x] `SmbConfigActivity.java`: 双模式 (添加/编辑)
- [x] 自动切换开关
- [x] 删除服务器后重新索引密码、调整活跃索引

### Step 4.3 — Export Page Settings [✔]
- [x] `DownloadFragment.java` 重定向到 SmbServerListActivity
- [x] `download_settings.xml` 更新配置按钮标题
- [x] `AndroidManifest.xml` 注册新 Activity

---

## Phase 5: 健康检测与故障切换 [✔]

### Step 5.1 — 快速健康检测 [✔]
- [x] `SmbConnectionManager.healthCheck()`: 独立连接, 2s 超时
- [x] 连接成功即视为可达 (移除 folderExists 探测)
- [x] 静态方法, 不干扰全局共享连接

### Step 5.2 — 故障切换 [✔]
- [x] `checkDownloadLocation()` AsyncTask 统一检测 + 切换
- [x] 遍历所有非活跃服务器 (修复仅高索引 bug)
- [x] 手动切换 / 自动切换 / 全部不可达三种分支
- [x] `ProgressDialog` 阻塞用户操作直至检测完成

### Step 5.3 — 并发安全 [✔]
- [x] `SmbFile.getShare()`: 延迟 15s 清理旧连接
- [x] `onPostExecute` 不再调用 `disconnectShared()`
- [x] 避免健康检查打断正在进行的 SMB 读取

---

## Phase 6: 打磨 [ ]

### Step 6.1 — 中文 UI [✔]
- [x] `values-zh-rCN/strings.xml`: 21 条 SMB 中文翻译
- [x] `SmbServerListActivity`: 按钮行垂直居中修复

### Step 6.2 — 清理与文档
- [ ] 移除调试日志 (Toast 调试输出, Log.e 等)
- [ ] 确认无硬编码测试值
- [x] 更新 devlog 和开发文档

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
