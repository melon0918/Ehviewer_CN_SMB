# Ehviewer 项目开发规范 — SMB 扩展补充

> 最后更新: 2026-07-02
> 基于实测验证补充的 SMB 开发规范

## 1. 代码风格

- 遵循项目现有风格: 4 空格缩进, 大括号换行
- 新增代码保持 Java 优先 (SmbJ 是 Java 库)
- 方法命名: 驼峰, 私有成员 mPrefix
- 常量: ALL_CAPS
- 日志: private static final String TAG

## 2. 架构规范

### 2.1 SmbFile UniFile 扩展

- 所有 `SmbFile` 方法必须保持与 `RawFile` 语义一致
- `smb://` URI 格式标准化: `smb://host/share/path`
- 异常时不抛 RuntimeException, 按 UniFile 惯例返回 null/false
- **构造函数已改为 public** (Settings.getDownloadLocation 需要跨包访问)

### 2.2 连接管理

- **全局共享连接**: 所有 SmbFile 实例复用同一个 `SmbConnectionManager` (static)
- **连接匹配**: 按 host + share + username + password 四元组匹配
- **线程安全**: 所有共享 DiskShare 的操作用 `synchronized(sLock)` 串行化
- **生命周期**: 连接由 SmbConnectionManager 管理, 各方法不关闭共享连接
- **断开**: `SmbFile.disconnectShared()` 全局断开

### 2.3 线程安全

- SMB 操作是网络 I/O, 必须异步执行 (通过现有 IoThreadPoolExecutor)
- 不在主线程调用 SmbFile 方法 (如无法避免, 需加 StrictMode 保护)
- DiskShare 非线程安全 → 通过 synchronized(sLock) 串行化所有访问
- 流操作 (`openInputStream`/`openOutputStream`) 打开文件句柄在锁内, 读写在锁外

### 2.4 错误处理

```
SMB I/O 错误处理层级:
  1. SmbFile 层: 捕获 SMBApiException, 返回 null/false (遵循 UniFile 契约)
  2. 连接管理器: 重试 (指数退避, 最多 3 次)
  3. SpiderDen 层: 由 Settings.getDownloadLocation() 返回 null 时自动降级
```

注意: SmbJ 的异常体系:
- `SMBApiException extends SMBRuntimeException extends RuntimeException`
- 所有 DiskShare 方法抛 SMBApiException, 不是 IOException
- 使用 `catch (IOException e)` 无法捕获 SMB 异常, 需用 `catch (Exception e)` 或 `catch (RuntimeException e)`

### 2.5 StrictMode 处理

SMB 文件操作涉及网络 I/O, 但部分代码路径在主线程执行:
- 全局: `EhApplication.onCreate()` 设置 `penaltyLog` 避免崩溃
- 局部: 每个 SmbFile 方法用 `relaxStrictMode()` + `finally restore` 包裹
- Stream close(): 内部也要加 StrictMode 保护 (close 也发送 SMB 请求)

### 2.6 健康检测规范

- `healthCheck()` 必须是静态方法, 使用独立连接, 不干扰全局共享连接
- 超时限制: connect ≤ 1500ms, soTimeout ≤ 500ms (总计 ≤ 2s)
- 连接 + 认证成功即视为可达, 不额外做 I/O 探测
- 所有资源在 finally 块中 `closeQuietly` 逆序关闭
- 由 `checkDownloadLocation()` 的 AsyncTask 统一调用

### 2.7 并发安全规范

- `getShare()` 凭据不匹配时: 交换引用指向新连接, 旧连接通过 Handler.postDelayed 延迟 15s 清理
- 禁止在健康检查的 `onPostExecute` 中调用 `disconnectShared()`
- 异步检测结果仅更新 UI/索引, 不主动断开共享连接
- 连接切换由后续 `getShare()` 按需处理

### 2.8 多服务器存储规范

- 服务器列表使用 fastjson JSON 数组存储在 `smb_servers` 键
- password 字段不参与 JSON 序列化, 使用 `smb_password_N` 索引键加密存储
- 删除服务器后必须重新索引密码 (`smb_password_{N}` → `smb_password_{N-1}`)
- `getActiveSmbServer()` 自动处理索引越界

## 3. 依赖管理

- SmbJ: `com.hierynomus:smbj:0.13.0` (锁定版本, 不要随意升级)
- 加密存储: `androidx.security:security-crypto:1.1.0-alpha06`
- 不引入其他不必要的依赖

### SmbJ 0.13.0 API 注意事项

