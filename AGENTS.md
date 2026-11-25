# AGENTS.md

小米背屏 Agent 状态项目：免 Gradle 构建，靠 Shizuku 拿 shell 权限，把 PC 上的 agent 状态显示到
Xiaomi 的背屏（`display 1`，976×596）。架构、协议和逐条踩坑记录见 `README.md`。

## 构建

```bash
bash build.sh                                   # 默认 VERSION_CODE
VERSION_CODE=24 VERSION_NAME=2.8 bash build.sh  # 递增版本
```

改动 `RearMover` 或任何跨进程 parcel 后**必须**递增 `VERSION_CODE`：Shizuku 按
`UserServiceArgs.version()` 复用旧 helper 进程，版本不变就静默继续跑老代码。

## 测试

```bash
python tests/test_statusd.py                      # daemon 端到端（13 项）
javac -encoding UTF-8 -cp .toolchain/dl/json-20250107.jar -d tests/out \
  app/src/com/dsh/rearscreen/data/AgentState.java tests/ParserTest.java
java -cp ".toolchain/dl/json-20250107.jar;tests/out" ParserTest   # 解析逻辑（8 项）
```

两条都应输出 `ALL PASS`。

## 硬规则

- 放置面板只能「先在主屏 `am start`，再 `am display move-stack`」。`am start --display 1`
  会被 HyperOS 背屏白名单拒绝（`result code=102`）。
- 面板绝不能留在 `display 0`。判断以真机实际画面为准。
- 抓屏必须显式指定 display：`screencap -d <displayId>`。不带 `-d` 时多屏下会随机选一个，
  很容易把背屏画面当成主屏。
- `dumpsys activity activities` 里 **Display #1 段在前、Display #0 段在后**，按顺序切段
  容易把两段算混；要按行归属（`^\s*Display #?(\d+)`）逐条统计。

## 代码审查重点

- 任何可能落到非预期 display 的 Activity，必须用透明 `windowBackground`
  （见 `app/res/values/styles.xml` 的 `Theme.Rear.Panel`）；运行时的 `alpha` /
  `FLAG_NOT_TOUCHABLE` 盖不住系统绘制的 starting window，改不透明黑会让面板压在
  主屏顶部并吃掉全部触摸。（来源：面板压在主屏、整块屏幕点不动的修复）
