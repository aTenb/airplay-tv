<p align="center"><img src="docs/art/icon.png" width="128" alt="AirPlay TV icon"></p>

# AirPlay TV 原生增强版

[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/aTenb/airplay-tv)](https://github.com/aTenb/airplay-tv/releases/latest)

**为 Android TV / 电视盒子量身打造的 Apple AirPlay 原生级投屏接收端。**

无需安装臃肿复杂的商业投屏软件，无开屏广告，开机后台静默守护。iPhone、iPad 或 Mac 随时一键镜像投屏，秒级全屏唤醒；投屏结束或遥控器按返回，瞬时干净退回电视桌面，零中间态页面露头，呈现真正原汁原味的电视系统级投屏质感。

[⬇️ 下载最新版 APK (Releases)](https://github.com/aTenb/airplay-tv/releases/latest)

---

## ✨ 核心特色与深度优化

| 特性 | 本修改版优势 | 传统第三方投屏 App |
| :--- | :--- | :--- |
| **投屏唤醒** | **零闪烁原生直出**：黑底 0 帧闪烁直接弹出全屏视频容器，不加载设置页 | 画面闪烁，先加载普通应用界面或广告 |
| **退出投屏** | **秒切 TV 桌面**：手机断开或按遥控器返回瞬时退回桌面，无任何 UI 泄漏 | 退回到播放器或 App 设置主界面，需再按多次返回 |
| **架构分离** | **真正守护服务**：开机自启静默常驻，平时永远不需要手动打开 App 图标 | 必须先手动打开 App，电视才能被搜索到 |
| **系统级唤醒** | **突破 Android 10+ 限制**：采用高优先级全屏通知意图（`fullScreenIntent`）穿透 | 后台常被系统拦截，导致二次投屏无法弹出 |
| **防抖优化** | **杜绝握手冲突**：彻底剥离连接探针误触，确保屏幕镜像次次必连 | 探针冲突触发 600ms 防抖，导致镜像请求被吞 |
| **硬件解码** | **双架构原生适配**：支持 `armeabi-v7a`（32位电视）与 `arm64-v8a`，低延迟硬件加速 | 兼容性差，部分老电视或索尼电视卡顿闪退 |
| **本地化** | **100% 简体中文**：彻底清理波斯语/英文残留，遥控操作逻辑贴合国人习惯 | 汉化不全，多处外语及 RTL 排版错乱 |

---

## 📱 核心功能

- **无线屏幕镜像（Screen Mirroring）**：支持 iPhone、iPad、Mac 屏幕实时镜像，支持 H.264 与 H.265 硬解加速。
- **音视频流媒体投屏（AirPlay Video / Audio）**：支持 Safari 及各大视频 App 的原生 AirPlay 投屏，保留原始音频采样率。
- **DLNA 接收支持**：支持安卓手机及主流视频播放器（VLC、BubbleUPnP 等）DLNA 推流。
- **遥控器智能缩放（Zoom）**：屏幕镜像时按遥控器 ▲ / ▼ 可自由裁剪去除片源黑边。
- **防丢帧平滑渲染**：MediaCodec 输入队列弹性缓冲，拒绝处理器瞬时负载带来的马赛克与破音。
- **可信设备配对与安全性**：支持首连授权验证与独立 PIN 码保护，防止局域网误投或蹭投。
- **应用内在线更新**：内置 GitHub Releases 自动版本检查与一键无感静默更新。

---

## 📥 安装指南

### 方法一：直接下载安装（推荐）
1. 在 [Releases](https://github.com/aTenb/airplay-tv/releases/latest) 页面下载最新的 `AirPlay-TV-x.y.z.apk`。
2. 通过 U 盘、电视内置文件浏览器或电视助手安装到电视上。
3. **初次安装只需打开一次**，赋予必要权限（或重启电视使其完成开机广播初始化）。
4. 此后**日常使用无需手动打开 App**，电视在开机状态下 iPhone 随时下拉控制中心即可投屏。

### 方法二：通过 ADB 远程安装
确保电脑与电视处于同一局域网并已开启电视开发者选项 / ADB 调试：
```bash
adb connect <电视IP地址>:5555
adb install -r -d AirPlay-TV-1.1.0.apk
```

---

## 🎮 使用说明

1. 确保手机与电视连接在同一个 Wi-Fi 网络（同一局域网）。
2. **屏幕镜像**：
   - 打开 iPhone / iPad 控制中心，点击【屏幕镜像】，选择 `AirPlay TV`。
   - 电视将立即全屏显示镜像画面。
   - 手机端点击【停止镜像】或按下电视遥控器的【返回键】，电视将直接干净退回 TV 桌面。
3. **设置与修改参数**：
   - 如果需要修改设备名称（例如“客厅电视”）、调整分辨率、开启 60fps 或查看连接日志，在电视桌面点击 `AirPlay TV` 图标进入设置看板即可。修改完成后按返回键退出，后台服务将自动无缝热重载。

---

## 🛠️ 本地编译与构建

本项目支持在本地或 GitHub Actions CI 上全自动构建双架构安装包：

```bash
git clone https://github.com/aTenb/airplay-tv.git
cd airplay-tv

# 编译包含 armeabi-v7a 与 arm64-v8a 的双架构 Release / Debug 包
./gradlew assembleDebug -Pabis=armeabi-v7a,arm64-v8a
```

*构建环境要求：JDK 21, Android SDK 37, NDK 27.0.12077973。*

---

## 📄 开源许可与致谢

- 本项目基于 [GPL-3.0 协议](LICENSE) 开源。
- 感谢 [RPiPlay](https://github.com/FDH2/UxPlay)、[android-airplay-server](https://github.com/jqssun/android-airplay-server) 与 [MehdiEskandari/airplay-tv](https://github.com/MehdiEskandari/airplay-tv) 提供的初始基础与协议研究。
- 视频解码与渲染基于 [FFmpeg](https://ffmpeg.org)、[libplist](https://github.com/libimobiledevice/libplist) 与 [OpenSSL](https://www.openssl.org)。
