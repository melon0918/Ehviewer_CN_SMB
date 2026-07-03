# EhViewer CN SMB

基于 [xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) 的 E-Hentai/ExHentai Android 客户端，**添加 SMB 远程存储支持**。

---

## 特性

- ✅ 画廊下载到 SMB 服务器（Windows SMB 共享 / NAS / 任何支持 SMB 2.0/3.0 的设备）
- ✅ 从 SMB 服务器读取已下载画廊
- ✅ 内置 SMB 文件浏览器（连接测试、目录选择、新建文件夹）
- ✅ 凭证加密存储（EncryptedSharedPreferences + AES256_GCM）
- ✅ **多服务器支持**: 配置多个 SMB 服务器, 支持故障自动/手动切换
- ✅ **快速健康检测**: 2 秒超时检测 SMB 服务器可达性
- ✅ **自动切换开关**: 当前服务器不可达时自动尝试下一台
- ✅ 基于原版 Ehviewer_CN_SXJ，所有原版功能完整保留

## 使用

1. 下载安装 APK
2. 进入 **设置 → 下载设置 → SMB Remote Storage**
3. 开启 **Enable SMB storage**
4. 点击 **Manage SMB servers**
5. 点击 **Add SMB server** 添加服务器
6. 填入服务器地址、共享名、凭据，点击 **Connect**
7. 浏览目录并选择下载根目录 → **Select this directory**
8. 可添加多个服务器，开启 **Auto-switch server** 启用自动故障切换
9. 此后下载的画廊将写入 SMB 服务器

## Build

Windows

```bash
> git clone https://github.com/melon0918/Ehviewer_CN_SMB.git
> cd Ehviewer_CN_SMB
> ./gradlew app:assembleAppReleaseDebug
```

Linux

```bash
$ git clone https://github.com/melon0918/Ehviewer_CN_SMB.git
$ cd Ehviewer_CN_SMB
$ ./gradlew app:assembleAppReleaseDebug
```

APK 在 `app/build/outputs/apk/appRelease/debug/` 目录下。

## 技术栈

- **SMB 协议**: [SmbJ 0.13.0](https://github.com/hierynomus/smbj) (纯 Java SMB2 实现)
- **凭证加密**: AndroidX Security Crypto (EncryptedSharedPreferences)
- **文件抽象**: UniFile 层 (`SmbFile` 继承 `UniFile`，与 `RawFile` 同级)

详细技术文档见 [docs/technical-design.md](docs/technical-design.md)。

## 与原版的差异

| 差异 | 说明 |
|------|------|
| 包名 | `com.ehviewer.smb.debug` |
| 新增文件 | `SmbFile.java`, `SmbConnectionManager.java`, `SmbUriHandler.java`, `SmbServerConfig.java`, `SmbConfigActivity.java`, `SmbServerListActivity.java` |
| 修改文件 | `Settings.java`, `EhApplication.java`, `DownloadFragment.java`, `MainActivity.java`, `SmbConnectionManager.java`, `build.gradle`, `AndroidManifest.xml`, `strings.xml` |
| 遵循约束 | `SpiderDen.java`, `DownloadManager.java`, `SpiderQueen.java`, `GalleryProvider2.java` 未修改 |

## 已知限制

- `Image.decode()` JNI 需要 `FileInputStream`, SMB 文件读取需全量下载到临时缓存
- 所有 SMB 操作串行执行 (全局共享连接，线程安全同步)
- 不支持 `createRandomAccessFile()` (SMB 协议无随机访问语义)
- Android StrictMode 已调整为 `penaltyLog`, 主线程 SMB 操作不崩溃但会在日志中记录
- 旧 SMB 连接延迟 15 秒清理 (避免中断进行中的读取)

## Changelog

### v2.1 (基于 2.0.1.8)

- **多服务器支持**: 配置多个 SMB 服务器, 支持故障自动/手动切换
- **快速健康检测**: 2 秒超时检测服务器可达性, 启动时弹出 ProgressDialog 防止误操作
- **故障切换**: 当前服务器不可达时自动或手动切换到下一台
- **自动切换开关**: 设置中可开启/关闭自动切换
- **中文 UI**: 完整中文翻译
- **并发安全优化**: 服务器切换时延迟清理旧连接, 避免中断进行中的读取

### v2.0 (基于 2.0.1.8)

- 添加 SMB 远程存储支持
- 添加 SMB 连接管理器（超时、重试、全局连接复用）
- 添加 SMB 文件浏览器（连接测试、目录导航、新建文件夹）
- 添加 EncryptedSharedPreferences 密码加密存储
- 支持切换本地/SMB 存储模式
- 从 SMB 读取已下载画廊

### 原版 2.0.1.8 (2026/06/01)

完整原版变更日志见 [原版仓库](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)。

---

## Thanks

感谢 Ehviewer 奠基人 [Hippo/seven332](https://github.com/seven332)    
感谢 [xiaojieonly/Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) 的持续维护

本项目基于以下开源库构建：
- [SmbJ](https://github.com/hierynomus/smbj) — SMB 协议纯 Java 实现
- [AOSP](http://source.android.com/)
- [okhttp](https://github.com/square/okhttp)
- [greenDAO](https://github.com/greenrobot/greenDAO)
- 以及原版使用的所有开源库