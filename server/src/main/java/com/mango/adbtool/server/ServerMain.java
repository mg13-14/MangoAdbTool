package com.mango.adbtool.server;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
public class ServerMain {
    private static final String TAG = "MangoServer";
    public static final String SOCKET_NAME = "mango_adb_tool";
    public static final int VERSION = 1;
    public static final String ALLOWED_PACKAGE = "com.mango.adbtool";
    public static final String APP_SIGNATURE_SHA256 = "";
    public static void main(String[] args) {
        Log.i(TAG, "🥭 MangoServer 启动! pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
        final LocalServerSocket server;
        try { server = new LocalServerSocket(SOCKET_NAME); }
        catch (IOException e) { Log.e(TAG, "socket 创建失败", e); return; }
        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            try {
                LocalSocket client = server.accept();
                pool.execute(new ClientHandler(client));
            } catch (IOException e) { break; }
        }
    }
    private static class ClientHandler implements Runnable {
        private final LocalSocket socket;
        ClientHandler(LocalSocket socket) { this.socket = socket; }
        @Override
        public void run() {
            try {
                socket.setSoTimeout(15000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream();
                String hello = reader.readLine();
                if (hello == null || !"hello".equals(new JSONObject(hello).optString("action"))) return;
                int peerUid = -1;
                try { peerUid = socket.getPeerCredentials().getUid(); } catch (Throwable ignored) { }
                if (!checkUid(peerUid)) {
                    respond(out, new JSONObject().put("code", 403).put("msg", "身份校验未通过 🍑"));
                    return;
                }
                respond(out, new JSONObject().put("code", 0).put("version", VERSION).put("uid", android.os.Process.myUid()));
                socket.setSoTimeout(0);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject req = new JSONObject(line);
                    boolean stop = "stop".equals(req.optString("action"));
                    respond(out, handle(req));
                    if (stop) {
                        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "客户端断开: " + t);
            } finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
        private JSONObject handle(JSONObject req) {
            try {
                String action = req.optString("action");
                if ("ping".equals(action))  return ok().put("data", "pong 🥭");
                if ("exec".equals(action))  return exec(req.getString("cmd"), req.optLong("timeout", 60000L));
                if ("read".equals(action))  return readFile(req.getString("path"));
                if ("write".equals(action)) return writeFile(req.getString("path"), req.getString("data"));
                return new JSONObject().put("code", 400).put("msg", "未知操作: " + action);
            } catch (Throwable t) {
                try { return new JSONObject().put("code", -1).put("msg", "服务端出错: " + t); }
                catch (org.json.JSONException ignored) { return new JSONObject(); }
            }
        }
        private JSONObject exec(String cmd, long timeoutMs) throws Exception {
            final Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = p.getInputStream().read(buf)) > 0) buffer.write(buf, 0, n);
                } catch (IOException ignored) { }
            });
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new JSONObject().put("code", 124).put("msg", "命令超时");
            }
            reader.join(3000);
            return ok().put("exit", p.exitValue()).put("data", buffer.toString("UTF-8"));
        }
        private JSONObject readFile(String path) throws Exception {
            File f = new File(path);
            if (!f.exists() || !allowed(path)) return new JSONObject().put("code", 404).put("msg", "文件不存在或路径不允许");
            return ok().put("data", Base64.encodeToString(readAll(f), Base64.NO_WRAP));
        }
        private JSONObject writeFile(String path, String b64) throws Exception {
            if (!allowed(path)) return new JSONObject().put("code", 403).put("msg", "路径不允许写入");
            File f = new File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(Base64.decode(b64, Base64.NO_WRAP)); }
            return ok();
        }
        private boolean allowed(String p) {
            return p.startsWith("/data/local/tmp/mango") || p.startsWith("/sdcard/") || p.startsWith("/storage/");
        }
        private static byte[] readAll(File f) throws IOException {
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        }
        private boolean checkUid(int uid) {
            try {
                if (uid < 10000) return false;
                Context ctx = systemContext();
                PackageManager pm = ctx.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs == null) return false;
                boolean has = false;
                for (String p : pkgs) if (ALLOWED_PACKAGE.equals(p)) has = true;
                if (!has) return false;
                if (APP_SIGNATURE_SHA256 != null && !APP_SIGNATURE_SHA256.isEmpty()) {
                    PackageInfo info = pm.getPackageInfo(ALLOWED_PACKAGE, PackageManager.GET_SIGNATURES);
                    if (info.signatures == null || info.signatures.length == 0) return false;
                    byte[] sha = MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray());
                    StringBuilder sb = new StringBuilder();
                    for (byte b : sha) sb.append(String.format("%02x", b));
                    if (!APP_SIGNATURE_SHA256.equalsIgnoreCase(sb.toString())) return false;
                }
                return true;
            } catch (Throwable t) { return false; }
        }
        private Context systemContext() throws Exception {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        }
        private static JSONObject ok() {
            try { return new JSONObject().put("code", 0); }
            catch (org.json.JSONException ignored) { return new JSONObject(); }
        }
        private static void respond(OutputStream out, JSONObject json) throws IOException {
            out.write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
