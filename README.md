# QringPrint (Android)

**错题小印系列 58mm 蓝牙热敏打印机的原生 Android 客户端**

基于 Jetpack Compose + Kotlin 开发，从原 HarmonyOS 版本 (ArkTS) 移植而来。

![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84) ![Language](https://img.shields.io/badge/language-Kotlin-7F52FF) ![Device](https://img.shields.io/badge/device-58mm%20%E7%83%AD%E6%95%8F%E6%89%93%E5%8D%B0%E6%9C%BA-7C5CE6) ![License](https://img.shields.io/badge/license-MIT-green)

---

## 这是什么

错题小印 (Qring / BeePrt BY 系列) 是一款 58mm 蓝牙热敏打印机，多用于错题、便签、标签打印。APP 服务器已经扑街，于是有了 QringPrint。

本项目是原 [QringPrint (HarmonyOS)](https://github.com/Thisko/QrintPrint) 的 Android 原生重写版本，通过经典蓝牙 (SPP) 直连打印机，把文字、图片、条码排版成 384 点宽的光栅位图，直接下发打印。界面全部用 Jetpack Compose 绘制，支持打印预览、模板复用和打印历史。

## 功能

### 打印

- **文字打印**：字体 / 字号 / 加粗斜体下划线 / 字间距 / 行间距排版设置，实时预览
- **图片打印**：三种抖动算法 (Floyd-Steinberg 误差扩散 / Ordered 有序 / Bayer)，实时预览
- **条码打印**：一维码 / 二维码，内容校验，防抖生成预览
- **自定义画布**：插入文字 / 图片 / 条码，拖拽移动、手柄缩放、双击编辑，可保存为模板
  - 支持竖排 / 横排两种打印方向
  - 横排模式下元素内容正立显示，打印时自动旋转输出

### 更多功能

- **课程表打印**：自定义课程表格式
- **标签纸打印**：标签排版打印
- **系统日历打印**：读取系统日历事件打印
- **待办打印**：Todo 清单打印

### 可靠性

- 电量 / 缺纸 / 开盖 / 过热实时监测，打印前自动体检拦截故障
- 打印期间暂停状态轮询，避免查询字节混入打印数据流
- 冷启动自动重连上次设备
- 前台服务保活，后台不断连

### 本地数据

- 模板保存 / 加载 / 重命名
- 打印历史持久化 (含缩略图)，一键重新打印

### UI

- 动态主题色：选择主题色全 App 立即生效
- Material 3 设计风格
- 浅色 / 深色模式

## 技术实现

### Qring 私有协议 (非标准 ESC/POS)

不依赖官方 SDK。打印机的状态查询、电量、浓度等走的是自己的 `10 FF` 系列命令，**只有走纸 (`ESC J`) 和光栅位图 (`GS v 0`) 两条沿用了 ESC/POS**。协议是通过对 `com.zxxk.xiaoyin.App` (错题小印) 的分析整理得到的。

- 状态字节单字节承载五个位：打印中 / 开盖 / 缺纸 / 低电压 / 过热
- 每包最大 1024 字节，包间 1ms
- 光栅编码：每行 48 字节 (384 点 / 8)，MSB first，**置 1 = 黑**

> 核心文件：`protocol/QringProtocol.kt`

### 三种抖动算法

Floyd-Steinberg 误差扩散、有序抖动、Bayer 抖动，**纯计算实现，不依赖任何图像库**，输出二值灰度交给光栅层打包。切换算法时复用已解码的灰度数据，不重复解码。

> 核心文件：`protocol/Dither.kt`

### 逐元素二值化再 OR 合并

画布上图片要 Floyd、文字要阈值 212、条码要阈值 128 且不能抖动——拍平到一张灰度再统一二值化会毁掉其中两类。做法是每个元素独立二值化，再 OR 合并到一张 384 点宽的二值画布。

> 核心文件：`render/ElementRenderer.kt`

## 项目结构

```
app/src/main/java/com/qring/print/
  bt/                 蓝牙连接与协议层
    PrinterConnection.kt     SPP 连接态机、分包收发、持久化重连
    PrinterDiscovery.kt      扫描、配对列表、设备名过滤
    PrinterPollingService.kt 前台服务保活
  protocol/           打印协议层
    QringProtocol.kt         私有协议命令常量、状态位解析
    RasterEncoder.kt         图片解码、灰度化、光栅打包、旋转
    Dither.kt                三种抖动算法 (纯计算)
    Compositor.kt            二值画布合成 (OR 合并)
  render/             元素渲染层
    ElementRenderer.kt       元素二值化、画布合成、预览/打印输出
  model/              数据模型
    CanvasDocument.kt        画布文档模型 (打印点坐标)
    PrinterStatus.kt         全局打印机状态
    BarcodeModel.kt          条码类型定义
  data/               本地持久化
    TemplateRepository.kt    模板 JSON 序列化与存储
    HistoryRepository.kt     打印历史持久化
  ui/                 Compose UI 层
    theme/                   设计令牌、动态主题色管理
    home/                    首页 (设备状态 + 功能入口)
    customprint/             自定义画布打印
    textprint/               文字打印
    imageprint/              图片打印
    codeprint/               条码打印
    calendar/                系统日历打印
    schedule/                课程表打印
    label/                   标签纸打印
    todo/                    待办打印
    history/                 打印历史
    template/                模板管理
    navigation/              导航图
```

## 构建

### 环境要求

- Android Studio (Koala/Ladybug 或更新版本)
- JDK 21
- Android SDK 36 (compileSdk)
- 最低支持：Android 10 (API 26)
- 设备：错题小印系列 58mm 蓝牙热敏打印机

### 步骤

1. 克隆仓库
2. 用 Android Studio 打开
3. 等待 Gradle Sync 完成（Gradle 8.14.5 会自动下载）
4. 连接设备，Run

命令行构建：

```bash
cd <项目根> && gradlew assembleDebug
```

输出 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

## 常见问题

**连不上打印机？** 先确认手机蓝牙已开启、蓝牙权限已授予；设备需处于配对列表或扫描发现范围。设备名带 `Qring` 前缀的会被默认过滤选中。

**打印出来模糊 / 偏淡？** 图片打印时选合适的抖动算法（文字 / 图表用阈值模式更好），或调高打印浓度设置。

**横排打印出来和竖排一样？** 确保使用最新版本，横排打印时数据会自动旋转 90 度输出。

## 参与贡献

欢迎 Issue 和 PR。提 Bug 时麻烦附上手机型号、Android 版本和复现步骤。

## 免责声明

QringPrint 是个人开发的第三方客户端，与错题小印官方无关。打印机通信协议是通过对官方 App 的分析整理得到的，**仅供学习参考，严禁商用**；如你认为此实现侵害了你的权益，请联系作者下架。

## 开源协议

[MIT License](LICENSE)

---

Fork from [Thisko/QrintPrint](https://github.com/Thisko/QrintPrint) (HarmonyOS / ArkTS)
