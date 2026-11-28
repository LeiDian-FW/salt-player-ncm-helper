#!/usr/bin/env python3
"""构建 Salt Player 插件包 .spmod（不依赖 Gradle）。

依赖：
  - JDK 25（可用 JAVA_HOME，或本目录 jdk25/，或 PATH 中的 javac）
  - libs/*.jar（运行 python get_deps.py 自动下载）

用法： python build.py
产物： build/libs/plugin-<PluginId>-<Version>.spmod
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
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(BASE, ".tools", "kotlin-2.4.0")
KOTLIN_SRC = os.path.join(BASE, "src", "main", "kotlin")
API_JAR = os.path.join(BASE, "libs", "api.jar")
PF4J_JAR = os.path.join(BASE, "libs", "pf4j.jar")
KOTLIN_STDLIB = os.path.join(BASE, "libs", "kotlin-stdlib.jar")
ZXING_JAR = os.path.join(BASE, "libs", "zxing-core.jar")
SRC = os.path.join(BASE, "src", "main", "java")
RES = os.path.join(BASE, "src", "main", "resources")
BUILD = os.path.join(BASE, "build")
CLASSES = os.path.join(BUILD, "classes")
INTER = os.path.join(BUILD, "intermediates")
LIBS_OUT = os.path.join(BUILD, "libs")

PLUGIN_ID = "io.github.spwncm.ncmhelper"
PLUGIN_CLASS = "io.github.spwncm.ncmhelper.MainPlugin"
PLUGIN_VERSION = "1.6.3"
PLUGIN_NAME = "NCM 助手"
PLUGIN_DESC = "自动解密网易云 NCM、按歌手/专辑归档、内嵌封面并在线补全歌词"
PLUGIN_PROVIDER = "spwncm"
PLUGIN_PERMISSIONS = "key-bindings,class-transform"
MIN_JDK = 25


def find_host_package():
    """宿主 classpath 归档（jpackage cfg 里的 app.classpath，即 ffmpeg-x64.dll）。"""
    env = os.environ.get("SALT_HOST_PACKAGE")
    if env and os.path.isfile(env):
        return env
    home = os.environ.get("SALT_HOME") or r"D:\SteamLibrary\steamapps\common\Salt Player for Windows"
    cand = os.path.join(home, "app", "ffmpeg-x64.dll")
    return cand if os.path.isfile(cand) else None


def compile_kotlin(javac_java):
    """编译原生 Compose UI（需要 .tools 下的 Kotlin 2.4.0 工具链与宿主归档）。"""
    kt_files = []
    for dp, _dn, fn in os.walk(KOTLIN_SRC):
        for f in fn:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(dp, f))
    if not kt_files:
        return
    toolchain = [
        "kotlin-compiler-embeddable-2.4.0.jar",
        "kotlin-build-tools-api-2.4.0.jar",
        "kotlin-stdlib-2.4.0.jar",
        "kotlin-script-runtime-2.4.0.jar",
        "kotlin-reflect-1.6.10.jar",
        "kotlin-daemon-embeddable-2.4.0.jar",
        "kotlinx-coroutines-core-jvm-1.8.0.jar",
        "annotations-13.0.jar",
    ]
    missing = [f for f in toolchain if not os.path.isfile(os.path.join(TOOLS, f))]
    if missing:
        sys.exit("缺少原生 UI 工具链：先运行 python scripts/get_native_toolchain.py\n缺少: %s" % ", ".join(missing))
    host = find_host_package()
    if not host:
        # CI/无游戏环境：跳过原生 UI（本地发布包需要宿主归档才能编译原生界面）
        print("跳过原生 UI 编译：未找到宿主归档（设置 SALT_HOST_PACKAGE 或 SALT_HOME）")
        return
    compile_only = [
        os.path.join(TOOLS, "foundation-desktop-1.10.3.jar"),
        os.path.join(TOOLS, "runtime-desktop-1.10.5.jar"),
    ]
    compile_cp = os.pathsep.join(compile_only + [CLASSES, API_JAR, KOTLIN_STDLIB, host])
    plugin_jar = os.path.join(TOOLS, "kotlin-compose-compiler-plugin-embeddable-2.4.0.jar")
    cmd = [javac_java, "-Dfile.encoding=UTF-8", "-classpath",
           os.pathsep.join(os.path.join(TOOLS, f) for f in toolchain),
           "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
           "-no-stdlib", "-no-reflect", "-jvm-target", "25", "-module-name", "ncm-native-ui",
           "-classpath", compile_cp, "-Xplugin=" + plugin_jar, "-d", CLASSES] + kt_files
    print("编译原生 Compose UI（%d 个 .kt）…" % len(kt_files))
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        out = (r.stdout or "") + (r.stderr or "")
        print(out[-4000:])
        sys.exit("原生 UI 编译失败")
    print("  原生 UI 编译完成")


def find_tool(name):
    exe = name + (".exe" if os.name == "nt" else "")
    local = os.path.join(BASE, "jdk25", "bin", exe)
    if os.path.isfile(local):
        return local
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = os.path.join(java_home, "bin", exe)
        if os.path.isfile(cand):
            return cand
    found = shutil.which(name)
    if found:
        return found
    sys.exit("找不到 %s：请安装 JDK %d+ 并设置 JAVA_HOME" % (name, MIN_JDK))


def check_jdk(javac):
    r = subprocess.run([javac, "-version"], capture_output=True, text=True, errors="replace")
    version = (r.stdout or "") + (r.stderr or "")
    try:
        major = int(version.split("javac ")[1].split(".")[0].split("-")[0])
    except Exception:
        major = 0
    if major < MIN_JDK:
        sys.exit("需要 JDK %d+，当前: %s" % (MIN_JDK, version.strip()))
    return version.strip()


def ensure_deps():
    missing = [p for p in (API_JAR, PF4J_JAR, KOTLIN_STDLIB, ZXING_JAR) if not os.path.isfile(p)]
    if missing:
        print("缺少依赖，自动运行 get_deps.py …")
        subprocess.run([sys.executable, os.path.join(BASE, "get_deps.py")], check=True)


def collect_sources():
    out = []
    for dp, dn, fn in os.walk(SRC):
        for f in fn:
            if f.endswith(".java"):
                out.append(os.path.join(dp, f))
    return out


def write_manifest(path):
    attrs = [
        ("Manifest-Version", "1.0"),
        ("Plugin-Class", PLUGIN_CLASS),
        ("Plugin-Id", PLUGIN_ID),
        ("Plugin-Version", PLUGIN_VERSION),
        ("Plugin-Provider", PLUGIN_PROVIDER),
        ("Plugin-Name", PLUGIN_NAME),
        ("Plugin-Description", PLUGIN_DESC),
        ("Plugin-Has-Config", "true"),
        ("Plugin-Permissions", PLUGIN_PERMISSIONS),
    ]
    lines = []
    for k, v in attrs:
        text = "%s: %s" % (k, v)
        first = True
        cur = b""
        limit = 72
        for ch in text:
            b = ch.encode("utf-8")
            if len(cur) + len(b) > limit:
                lines.append(cur if first else b" " + cur)
                first = False
                limit = 71
                cur = b""
            cur += b
        lines.append(cur if first else b" " + cur)
    with open(path, "wb") as f:
        for line in lines:
            f.write(line + b"\r\n")
        f.write(b"\r\n")


def main():
    ensure_deps()
    javac = find_tool("javac")
    print("JDK:", check_jdk(javac))

    for d in (CLASSES, INTER, LIBS_OUT):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d, exist_ok=True)

    cp = os.pathsep.join([API_JAR, PF4J_JAR, KOTLIN_STDLIB, ZXING_JAR])
    sources = collect_sources()
    print("编译 %d 个 Java 文件…" % len(sources))
    r = subprocess.run([javac, "-encoding", "UTF-8", "-proc:full",
                        "-processorpath", PF4J_JAR,
                        "-classpath", cp, "-d", CLASSES] + sources,
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        sys.exit(r.returncode)

    compile_kotlin(find_tool("java"))

    for dp, dn, fn in os.walk(RES):
        for f in fn:
            src = os.path.join(dp, f)
            rel = os.path.relpath(src, RES)
            dst = os.path.join(CLASSES, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)

    idx = [os.path.join(dp, f) for dp, dn, fn in os.walk(CLASSES)
           for f in fn if f == "extensions.idx"]
    print("扩展索引:", [os.path.relpath(p, CLASSES) for p in idx] or "未生成")

    manifest = os.path.join(INTER, "MANIFEST.MF")
    write_manifest(manifest)

    plugin_jar = os.path.join(INTER, "plugin.jar")
    with zipfile.ZipFile(plugin_jar, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(manifest, "META-INF/MANIFEST.MF")
        for dp, dn, fn in os.walk(CLASSES):
            for f in fn:
                src = os.path.join(dp, f)
                rel = os.path.relpath(src, CLASSES).replace(os.sep, "/")
                if rel.upper() == "META-INF/MANIFEST.MF":
                    continue
                z.write(src, rel)

    spmod = os.path.join(LIBS_OUT, "plugin-%s-%s.spmod" % (PLUGIN_ID, PLUGIN_VERSION))
    with zipfile.ZipFile(spmod, "w", zipfile.ZIP_DEFLATED) as z:
        with zipfile.ZipFile(plugin_jar) as inner:
            for name in inner.namelist():
                if name.endswith("/"):
                    continue
                z.writestr("classes/" + name, inner.read(name))
        # lib/ = 运行时依赖（宿主通过插件类加载器加载）
        z.writestr("lib/zxing-core.jar", open(ZXING_JAR, "rb").read())

    print("\n构建完成: %s (%.1f KB)" % (spmod, os.path.getsize(spmod) / 1024))
    print("安装: Salt Player → 创意工坊 → 模组管理 → 导入模组 → 选择该文件")


if __name__ == "__main__":
    main()
