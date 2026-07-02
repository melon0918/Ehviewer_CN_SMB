# Ehviewer SMB 远程存储 — 技术设计文档

> 最后更新: 2026-07-02
> 基于实测验证的最终设计

## 1. 架构总览

```
┌──────────────────────────────────────────────────┐
│               UI 层 (新增/修改)                     │
│  SmbConfigActivity     DownloadFragment (修改)      │
│  (SMB 浏览器 + 配置)   (存储位置选项增加 SMB)        │
├──────────────────────────────────────────────────┤
│              UniFile 抽象层 (新增实现)               │
│  SmbFile + SmbUriHandler                          │
│  ── 封装 SmbJ 库的 File/Directory 操作              │
│  ── 注册到 UniFile.addUriHandler()                 │
│  ── 全局共享连接 (static SmbConnectionManager)       │
│  ── synchronized(sLock) 线程安全                   │
├──────────────────────────────────────────────────┤
│              SMB 连接管理层 (新增)                    │
│  SmbConnectionManager                             │
│  ── 全局复用 (static, 按 host/share/user/pass 匹配)  │
│  ── 自动重连 + 超时控制                            │
├──────────────────────────────────────────────────┤
│              现有层 (不修改)                        │
│  SpiderDen / DownloadManager / GalleryProvider2   │
│  ── 仍通过 UniFile 操作文件，对后端无感知             │
│  ── Settings.getDownloadLocation() 返回 SmbFile    │
├──────────────────────────────────────────────────┤
│              临时文件层 (新增)                       │
│  SmbFile.openInputStream()                        │
│  ── 下载 SMB 文件到 cacheDir/smb_*.img             │
│  ── 返回 FileInputStream (JNI 解码需要)             │
│  ── close() 时自动删除                             │
└──────────────────────────────────────────────────┘
```

## 2. 核心新增文件

### 2.1 SmbFile.java

```
包: com.hippo.unifile
继承: UniFile (abstract)
底层: com.hierynomus.smbj (SmbJ 0.13.0)

关键方法:
  - createFile(String):        SmbFile  → openFile() + close()
  - createDirectory(String):   SmbFile  → share.mkdir()
  - getUri():                  Uri      → smb://host/share/path
  - getName():                 String
  - isDirectory()/isFile():    boolean  → share.folderExists()/fileExists()
  - ensureDir():               boolean  → 递归 mkdir (直接 mkdir(mPath))
  - ensureFile():              boolean  → openFile() + close() (直接 at mPath)
  - subFile(String):           SmbFile
  - delete():                  boolean  → share.rm() + 递归删除子目录
  - exists():                  boolean  → fileExists || folderExists
  - listFiles():               SmbFile[] → share.list() → FileIdBothDirectoryInformation
  - findFile(String):          SmbFile
  - openInputStream():         FileInputStream → 下载到临时文件后返回
  - openOutputStream():        OutputStream → openFile().getOutputStream()
  - length():                  long     → getFileInformation(FileStandardInformation).getEndOfFile()
  - lastModified():            long     → getFileInformation(FileBasicInformation).getLastWriteTime()

连接管理:
  - 全局共享 DiskShare (static SmbConnectionManager)
  - 连接匹配条件: host + share + username + password 完全相同
  - 线程安全: 所有 SMB 操作在 synchronized(sLock) 内

临时文件:
  - 位置: setTempDir() 设置 (EhApplication 传 getCacheDir())
  - 文件名: smb_XXXXXXXXX.img
  - 清理: close() 时 delete, 启动时 cleanTempFiles()

StrictMode:
  - 每个方法 relaxStrictMode() + finally restore
  - 同时 EhApplication 全局 penaltyLog (不 crash)
```

### 2.2 SmbUriHandler.java

```
包: com.hippo.unifile
实现: UriHandler

fromUri(Context, Uri) → UniFile:
  解析 smb:// 协议 URI
  格式: smb://host/share/path 或 smb://user:pass@host/share/path
  构造 SmbFile 返回
  非 smb:// 返回 null (链式处理)

注册:
  EhApplication.onCreate():
    UniFile.addUriHandler(new SmbUriHandler())
```

### 2.3 SmbConnectionManager.java

