# ShellMgr

Android Shell 脚本管理器 —— 在手机上浏览、管理、执行 `.sh` 脚本，支持 root 权限。

## 功能

- 浏览文件系统（带书签）
- 查看脚本内容
- 前台/后台执行脚本
- root 权限静默执行（KernelSU / Magisk）
- 脚本历史记录（最近 50 条）
- 新建脚本、查看文件属性
- 书签管理

## 兼容性

- Android 5.0+（minSdk 21）
- 需要 root（KernelSU 或 Magisk）

## 编译

### 要求

- Linux 环境（或 WSL）
- Android SDK Build-Tools 34.0.0
- Android SDK Platform 34
- JDK 8+

路径可在 `apk_build.sh` 里按需修改。

### 构建

```bash
git clone https://github.com/Ying-Tang-W/shellmgr.git
cd shellmgr
bash apk_build.sh . /sdcard/Download/ShellMgr.apk
```

产物在 `build/aligned.apk`。

## 安装

```bash
adb install ShellMgr.apk
```

或直接传到手机点按安装。首次打开后去 KernelSU / Magisk 授权 root。

## 注意事项

- 中文路径支持已通过 UTF-8 编码修复
- 安装/重装后需在 root 管理器中重新授权
- 当前不支持交互式脚本（read -p 等），待 PTY 方案实现

## 项目结构

```
shellmgr/
  AndroidManifest.xml
  apk_build.sh          # 一键构建脚本
  src/com/shmgr/app/
    MainActivity.java   # 主界面（~590 行）
  res/
    values/styles.xml
    mipmap-hdpi/ic_launcher.png
```

## License

MIT
