# 原生界面 Hook 注入点说明

原生 Compose 面板通过 Workshop API 的 `afterComposable(owner, method, inClass, params, priority, hook)`
注入到 Salt 宿主。宿主用 ByteBuddy 做字节码增强，四个参数必须**精确匹配**，因此宿主升级后需要复验。

## 当前使用的注入点（按尝试顺序）

| 顺序 | owner（目标类） | 方法 | inClass（调用点） | params | 说明 |
|---|---|---|---|---|---|
| 1 | `androidx.compose.ui.ୡ` | `Ϳ` | `androidx.compose.ui.Է` | `ӗ, ઠ, च, Modifier` | 标题栏组件，每个窗口只调用一次，最可靠 |
| 2 | `com.xuncorp.voxzen.ui.navigation.AppNavigationKt` | `Content` | 自身 | 无 | 导航内容根 |
| 3 | `androidx.compose.ui.ଚ` | `Ϳ` | `androidx.compose.ui.ଚ$Ԯ` | `androidx.compose.ui.غ$Ԫ` | 侧栏项（会被按项多次调用，插件内部做了面板去重） |

## 如何复验（宿主更新后）

```bash
python scripts/verify_hook_targets.py "D:\SteamLibrary\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
```

脚本会解析宿主归档中的 class 文件：

1. 目标类是否存在、是否包含目标方法
2. **调用点类（inClass）是否真的调用了「目标类#方法」**（解析常量池的 Methodref）
3. 调用描述符的**真实参数个数**是否与插件注册的 `params` 列表一致

全部通过时会输出 `通过候选: 3/3`。

## 运行时诊断

插件内已内置诊断，日志文件 `ncm-helper.log`：

- `原生界面预检: AppNavigationKt.Content 存在=…` —— 安装前预检
- `原生界面已安装（候选 N/3：owner#method）` —— 命中的注入点
- `原生界面候选 N 注入失败（…）: 原因` —— 某个候选注册失败
- `原生界面状态: installed=…, hits=…, target=…` —— 配置页「原生界面状态」按钮
- 打开面板时 Hook 从未被调用（`hits=0`）→ 自动切换下一个候选，全部失败则回退 Swing 面板

配置页「导出诊断信息」会把上述状态与日志尾部打包到桌面 zip，便于反馈。

## 面板查找逻辑

1. 插件启动时（拥有 `CLASS_TRANSFORM` 权限）按上表顺序尝试注册 Hook
2. 点击「打开网易云原生面板」时：
   - 未安装 → 回退 Swing 兼容面板
   - 已安装但 `hits == 0`（注入点不匹配）→ 卸载并切换下一个候选，提示再点一次
   - 正常触发 → 打开原生 Compose 面板（同一时刻只显示一个）

## 原生 UI 构建要求

原生 Kotlin/Compose 代码在**安装 Salt 的机器上**编译（编译期对着宿主 `ffmpeg-x64.dll` 中的类）：

```bash
python scripts/get_native_toolchain.py     # 下载并校验 Kotlin 2.4.0 + Compose 编译器
python build.py                           # 自动编译 Java + 原生 UI 并打包 .spmod
```

设置 `SALT_HOST_PACKAGE` 或 `SALT_HOME` 可指定宿主位置；未找到宿主归档时（例如 CI）会跳过原生 UI 编译。