```
包: com.hippo.ehviewer.client

功能:
  - 管理 SmbJ Client/Session/DiskShare 连接生命周期
  - 单服务器连接
  - 断线自动重连 (指数退避)
  - 连接健康检测 (isConnected())

API:
  - connect(String host, String share, String username, String password): DiskShare
  - disconnect()
  - isConnected(): boolean
  - getShare(): DiskShare

连接参数:
  - 超时: connectTimeout=15s, readTimeout=30s (SmbConfig)
  - 重试: 最多 3 次, 间隔 1s/2s/4s 指数退避
  - SMB 版本: SmbJ 自动协商 2.0/3.0

SmbConfig:
  SmbConfig.builder()
    .withTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)   // 连接超时
    .withSoTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)    // Socket 超时
    .build()
```

### 2.4 SmbConfigActivity.java

```
包: com.hippo.ehviewer.ui
继承: ToolbarActivity

布局: activity_smb_config.xml
  - TextInputLayout ×4: host, share, username, password
  - Button: Connect
  - TextView: status
  - ScrollView + LinearLayout: 目录列表 (手动添加 TextView)
  - Button ×2: Select, New Folder

目录浏览:
  - 连接后 openDirectory(path, AccessMask.GENERIC_READ, FILE_ATTRIBUTE_DIRECTORY,
      FILE_SHARE_READ|WRITE, FILE_OPEN, FILE_DIRECTORY_FILE)
  - Directory.list() 获取条目
  - 父目录 ".." 导航
  - 新建文件夹 → share.mkdir()

路径格式:
  - 内部: "" 表示根目录 (SmbJ 需要)
  - 保存: "/" + path (SmbFile 需要)
```

## 3. 修改现有文件

### 3.1 app/build.gradle

```groovy
dependencies {
    implementation 'com.hierynomus:smbj:0.13.0'           // SMB
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'  // 密码加密
}
```

### 3.2 EhApplication.java

```
onCreate() 新增:
  1. StrictMode.setThreadPolicy(detectAll().penaltyLog())  // SMB 网络 I/O 不崩溃
  2. SmbFile.setTempDir(getCacheDir())                     // 临时文件目录
  3. SmbFile.cleanTempFiles()                              // 清理残留
  4. UniFile.addUriHandler(new SmbUriHandler())            // 注册 URI 处理器
```

### 3.3 Settings.java

新增 Keys:
```java
KEY_SMB_ENABLED  = "smb_enabled"    // boolean
KEY_SMB_HOST     = "smb_host"       // String
KEY_SMB_SHARE    = "smb_share"      // String
KEY_SMB_PATH     = "smb_path"       // String
KEY_SMB_USERNAME = "smb_username"   // String
```

密码存储: EncryptedSharedPreferences (独立实例, AES256_GCM)
```java
MasterKey masterKey = new MasterKey.Builder(sContext)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
EncryptedSharedPreferences.create(sContext, "smb_secure_prefs", masterKey,
    AES256_SIV, AES256_GCM);
```

修改 `getDownloadLocation()`:
```java
if (getSmbEnabled() && host != null && share != null) {
    return new SmbFile(null, host, share, path, username, password);
}
// 原有本地路径逻辑...
```

### 3.4 DownloadFragment.java

新增处理:
- `KEY_SMB_ENABLED` → `Settings.setSmbEnabled()`
- `"smb_configure"` → 启动 `SmbConfigActivity` (REQUEST_CODE_SMB_CONFIG)
- `onActivityResult` 处理 SMB 配置返回

### 3.5 download_settings.xml

新增 PreferenceCategory:
```xml
<PreferenceCategory android:title="SMB Remote Storage">
    <SwitchPreference android:key="smb_enabled" .../>
    <Preference android:key="smb_configure" .../>
</PreferenceCategory>
```

### 3.6 MainActivity.java

修改 `checkDownloadLocation()`:
- SMB 启用时: AsyncTask 异步执行 `ensureDir()`, 不阻塞主线程
- 避免 `NetworkOnMainThreadException`

## 4. SmbJ 0.13.0 API 适配要点

### 4.1 DiskShare 方法映射

| 需求 | SmbJ 方法 | 备注 |
|------|-----------|------|
| 打开输入流 | `openFile(path, ...).getInputStream()` | 无直接的 openInputStream |
| 打开输出流 | `openFile(path, ...).getOutputStream()` | 无直接的 openOutputStream |
| 文件重命名 | `openFile(path).rename(newPath)` | DiskShare 无 rename 方法 |
| 文件大小 | `getFileInformation(path, FileStandardInformation.class).getEndOfFile()` | 无 getFileSize |
| 最后修改时间 | `getFileInformation(path, FileBasicInformation.class).getLastWriteTime().toEpochMillis()` | |
| 目录列表 | `list(path)` → `List<FileIdBothDirectoryInformation>` | |
| 判断是否为目录 | `folderExists(path)` | 或从 FileAttributes 位掩码判断 |
| 文件存在 | `fileExists(path)` | |
| 创建目录 | `mkdir(path)` | |
| 删除 | `rm(path)` | 目录需先清空内容 |
| 打开目录句柄 | `openDirectory(path, ...)` | 用于 `Directory.list()` |

