package io.github.them3dium.mareshine;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.UUID;

// Syncing with another device in the same Wi-Fi, without a cloud or an
// account (Settings → OTHER → SYNC → Wi-Fi). While WifiSyncActivity shows,
// the device shares: it puts its own saves, save states and achievements into
// a sync file of its own (the "hub", in the app's storage), announces itself
// in the network (NSD, _mareshine._tcp) and shows a code. The other device
// picks it and enters the code, syncs with that hub exactly as with a sync
// file (Sync), and sends the result back; the sharing device then syncs its
// own data with the hub again. Both end up with the same.
//
// The connection is a plain TCP socket in the home network: a line
//   MARESHINE 1 <code> GET           -> OK <length> <device id>\n, the zip
//   MARESHINE 1 <code> PUT <length>  (the zip) -> OK\n
// A wrong code gets NO; after five wrong ones the code changes.
final class WifiSync {
    static final String SERVICE = "_mareshine._tcp.";
    private static final String MAGIC = "MARESHINE 1";
    private static final int MAX_ZIP = 1 << 30; // a sanity bound for a received length

    private WifiSync() {}

    static String deviceName() {
        String m = Build.MODEL;
        return m == null || m.isEmpty() ? "Android" : m;
    }

    // this device's own id (the other side's hashes are kept per id)
    static String deviceId(Context c) {
        SharedPreferences p = c.getSharedPreferences("sync", Context.MODE_PRIVATE);
        String id = p.getString("wifiId", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8);
            p.edit().putString("wifiId", id).apply();
        }
        return id;
    }

    static File hub(Context c) { return new File(c.getFilesDir(), "wifi-sync-hub.zip"); }

    // the address in the home network, for typing it in when the search finds nothing
    static String localAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // --- the sharing side ---------------------------------------------------------

    interface HostListener {
        void ready(String code, int port);
        // the other device sent its result and this device synced with it
        void synced(String message, boolean changed);
        void failed(String message);
    }

    static final class Host {
        private final Context c;
        private final HostListener l;
        private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        private ServerSocket server;
        private NsdManager nsd;
        private NsdManager.RegistrationListener reg;
        private volatile boolean running;
        private volatile String code;
        private int wrong;

        Host(Context c, HostListener l) {
            this.c = c.getApplicationContext();
            this.l = l;
        }

        String code() { return code; }

        void start() {
            running = true;
            new Thread(() -> {
                try {
                    // this device's state into the hub first
                    Sync.syncNow(c, new Sync.LocalFile(hub(c), "hub"));
                    server = new ServerSocket(0);
                    newCode();
                    final int port = server.getLocalPort();
                    ui.post(() -> {
                        announce(port);
                        l.ready(code, port);
                    });
                    while (running) {
                        Socket s;
                        try {
                            s = server.accept();
                        } catch (IOException e) {
                            break;
                        }
                        handle(s);
                    }
                } catch (Exception e) {
                    if (running) ui.post(() -> l.failed(String.valueOf(e.getMessage())));
                }
            }, "WifiSyncHost").start();
        }

        void stop() {
            running = false;
            try { if (server != null) server.close(); } catch (IOException ignored) {}
            if (nsd != null && reg != null) {
                try { nsd.unregisterService(reg); } catch (Exception ignored) {}
            }
            reg = null;
        }

        private void newCode() {
            code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            wrong = 0;
        }

        private void announce(int port) {
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName(deviceName());
            si.setServiceType(SERVICE);
            si.setPort(port);
            nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) {}
                @Override public void onRegistrationFailed(NsdServiceInfo i, int err) {}
                @Override public void onServiceUnregistered(NsdServiceInfo i) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) {}
            };
            try {
                nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, reg);
            } catch (Exception e) {
                reg = null; // found by address only
            }
        }

        private void handle(Socket s) {
            try (Socket sock = s) {
                sock.setSoTimeout(60000);
                InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = sock.getOutputStream();
                String[] w = readLine(in).split(" ");
                if (w.length < 4 || !(w[0] + " " + w[1]).equals(MAGIC)) return;
                if (!w[2].equals(code)) {
                    out.write("NO\n".getBytes("UTF-8"));
                    if (++wrong >= 5) {
                        newCode();
                        ui.post(() -> l.ready(code, server.getLocalPort()));
                    }
                    return;
                }
                File hub = hub(c);
                if (w[3].equals("GET")) {
                    long n = hub.isFile() ? hub.length() : 0;
                    out.write(("OK " + n + " " + deviceId(c) + "\n").getBytes("UTF-8"));
                    if (n > 0) try (InputStream f = new FileInputStream(hub)) { copy(f, out, n); }
                    out.flush();
                } else if (w[3].equals("PUT") && w.length >= 5) {
                    long n = Long.parseLong(w[4]);
                    if (n <= 0 || n > MAX_ZIP) return;
                    File tmp = new File(c.getCacheDir(), "wifi-sync-in.zip");
                    try (OutputStream f = new FileOutputStream(tmp)) { copy(in, f, n); }
                    if (!tmp.renameTo(hub)) { hub.delete(); tmp.renameTo(hub); }
                    out.write("OK\n".getBytes("UTF-8"));
                    out.flush();
                    // the other device's result: into this device's data too
                    String msg;
                    boolean changed;
                    try {
                        Sync.Result r = Sync.syncNow(c, new Sync.LocalFile(hub, "hub"));
                        msg = r.summary(c);
                        changed = r.changed();
                    } catch (Exception e) {
                        msg = L.t("Sync failed: %s", String.valueOf(e.getMessage()));
                        changed = true;
                    }
                    final String m = msg;
                    final boolean ch = changed;
                    ui.post(() -> l.synced(m, ch));
                }
            } catch (Exception ignored) {
            }
        }
    }

    // --- the other device -------------------------------------------------------

    // the sharing device as the other side of a sync (Sync.Remote)
    static final class Peer implements Sync.Remote {
        private final String host;
        private final int port;
        private final String code;
        private String id = "unknown";

        Peer(String host, int port, String code) {
            this.host = host;
            this.port = port;
            this.code = code;
        }

        // the sharing device's id, known after read(); the hashes are kept per device
        public String key() { return "wifi-" + id; }

        public boolean read(File dst) throws Exception {
            try (Socket s = connect()) {
                OutputStream out = s.getOutputStream();
                out.write((MAGIC + " " + code + " GET\n").getBytes("UTF-8"));
                out.flush();
                InputStream in = new BufferedInputStream(s.getInputStream());
                String[] w = answer(in);
                long n = Long.parseLong(w[1]);
                if (w.length > 2) id = w[2].replaceAll("[^A-Za-z0-9-]", "");
                if (n <= 0) return false;
                if (n > MAX_ZIP) throw new IOException("too large");
                try (OutputStream f = new FileOutputStream(dst)) { copy(in, f, n); }
                return true;
            }
        }

        public void write(File src) throws Exception {
            try (Socket s = connect()) {
                OutputStream out = s.getOutputStream();
                out.write((MAGIC + " " + code + " PUT " + src.length() + "\n").getBytes("UTF-8"));
                try (InputStream f = new FileInputStream(src)) { copy(f, out, src.length()); }
                out.flush();
                answer(new BufferedInputStream(s.getInputStream()));
            }
        }

        private Socket connect() throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(120000);
            return s;
        }

        private static String[] answer(InputStream in) throws IOException {
            String line = readLine(in);
            if (line.equals("NO")) throw new IOException(L.t("wrong code"));
            String[] w = line.split(" ");
            if (!w[0].equals("OK")) throw new IOException(L.t("the other device did not answer"));
            return w;
        }
    }

    // --- searching ------------------------------------------------------------------

    interface Found { void found(String name, String host, int port); void lost(String name); }

    // the devices sharing in the network; stop() when done
    static final class Search {
        private final NsdManager nsd;
        private final NsdManager.DiscoveryListener disc;
        private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        private boolean on;

        Search(Context c, String ownName, Found f) {
            nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
            disc = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onDiscoveryStopped(String t) {}
                @Override public void onStartDiscoveryFailed(String t, int e) {}
                @Override public void onStopDiscoveryFailed(String t, int e) {}
                @Override public void onServiceLost(NsdServiceInfo i) { ui.post(() -> f.lost(i.getServiceName())); }
                @Override public void onServiceFound(NsdServiceInfo i) {
                    if (i.getServiceName().equals(ownName)) return; // this device
                    try {
                        nsd.resolveService(i, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo x, int e) {}
                            @Override public void onServiceResolved(NsdServiceInfo x) {
                                if (x.getHost() == null) return;
                                String h = x.getHost().getHostAddress();
                                ui.post(() -> f.found(x.getServiceName(), h, x.getPort()));
                            }
                        });
                    } catch (Exception ignored) {}
                }
            };
        }

        void start() {
            try {
                nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, disc);
                on = true;
            } catch (Exception ignored) {}
        }

        void stop() {
            if (!on) return;
            on = false;
            try { nsd.stopServiceDiscovery(disc); } catch (Exception ignored) {}
        }
    }

    // --- bytes ------------------------------------------------------------------------

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n') {
            if (sb.length() > 200) throw new IOException("bad request");
            sb.append((char) ch);
        }
        return sb.toString().trim();
    }

    private static void copy(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("connection closed");
            out.write(buf, 0, r);
            left -= r;
        }
    }
}