```
DiskShare 缺少的方法:
  openInputStream(String)     → 用 openFile(path).getInputStream()
  openOutputStream(String)    → 用 openFile(path).getOutputStream()
  rename(String, String)      → 用 openFile(path).rename(newPath)
  getFileSize(String)         → 用 getFileInformation(path, FileStandardInformation.class).getEndOfFile()

DiskShare 已有的方法:
  list(String)                → List<FileIdBothDirectoryInformation>
  fileExists(String)          → boolean
  folderExists(String)        → boolean
  mkdir(String)               → void
  rm(String)                  → void
  openFile(String, ...)       → File
  openDirectory(String, ...)  → Directory
  getFileInformation(String, Class) → <T> T

目录列表:
  list("/") → 内部路径拼接产生双反斜杠, 某些 SMB 服务器失败
  替代: openDirectory("").list() → 路径用 "" 表示根

enum 使用:
  EnumSet.of(A, B)  ← 正确
  EnumSet.of(A | B) ← 错误 (编译错误)
  
FileAttributes.FILE_ATTRIBUTE_DIRECTORY 是枚举值, 判断目录用:
  (entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0
```

## 4. SmbConfigActivity 开发规范

- 使用 `AsyncTask` 执行 SMB 连接和目录列表 (遵循项目现有模式)
- 目录列表路径用 `""` 表示根, 不传 `"/"`
- 保存路径时转为 `"/" + path` 格式 (SmbFile 需要)
- 目录浏览器用 ScrollView + LinearLayout + 手动 TextView (不用 RecyclerView/ListView)
- 文件前缀用 `"> "` 纯文本, 不用 emoji

## 5. 文件清单

### 新增文件 (9个)

| 文件 | 说明 | 关键依赖 |
|------|------|---------|
| `SmbConnectionManager.java` | SMB 连接管理器 | SmbJ SMBClient/Connection/Session/DiskShare |
| `SmbFile.java` | UniFile SMB 实现 | SmbJ DiskShare, 静态 SmbConnectionManager |
| `SmbUriHandler.java` | smb:// URI 解析器 | UriHandler 接口 |
| `SmbServerConfig.java` | 服务器配置 POJO | fastjson |
| `SmbConfigActivity.java` | SMB 配置 UI | ToolbarActivity, SmbConnectionManager |
| `SmbServerListActivity.java` | 服务器管理列表 UI | ToolbarActivity, SmbServerConfig |
| `activity_smb_config.xml` | SMB 配置布局 | Material Design TextInputLayout |
| `activity_smb_server_list.xml` | 服务器列表布局 | SwitchCompat, ScrollView |
| `filter_log.ps1` | 日志过滤工具 | PowerShell |

### 修改文件 (10个)

| 文件 | 变更 |
|------|------|
| `app/build.gradle` | 添加 SmbJ + security-crypto 依赖 |
| `EhApplication.java` | StrictMode 配置, SmbFile 初始化, SmbUriHandler 注册 |
| `Settings.java` | SMB Key 常量, getter/setter, getDownloadLocation 扩展, JSON 列表存储, 迁移方法 |
| `DownloadFragment.java` | SMB 开关和配置按钮处理, 重定向到 SmbServerListActivity |
| `MainActivity.java` | checkDownloadLocation 异步化, 健康检测+故障切换, ProgressDialog |
| `AndroidManifest.xml` | 注册 SmbConfigActivity + SmbServerListActivity |
| `download_settings.xml` | 添加 SMB PreferenceCategory |
| `SmbConfigActivity.java` | 双模式 (添加/编辑), 保存到服务器列表 |
| `SmbConnectionManager.java` | 新增静态 healthCheck() 方法 |
| `strings.xml` + `values-zh-rCN/strings.xml` | 多服务器 + 中文 UI 字符串 |

## 6. 增量更新检查清单

当原版 Ehviewer 更新时, 逐项检查:

- [ ] `app/build.gradle` 依赖未冲突
- [ ] `EhApplication.onCreate()` 初始化链未变
- [ ] `Settings.getDownloadLocation()` 签名未变
- [ ] `DownloadFragment` Preference key 未变
- [ ] `MainActivity.checkDownloadLocation()` 签名未变
- [ ] `AndroidManifest.xml` Activity 注册区未变
- [ ] 编译验证: `./gradlew :app:compileAppReleaseDebugJavaWithJavac`
- [ ] 功能验证: 连接 SMB → 下载 → 阅读 → 开关切换