### 4.2 异常处理

```
SMBApiException extends SMBRuntimeException extends RuntimeException
                                  ↑ 不是 IOException!
```

所有 DiskShare 方法抛 `SMBApiException`, 不是 `IOException`。在 try-catch(IOException) 块中无法捕获。

### 4.3 枚举类型

SmbJ 的枚举如 `SMB2ShareAccess` 实现 `EnumWithValue`, 在 `EnumSet.of()` 中用逗号分隔多个值:
```java
// 正确
EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE)
// 错误 (编译错误)
EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ | SMB2ShareAccess.FILE_SHARE_WRITE)
```

## 5. 已知 Bug 与修复记录

### Bug 1: ensureDir() 路径加倍 (Phase 3)

- **堆栈**: `SPAException: STATUS_OBJECT_PATH_NOT_FOUND` in `SmbFile.createDirectory`
- **原因**: `ensureDir()` 调用 `createDirectory(getName())`, createDirectory 通过 `getChildPath(name)` 拼接路径 → `mPath + "/" + name` = `.../gallery/gallery`
- **修复**: 直接 `share.mkdir(mPath)` 在当前路径创建，递归父目录保留
- **同样问题**: `ensureFile()` 也修复为直接 `openFile(mPath)`

### Bug 2: 连接风暴 (Phase 3)

- **堆栈**: `STATUS_REQUEST_NOT_ACCEPTED: Authentication failed`
- **原因**: 多 SpiderWorker 线程同时创建 SMB 连接，服务器拒绝并发认证
- **修复**: 全局共享连接 (static SmbConnectionManager), synchronized 串行化
- **演进**: 创建锁 → 共享连接

### Bug 3: Image.decode 需要 FileInputStream (Phase 3)

- **堆栈**: `ClassCastException: SmbFileInputStream cannot be cast to FileInputStream`
- **原因**: `Image.decode()` 是 JNI 调用，需要文件描述符。SpiderQueen/ImageBitmapHelper 中硬编码 `(FileInputStream)` 强制转换
- **修复**: `openInputStream()` 下载到 cacheDir 临时文件，返回 FileInputStream
- **代价**: 每张图片从 SMB 全量下载到本地再解码，非流式

### Bug 4: NetworkOnMainThreadException (Phase 3)

- **堆栈**: GalleryActivity.onCreate → SpiderQueen.getStartPage → SpiderDen.getDownloadDir → SmbFile.ensureDir → 网络 I/O
- **原因**: GalleryActivity 在主线程调用文件操作，SMB 模式下触发网络 I/O
- **修复**: `EhApplication` 全局 StrictMode penaltyLog + 各方法 relaxStrictMode

### Bug 5: SmbConfigActivity 目录列表不渲染 (Phase 2)

- **原因1**: RecyclerView 特定渲染问题 (simple_list_item_1 可能不兼容)
- **原因2**: emoji "📁" 在某些设备/模拟器上显示为方块
- **修复**: ScrollView + LinearLayout + 手动添加 TextView, 纯文本前缀 "> "

### Bug 6: SmbJ list() 路径问题 (Phase 2)

- **原因**: SmbJ 的 `getPathForList("", "*")` 产生 `\\host\share\\*` (双反斜杠)
- **修复**: 改用 `openDirectory("").list()` 绕过 getPathForList

## 6. 安全设计

- 密码使用 EncryptedSharedPreferences + AES256_GCM 加密
- URI 中的密码部分不在 UI 中明文显示
- 连接信息不写入日志
- SMB 连接仅通过用户主动配置触发, 无后台自动扫描
- 临时文件在 cacheDir 内, close() 立即删除

## 7. 限制

- `createRandomAccessFile()`: 不支持, 抛 IOException (SMB 协议无随机访问语义)
- 图片读取: 全量下载到临时文件再解码 (非流式, Image.decode JNI 限制)
- 中文路径: 需确保 SMB 服务器 UTF-8 编码
- 多线程: 所有 SMB 操作串行化 (共享连接 + synchronized)
- Android StrictMode: 全局 penaltyLog, 网络违规不再崩溃但记日志
