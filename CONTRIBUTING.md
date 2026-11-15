# 贡献指南

感谢您愿意改进「题了个题」。这份文档说明分支、提交、构建和 PR 的要求，尽量让每一次贡献都能顺利合入。

## 分支命名

| 类型 | 格式 | 示例 |
|---|---|---|
| 功能 | `feat/<issue号>-<简述>` | `feat/149-question-coverage` |
| 修复 | `fix/<issue号>-<简述>` | `fix/122-fullscreen-debounce` |
| 维护 | `chore/<简述>` | `chore/gitee-mirror` |
| 发布 | `release/v<版本>` | `release/v0.3.37` |

从 `main` 切出，不要把多个不相关的改动放在同一个分支里。

## 提交信息

```
<type>: <一句话说明（中文）>

- 细节 1
- 细节 2
```

`type` 可用：`feat` / `fix` / `docs` / `style` / `refactor` / `chore` / `build` / `release`。

提交信息写「改了什么、为什么」，不需要写测试过程；一个提交只做一件事，方便回滚。

## 本地开发

### 家长面板（parent-ui）

```bash
cd parent-ui
npm ci
npm run dev      # 浏览器打开 127.0.0.1，填入设备的 IP:8099
```

`npm run dev` 不带设备时使用内置的预览数据，可以离线改界面。

### Android

`admin/` 是 parent-ui 的构建产物，不入库。先构建面板，再编译 APK：

```bash
cd parent-ui && npm ci && npm run build
cd ../app/native && ./gradlew assembleTvDebug
```

构建产物 `admin/`、`app/native/app/src/main/assets/` 都由 Gradle 自动同步，不要提交。

回归测试（需要连接真机或模拟器，使用独立数据库，不影响家庭题库）：

```bash
adb install -r app/native/app/build/outputs/apk/tv/debug/*.apk
adb shell am instrument -w com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
```

前端检查：

```bash
cd parent-ui && npx tsc --noEmit && npm test
```

## PR 要求

- 在描述里关联 Issue（`closes #N`），并写清楚复现步骤和修复思路
- 说明测试设备与 Android 版本（例如「小米电视 Android 9，遥控器」）
- 不要在 PR 里提交 `admin/`、`app/native/app/src/main/assets/admin/`、`node_modules/` 或 `*.apk`
- 改了界面请附截图；改了管控逻辑请说明是否跑过 instrumentation 回归

## 代码风格

- Kotlin：不用第三方依赖，标准库 + Android SDK 即可；注释写「为什么」而不是「做了什么」
- React / TypeScript：组件拆到 `parent-ui/src/components/`，数据结构用 zod schema 校验
- 新增接口放在 `LanServer.registerRoutes()` 里登记，鉴权在进入路由前统一完成
- 数据库结构变更必须写 `onUpgrade` 迁移，并补一条 upgrade 回归用例

## 已知的设备兼容性坑（改代码前必读）

1. 海信 VIDAA（issue #66）：前台包名就是桌面包名，看片时检测不到前台应用 → 用播放状态兜底计时
2. TCL 电视关机（issue #57）：PowerManager 仍报 interactive → 同时检查系统广播和显示状态
3. 部分电视悬浮窗 0 尺寸（issue #110）：`addView` 成功但窗口不可见 → 按可见性判断是否重试，不能只看 `isShowing`
4. 遥控器待机不发开机广播（小米等）：BootReceiver 不可靠 → 结合 RevivalListener 和 Watchdog 双重保活
5. Android 5（API 21）：部分 `JobScheduler` 特性不可用，SQLite 没有 `JSON_EXTRACT`；数据库迁移不要依赖 JSON 函数
