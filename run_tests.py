#!/usr/bin/env python3
"""NCM 助手自测：核心解密、歌词接口、完整流水线、打包校验。

测试完全自包含：用 test/tools/build_test_ncm.py 合成 NCM / FLAC / JPEG，
不依赖任何外部音乐文件。JVM 参数模拟 Salt Player 宿主约束
（512MB 堆 + 只保留 java.base/java.desktop 模块）。

用法：
  python run_tests.py             # 全量（含在线歌词接口）
  python run_tests.py --no-network  # 跳过联网测试（CI 用）
"""
import os
import shutil
import subprocess
import sys
try:  # 某些 Windows 环境默认 cp1252，无法输出中文
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = os.path.dirname(os.path.abspath(__file__))
BUILD_DIR = os.path.join(BASE, "build")
CLASSES = os.path.join(BUILD_DIR, "classes")
TEST_DIR = os.path.join(BASE, "test")
SAMPLES = os.path.join(TEST_DIR, "samples")
TEST_OUT = os.path.join(TEST_DIR, "out")
WORK = os.path.join(TEST_DIR, "run")

HOST_JVM = ["-Xmx512M", "-XX:SoftMaxHeapSize=128M", "-Xss500K",
            "--limit-modules=java.base,java.desktop"]
ENV = dict(os.environ, PYTHONIOENCODING="utf-8",
           JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8")


def find_java_tool(name):
    exe = name + (".exe" if os.name == "nt" else "")
    local = os.path.join(BASE, "jdk25", "bin", exe)
    if os.path.isfile(local):
        return local
    jh = os.environ.get("JAVA_HOME")
    if jh:
        cand = os.path.join(jh, "bin", exe)
        if os.path.isfile(cand):
            return cand
    found = shutil.which(name)
    if not found:
        sys.exit("找不到 %s：请安装 JDK 25+ 并设置 JAVA_HOME" % name)
    return found


def run(cmd):
    print("$", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=ENV)
    out = (r.stdout or "") + (r.stderr or "")
    print(out)
    if r.returncode != 0:
        raise SystemExit("失败: " + " ".join(cmd))
    return out


def ensure_deps():
    if not os.path.isfile(os.path.join(BASE, "libs", "api.jar")):
        run([sys.executable, os.path.join(BASE, "get_deps.py")])


def ensure_build():
    if not os.path.isfile(os.path.join(CLASSES, "io/github/spwncm/ncmhelper/MainPlugin.class")):
        run([sys.executable, os.path.join(BASE, "build.py")])


def ensure_samples():
    if not os.path.isfile(os.path.join(SAMPLES, "sample.ncm")):
        run([sys.executable, os.path.join(TEST_DIR, "tools", "build_test_ncm.py"), SAMPLES])


def find_spmod():
    libdir = os.path.join(BUILD_DIR, "libs")
    if not os.path.isdir(libdir):
        return ""
    cands = [os.path.join(libdir, f) for f in os.listdir(libdir) if f.endswith(".spmod")]
    return max(cands, key=os.path.getmtime) if cands else ""


def main():
    no_network = "--no-network" in sys.argv
    javac = find_java_tool("javac")
    java = find_java_tool("java")

    ensure_deps()
    ensure_build()
    ensure_samples()

    libs = os.path.join(BASE, "libs")
    cp = os.pathsep.join([
        CLASSES,
        os.path.join(libs, "api.jar"),
        os.path.join(libs, "pf4j.jar"),
        os.path.join(libs, "kotlin-stdlib.jar"),
    ])
    shutil.rmtree(TEST_OUT, ignore_errors=True)
    os.makedirs(TEST_OUT, exist_ok=True)

    java_sources = [os.path.join(TEST_DIR, "java", f)
                    for f in os.listdir(os.path.join(TEST_DIR, "java")) if f.endswith(".java")]
    run([javac, "-encoding", "UTF-8", "-cp", cp, "-d", TEST_OUT] + java_sources)

    ncm = os.path.join(SAMPLES, "sample.ncm")
    flac = os.path.join(SAMPLES, "sample.flac")

    run([java] + HOST_JVM + ["-cp", cp + os.pathsep + TEST_OUT, "TestCore", ncm, flac])
    if no_network:
        print("跳过联网歌词测试（--no-network）")
    else:
        run([java] + HOST_JVM + ["-cp", cp + os.pathsep + TEST_OUT, "TestLyrics"])
    run([java] + HOST_JVM + ["-cp", cp + os.pathsep + TEST_OUT, "TestPipeline", WORK, ncm])
    spmod = find_spmod()
    if not spmod:
        raise SystemExit("未找到构建产物 .spmod")
    run([java] + HOST_JVM + ["-cp", cp + os.pathsep + TEST_OUT, "TestPackage", spmod,
                             os.path.join(TEST_DIR, "pkgcheck")])

    print("\n===== 全部测试通过 =====")


if __name__ == "__main__":
    main()
