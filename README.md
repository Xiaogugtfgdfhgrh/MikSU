# MilkSU

<img src="manager/app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png" width="112" height="112" alt="MilkSU">

**MilkSU乃优者之长，去败者之短。**

以“万物皆可收，万物皆可隐藏，万物皆适配”为核心理念，为 Android 带来兼顾 Root 管理、KPM 扩展与 SUSFS 隐藏能力的使用体验。

[下载正式版](https://github.com/Xiaogugtfgdfhgrh/MikSU/releases/latest) · [官方 TG 频道](https://t.me/Xiaogu_zcr521) · [问题反馈](https://github.com/Xiaogugtfgdfhgrh/MikSU/issues)

MilkSU 提供内核级 Root 授权、应用权限配置、模块管理、KPM 管理和 SUSFS 功能。KPM 与 SUSFS 均可在管理器设置中按需开启，底层采用独立适配的内核钩子，并保留 KernelSU 模块与 WebUI 的兼容接口。

当前正式版本：**0.2.0（32800）**。应用 ID：`io.milksu.manager`。

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 内核级 Root | 按应用授权与撤销 Root，支持 App Profile、自定义 UID/GID、权限组、权能和 SELinux 上下文 |
| 内核钩子 | 使用 KernelSU-Next 来源的 ARM64 钩子后端，衔接权限管理、启动事件与模块生命周期 |
| KPM 扩展 | 导入 `.kpm`、加载确认、临时加载、开机加载、参数控制与运行状态查询 |
| SUSFS 隐藏 | 提供路径、挂载、KSTAT、uname、cmdline/bootconfig、open redirect 等能力，兼容对应的 SUSFS 用户态工具与模块 |
| 即时 SUSFS 开关 | 开启集成后立即加载并校验，同时保存开机加载设置；关闭时查询并确认实际运行状态 |
| SELinux 与日志 | 提供隐藏 SELinux 修改、AVC 日志欺骗、SU 日志和传统 SU 命令支持设置 |
| 模块生态 | 支持模块安装、更新、启停、卸载、WebUI 与 metamodule |
| 管理器界面 | 提供 Miuix / Material 两种界面，首页按集成状态显示 SUSFS 支持情况与版本 |
| 正式版更新 | 从本仓库稳定 Release 检查管理器更新，保持应用签名与内核认证身份一致 |

## KPM 与 SUSFS

### KPM

设置中开启 **KPM 集成**后，底部导航显示为：**主页 / KPM / 超级用户 / 模块 / 设置**；未开启时保留四个主要页面。

- 在 KPM 页面点击右下角 `+`，通过系统文件选择器导入 `.kpm`。
- 选择文件后显示加载确认，可勾选“临时加载”；临时加载不会启用开机加载。
- 加载后直接在模块列表使用控制与卸载入口，无需进入单独详情页。
- 参数、自动加载与错误状态均由守护进程管理，操作后重新读取实际状态。
- 安装或移除 KPM 引擎需要完整重启。模块代码仍驻留时会显示待重启状态，重启后完成清理。

### SUSFS

设置中的 **SUSFS 集成**同时控制当前加载与开机加载，不再提供独立的开机自启动开关。

- 开启后立即加载，确认后端可用再保存启动配置。
- 首页仅在集成开启时显示 SUSFS 支持情况、版本与后端类型。
- 支持对应的 SUSFS 模块与 `ksu_susfs` 工具，具体规则由所用模块或工具配置。
- **AVC 日志欺骗**位于“隐藏 SELinux 修改”下方，开关读取实际内核状态，并与 SUSFS 共用 AVC 服务。

## 支持范围

当前发布的 APK 面向 **ARM64 / GKI**，内置下列 8 组 KMI 的 Root 与 SUSFS 模块：

| KMI | 验证情况 |
| --- | --- |
| `android14-6.1` | 已完成单台设备真机验收，包括 Root 授权、Profile、SUSFS、AVC、KPM 及关闭 / 开启后的完整重启 |
| `android12-5.10` | 已构建并校验内置资产，未在对应真机验证 |
| `android13-5.10` | 已构建并校验内置资产，未在对应真机验证 |
| `android13-5.15` | 已构建并校验内置资产，未在对应真机验证 |
| `android14-5.15` | 已构建并校验内置资产，未在对应真机验证 |
| `android15-6.6` | 已构建并校验内置资产，未在对应真机验证 |
| `android16-6.12` | 已构建并校验内置资产，未在对应真机验证 |
| `android17-6.18` | 已构建并校验内置资产，未在对应真机验证 |

相同 KMI 不代表所有厂商内核都兼容。KPM 集成还需要通过设备、内核及镜像预检；非 GKI、其他架构和未验证设备不作兼容承诺。

## 安装与使用

### 一、下载管理器

从 [GitHub Releases](https://github.com/Xiaogugtfgdfhgrh/MikSU/releases/latest) 下载正式 APK 和 `SHA256SUMS`。

当前正式包：`MilkSU_0.2.0_32800.apk`。下载后可在同一目录校验：

```sh
sha256sum -c SHA256SUMS
```

### 二、安装 Root

1. 安装 MilkSU 管理器，确认设备架构与 KMI 在支持范围内。
2. 首次安装时，在安装入口选择“选择文件并修补”，使用与当前系统版本一致的启动镜像，按设备实际分区选择 `boot`、`init_boot` 或 `vendor_boot`。
3. 检查修补结果后，按设备对应的刷写方式写入匹配分区与槽位，重启后确认首页显示“工作中”。
4. 已具备可用 Root 的兼容设备可使用管理器提供的直接安装入口；具体选项由设备状态决定。

### 三、启用扩展

- **KPM**：设置 → KPM 集成 → 完整重启 → KPM 页面导入模块。
- **SUSFS**：设置 → SUSFS 集成，当前启动立即生效，同时保存开机加载配置。
- **AVC**：设置 → 隐藏 SELinux 修改下方的“AVC 日志欺骗”。
- **普通模块**：模块页面安装 ZIP，并按模块要求重启或打开 WebUI。

## 更新与恢复

- 管理器默认检查本仓库的稳定 Release。更高版本使用递增的 `versionCode` 和相同正式签名。
- 也可以从 Releases 下载新版 APK 覆盖安装。只有源码提交、没有有效 APK 的 Release 或检查失败时，不会误报更新。
- 管理器 APK 升级与设备内的 Root 更新是两个步骤；更新后根据首页内核状态使用安装入口更新 Root。
- 关闭 KPM 集成会恢复其保存的原始内核，并保留 MilkSU Root；关闭后需要完整重启。
- 涉及镜像的安装会校验备份、候选镜像与写入后的分区内容，并保留恢复记录。
- 守护进程使用 `/data/adb/ksud`，核心目录为 `/data/adb/ksu/`，MilkSU 专用状态位于 `/data/adb/ksu/MilkSU/`。

## 常见问题

| 现象 | 检查方向 |
| --- | --- |
| 没有 KPM 底部入口 | 先在设置中开启 KPM 集成，并确认设备预检与实际配置状态 |
| KPM 提示需要重启 | 引擎变更或模块驻留尚未结束，需要完整重启 |
| SUSFS 信息未出现在首页 | 确认 SUSFS 集成已开启，且后端加载成功 |
| 开关不可用或操作失败 | 查看实际错误，确认 Root、管理器认证、KMI 与内核能力匹配 |
| 没有检测到管理器更新 | 确认存在更高版本的正式 Release，并且当前网络能够访问 GitHub |

## 从源码构建

使用 Linux / WSL Ubuntu，构建工具与缓存默认保存在 `/opt/milksu`：

```sh
bash tools/bootstrap-milksu.sh all
bash tools/bootstrap-kpatch-toolchain.sh
bash tools/build-all.sh --source-ready
```

构建需要 Android、Rust、内核构建工具链及对应签名配置。正式构建要求管理器 APK 的证书与内核认证参数一致；持续维护时复用同一正式密钥。

- [管理器与签名配置](manager/README.milksu.md)
- [构建、验收与镜像恢复细节](docs/MILKSU_TECHNICAL.md)
- [手动构建工作流](.github/workflows/milksu-build.yml)

## 目录结构

```text
kernel/              Root 内核模块、ARM64 钩子与 SUSFS
userspace/           ksud、ksuinit 与用户态工具
manager/             Android 管理器
uapi/                内核与用户态接口
vendor/kpatch-next/  KPM 引擎及相关工具源码
tools/               构建、打包、签名与验收工具
```

## 开源协议

管理器与用户态代码遵循 [GPL-3.0-or-later](LICENSE)，内核部分遵循 [GPL-2.0](kernel/LICENSE)。各组件保留其许可证、版权声明与来源记录。

## 上游项目

感谢以下项目及其贡献者。具体来源版本与适配记录保留在源码中的 provenance 文件内。

| 项目 | 链接 |
| --- | --- |
| KernelSU | [tiann/KernelSU](https://github.com/tiann/KernelSU) |
| KernelSU-Next | [KernelSU-Next/KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next) |
| KPatch-Next | [KernelSU-Next/KPatch-Next](https://github.com/KernelSU-Next/KPatch-Next) |
| KPatch-Next-Module | [KernelSU-Next/KPatch-Next-Module](https://github.com/KernelSU-Next/KPatch-Next-Module) |
