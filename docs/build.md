# 构建与测试（Windows）

需要 Git Bash、JDK 17、[Android SDK](https://developer.android.com/studio)，测试另需 Python 3.10+。

1. 在 SDK Manager 安装 Android Platform 36 与 Build-Tools 36.0.0。复制平台目录到 `.toolchain/sdk/platforms/android-36/`，把 Build-Tools 36.0.0 的内容放到 `.toolchain/sdk/build-tools/android-16/`（脚本约定的目录名）。
2. 在 [Maven Central](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/) 下载 api、provider、aidl、shared 的 13.1.5 AAR。将每个 AAR 内的 `classes.jar` 分别解包到 `.toolchain/sdk/aar/<模块名>/classes.jar`。
3. 用 Git Bash 运行 `bash build.sh`，输出 `build/source/app-aligned.apk`（未签名）。自行测试时使用你自己的密钥，参考 [apksigner](https://developer.android.com/tools/apksigner)。不同签名通常不能覆盖同包名已安装版本。

## 测试

把 [JSON-java 20250107 JAR](https://repo.maven.apache.org/maven2/org/json/json/20250107/json-20250107.jar) 放到 `.toolchain/dl/` 后，在 Git Bash 运行：

~~~bash
python tests/test_statusd.py
javac -encoding UTF-8 -cp .toolchain/dl/json-20250107.jar -d tests/out app/src/com/dsh/rearscreen/data/AgentState.java tests/ParserTest.java
java -cp "tests/out;.toolchain/dl/json-20250107.jar" ParserTest
~~~

两套测试应输出 ALL PASS。修改 RearMover 或跨进程协议时，递增 VERSION_CODE，避免 Shizuku 复用旧进程。
