package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A mod's native plugin (src/port_include/mareshine_plugin.h): the mod keeps it
 * as plugin/&lt;abi&gt;/lib&lt;name&gt;.so in its folder. Shared storage is
 * mounted without execute rights, so the game loads a copy in the app's own
 * storage (platform/mods/plugins.cpp, through SMS_PLUGIN).
 */
final class ModPlugin {
    private ModPlugin() {}

    /** The mod's plugin file for this device, or null. */
    static File find(Mods.Mod mod) {
        if (mod == null || mod.dir == null) return null;
        for (String abi : Build.SUPPORTED_ABIS) {
            File[] libs = new File(mod.dir, "plugin/" + abi).listFiles(
                (d, name) -> name.startsWith("lib") && name.endsWith(".so"));
            if (libs != null && libs.length > 0) return libs[0];
        }
        return null;
    }

    /** The copy the game loads (made or refreshed now), or null when the mod has no plugin. */
    static String prepare(Context ctx, Mods.Mod mod) {
        File src = find(mod);
        if (src == null) return null;
        File dir = new File(ctx.getFilesDir(), "plugins/" + mod.id);
        File dst = new File(dir, src.getName());
        if (dst.isFile() && dst.length() == src.length() && dst.lastModified() == src.lastModified())
            return dst.getAbsolutePath();
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File tmp = new File(dir, src.getName() + ".tmp");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException e) {
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            return null;
        }
        dst.setLastModified(src.lastModified());
        return dst.getAbsolutePath();
    }
}
