import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import org.pf4j.Extension;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** 模拟宿主读取 .spmod：清单、类加载、扩展索引。 */
public class TestPackage {
    public static void main(String[] args) throws Exception {
        Path spmod = Path.of(args[0]);
        Path work = Path.of(args[1]);
        if (Files.exists(work)) {
            try (var w = Files.walk(work)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                    try {
                        Files.deleteIfExists(x);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);

        // 1) 按宿主的方式解包 classes/
        try (JarFile zip = new JarFile(spmod.toFile())) {
            zip.stream().forEach(e -> {
                if (e.isDirectory()) return;
                String name = e.getName();
                if (!name.startsWith("classes/")) return;
                Path target = classes.resolve(name.substring("classes/".length()));
                try {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, target);
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
        System.out.println("[1] classes/ 解包完成: " + classes);

        // 2) 清单
        Path mf = classes.resolve("META-INF/MANIFEST.MF");
        Manifest m = new Manifest(Files.newInputStream(mf));
        Attributes a = m.getMainAttributes();
        String[] keys = {"Plugin-Class", "Plugin-Id", "Plugin-Version", "Plugin-Name",
                "Plugin-Permissions", "Plugin-Has-Config"};
        for (String k : keys) {
            String v = a.getValue(k);
            if (v == null) throw new AssertionError("缺少清单属性: " + k);
            System.out.println("    " + k + " = " + v);
        }
        String cls = a.getValue("Plugin-Class");
        String perm = a.getValue("Plugin-Permissions");
        if (!"key-bindings".equals(perm)) throw new AssertionError("权限声明异常: " + perm);

        // 3) 类加载
        URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                TestPackage.class.getClassLoader());
        Class<?> main = Class.forName(cls, true, loader);
        if (!SpwPlugin.class.isAssignableFrom(main)) throw new AssertionError("主类未继承 SpwPlugin");
        main.getConstructor(com.xuncorp.spw.workshop.api.PluginContext.class);
        System.out.println("[2] 主类加载成功: " + main.getName() + "（含 PluginContext 构造器）");

        // 4) 扩展索引 + 扩展类
        String idx;
        try (InputStream in = loader.getResourceAsStream("META-INF/extensions.idx")) {
            if (in == null) throw new AssertionError("缺少 extensions.idx");
            idx = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.println("[3] 扩展索引:\n" + idx.strip().indent(4));
        String extName = null;
        for (String line : idx.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            extName = line.strip();
        }
        Class<?> ext = Class.forName(extName, true, loader);
        if (!PlaybackExtensionPoint.class.isAssignableFrom(ext)) {
            throw new AssertionError("扩展类未实现 PlaybackExtensionPoint");
        }
        if (ext.getAnnotation(Extension.class) == null) throw new AssertionError("扩展类缺少 @Extension");
        ext.getDeclaredConstructor();
        System.out.println("[4] 扩展加载成功: " + ext.getName() + "（实现 PlaybackExtensionPoint）");

        System.out.println("\n打包校验全部通过");
    }
}
