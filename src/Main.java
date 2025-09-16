import java.io.*;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.nio.channels.FileChannel;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

public class Main {

    public static void main(String[] args) {
        String cfgPath = (args != null && args.length > 0) ? args[0] : "config.txt";

        try {
            Map<String, Properties> sections = ConfigLoader.load(cfgPath);

            String hostname = getHostnameFromSystem();
            if (hostname == null || hostname.length() == 0) hostname = "UNKNOWN";

            Properties merged = new Properties();
            Properties global = sections.get("Global");
            if (global != null) merged.putAll(global);
            Properties machine = sections.get(hostname);
            if (machine != null) merged.putAll(machine);
            else LoggerUtil.error("警告：設定檔中沒有 [" + hostname + "] 段落，僅使用 [Global]。");

            AppConfig cfg = new AppConfig(hostname, merged);

            // === 初始化 Logger（新增） ===
            String logDir   = merged.getProperty("log.dir", "logs");
            String logLevel = merged.getProperty("log.level", "INFO");
            LoggerUtil.init(logDir, logLevel);
            LoggerUtil.info("程式啟動，hostname=" + hostname);


            // === 佔位符解析（目前用在 collect.baseDir；若要也可套用到 watch.dirs） ===
            Date now = new Date();
            String tsHour = new SimpleDateFormat("yyyyMMddHH").format(now);
            String resolvedCollectBase = resolvePlaceholders(cfg.collectBaseDir, hostname, tsHour);

            // === 批次子資料夾（以 ZIP 名稱去掉 .zip 當批次名） ===
            String zipBase = resolvePlaceholders(cfg.zipName, hostname, tsHour);
            if (!zipBase.toLowerCase(Locale.ENGLISH).endsWith(".zip")) zipBase += ".zip";
            String batchFolderName = zipBase.substring(0, zipBase.length() - 4);

            File collectBatchDir = (resolvedCollectBase != null && resolvedCollectBase.length() > 0)
                    ? new File(resolvedCollectBase, batchFolderName)
                    : null;
            if (collectBatchDir != null) ensureDir(collectBatchDir);

            // === 先決定「要掃哪些目標資料夾」：支援 expand 子資料夾規則 ===
            List<File> targetRoots = resolveTargetDirs(cfg);
            if (targetRoots.isEmpty()) {
                LoggerUtil.info("沒有可掃描的目標資料夾（請確認 watch.dirs 或 expand 規則）。");
                return;
            }

            // 編譯 include/exclude 規則
            List<Pattern> includes = compileGlobList(cfg.includePatterns);
            List<Pattern> excludes = compileGlobList(cfg.excludePatterns);
            System.out.println("includes: " + includes);
            System.out.println("excludes: " + excludes);

            // 準備一對一備份對應（若沒提供 backup.dirs 則使用 backup.baseDir 作為通用備份）
            boolean hasOneToOne = (cfg.backupDirs != null && !cfg.backupDirs.isEmpty()
                    && cfg.backupDirs.size() == cfg.watchDirs.size());
            List<File> backupRoots = new ArrayList<File>();
            if (hasOneToOne) {
                for (int i = 0; i < cfg.backupDirs.size(); i++) {
                    String b = resolvePlaceholders(cfg.backupDirs.get(i), hostname, tsHour);
                    File bf = (b == null || b.length() == 0) ? null : new File(b);
                    if (bf != null) ensureDir(bf);
                    backupRoots.add(bf);
                }
            } else if (cfg.backupBaseDir != null && cfg.backupBaseDir.trim().length() > 0) {
                String b = resolvePlaceholders(cfg.backupBaseDir, hostname, tsHour);
                File bf = new File(b);
                ensureDir(bf);
                // 若非一對一，全部來源都用這個備份根
                for (int i = 0; i < cfg.watchDirs.size(); i++) backupRoots.add(bf);
            } else {
                // 沒有任何備份根，允許只收集不備份（會警告）
                for (int i = 0; i < cfg.watchDirs.size(); i++) backupRoots.add(null);
                LoggerUtil.error("警告：未設定 backup.dirs 或 backup.baseDir，將不進行備份複製。");
            }

            // === 掃描＋搬移 ===
            int totalMoved = 0;
            // 這裡需保留「來源根與備份根的一對一關係」：用 watchDirs 的順序對應
            for (int i = 0; i < cfg.watchDirs.size(); i++) {
                String srcRootStr = resolvePlaceholders(cfg.watchDirs.get(i), hostname, tsHour);
                File srcRoot = new File(srcRootStr);

                // 若使用 expand 模式，targetRoots 可能是從多個 srcRoot 之下挑出；我們只處理與此 srcRoot 相關的目標
                List<File> targetsForThisRoot = filterTargetsUnder(srcRoot, targetRoots);
                if (targetsForThisRoot.isEmpty()) {
                    // 若沒有 expand 或無命中，就把 srcRoot 自身當作目標（傳統行為）
                    if (matchesAnyTargetRoot(srcRoot, cfg)) {
                        targetsForThisRoot.add(srcRoot);
                    }
                }

                File backupRoot = (i < backupRoots.size()) ? backupRoots.get(i) : null;

                for (int t = 0; t < targetsForThisRoot.size(); t++) {
                    File targetRoot = targetsForThisRoot.get(t);
                    List<File> candidates = listFilesWithDepth(targetRoot, cfg.watchFilesMaxDepth);

                    for (int j = 0; j < candidates.size(); j++) {
                        File f = candidates.get(j);
                        if (!f.isFile()) continue;

                        String name = f.getName();
                        if (!matchAny(name, includes)) continue;
                        if (matchAny(name, excludes)) continue;

                        if (!isStable(f, cfg.stableMillis)) {
                            LoggerUtil.info("跳過（未達穩定時間）：" + f.getAbsolutePath());
                            continue;
                        }

                        // 以「目標根 targetRoot」計算相對路徑，避免不同子資料夾同名衝突
                        String base = targetRoot.getCanonicalPath();
                        String abs = f.getCanonicalPath();
                        String rel = abs.substring(base.length());
                        if (rel.startsWith(File.separator)) rel = rel.substring(1);

                        // 決定 collect 與 backup 的目標路徑
                        File collectTarget = (collectBatchDir != null) ? new File(collectBatchDir, rel) : null;
                        File backupTarget = (backupRoot != null) ? new File(backupRoot, rel) : null;

                        try {
                            if (collectTarget != null) copyFile(f, collectTarget);
                            if (backupTarget != null)  copyFile(f, backupTarget);

                            // 來源刪除
                            if (!f.delete()) LoggerUtil.error("刪除來源失敗：" + f.getAbsolutePath());

                            totalMoved++;
                            LoggerUtil.info("搬運完成：" + f.getAbsolutePath()
                                    + (collectTarget != null ? (" → [collect] " + collectTarget.getAbsolutePath()) : "")
                                    + (backupTarget  != null ? (" | [backup] " + backupTarget.getAbsolutePath())  : "")
                            );

                        } catch (Exception ex) {
                            LoggerUtil.error("搬運失敗：" + f.getAbsolutePath() + " - " + ex.getMessage());
                            if (collectTarget != null) deleteQuietly(collectTarget);
                            if (backupTarget  != null) deleteQuietly(backupTarget);
                        }
                    }
                }
            }

            if (totalMoved == 0) {
                LoggerUtil.info("本次無可搬運檔案，結束。");
                return;
            }

            LoggerUtil.info("掃描與搬運完成。共搬運檔案數：" + totalMoved);

            // === ZIP 壓縮 ===
            if (collectBatchDir != null && totalMoved > 0) {
                File zipDest = new File(collectBatchDir.getParentFile(), zipBase); // zipBase 前面已算好
                try {
                    zipDirectory(collectBatchDir, zipDest);
                    LoggerUtil.info("ZIP 產出完成：" + zipDest.getAbsolutePath());

                    // （可選）若要節省空間，壓縮成功後移除收集資料夾：
                    // deleteQuietly(collectBatchDir);
                    // === FTP 上傳 ===
                    try {
                        boolean ok = ftpUploadWithRetry(zipDest, cfg, 3, 3000);
                        if (ok) {
                            LoggerUtil.info("FTP 上傳成功：" + zipDest.getName());

                            // （可選）上傳成功後清理收集資料夾與 ZIP，視你的保留策略而定
                            // deleteQuietly(collectBatchDir);
                            // if (!zipDest.delete()) LoggerUtil.info("刪除 ZIP 失敗：" + zipDest.getAbsolutePath());
                        } else {
                            LoggerUtil.info("FTP 上傳失敗（多次重試仍失敗）。");
                        }
                    } catch (Exception ex) {
                        LoggerUtil.error("FTP 上傳例外：" + ex.getMessage());
                        ex.printStackTrace();
                    }

                } catch (Exception zex) {
                    LoggerUtil.error("ZIP 失敗：" + zex.getMessage());
                    zex.printStackTrace();
                    // 視需求決定是否在 ZIP 失敗時中止或繼續（此處先不退出）
                }
            } else {
                LoggerUtil.info("略過 ZIP（collect 目錄不存在或無搬運檔案）。");
            }

        } catch (Exception e) {
            LoggerUtil.error("執行失敗：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ================= AppConfig =================
    static class AppConfig {
        final String hostname;
        final String ftpHost;
        final int    ftpPort;
        final String ftpUser;
        final String ftpPass;
        final String ftpRemoteDir;
        final boolean ftpPassive;
        final int ftpConnectTimeoutMs;
        final int ftpDataTimeoutMs;

        final String zipName;
        final long   stableMillis;

        final List<String> watchDirs;
        final List<String> backupDirs;
        final List<String> includePatterns;
        final List<String> excludePatterns;

        final boolean watchRecursive;
        final int     watchFilesMaxDepth;
        final boolean watchExpandEnabled;
        final String  watchExpandSubdirRegex;
        final int     watchExpandDepth;

        final String collectBaseDir;
        final String backupBaseDir;

        AppConfig(String hostname, Properties p) {
            this.hostname = hostname;
            ftpHost = p.getProperty("ftp.host");
            ftpPort = parseInt(p.getProperty("ftp.port"), 21);
            ftpUser = p.getProperty("ftp.user");
            ftpPass = p.getProperty("ftp.pass");
            ftpRemoteDir = p.getProperty("ftp.remoteDir");
            ftpPassive = parseBool(p.getProperty("ftp.passive"), true);
            ftpConnectTimeoutMs = parseInt(p.getProperty("ftp.connect.timeout.ms"), 30000);
            ftpDataTimeoutMs    = parseInt(p.getProperty("ftp.data.timeout.ms"), 30000);

            zipName = defaultIfBlank(p.getProperty("zip.name"), "{hostname}_{yyyyMMddHH}.zip");
            stableMillis = parseLong(p.getProperty("stable.millis"), 120000L);

            watchDirs       = splitCsv(p.getProperty("watch.dirs"));
            backupDirs      = splitCsv(p.getProperty("backup.dirs"));
            includePatterns = splitCsv(firstNonBlank(p.getProperty("include.patterns"),
                                                     p.getProperty("include.pattern"),
                                                     p.getProperty("include")));
            excludePatterns = splitCsv(p.getProperty("exclude.patterns"));

            watchRecursive         = parseBool(p.getProperty("watch.recursive"), false);
            watchFilesMaxDepth     = parseInt(p.getProperty("watch.files.maxDepth"),
                                              watchRecursive ? -1 : 0);
            watchExpandEnabled     = parseBool(p.getProperty("watch.expand.enabled"), false);
            watchExpandSubdirRegex = p.getProperty("watch.expand.subdir.regex");
            watchExpandDepth       = parseInt(p.getProperty("watch.expand.depth"), 1);

            collectBaseDir = p.getProperty("collect.baseDir");
            backupBaseDir  = p.getProperty("backup.baseDir");

            if (watchDirs.isEmpty()) throw new IllegalArgumentException("watch.dirs 未設定或為空。");
            if (!backupDirs.isEmpty() && backupDirs.size() != watchDirs.size()) {
                LoggerUtil.error("警告：backup.dirs 與 watch.dirs 數量不一致（一對一對應可能無法成立）。");
            }
        }

        void printSummary() {
            log("=== 設定摘要 ===");
            log("hostname=" + hostname);
            log("watch.dirs=" + watchDirs);
            log("backup.dirs=" + backupDirs + " (若空則可能使用 backup.baseDir=" + backupBaseDir + ")");
            log("include.patterns=" + includePatterns);
            log("exclude.patterns=" + (excludePatterns.isEmpty() ? "[]" : excludePatterns.toString()));
            log("stable.millis=" + stableMillis);
            log("collect.baseDir=" + collectBaseDir);
            log("backup.baseDir=" + backupBaseDir);
            log("zip.name=" + zipName);
            log("FTP host=" + ftpHost + ":" + ftpPort + ", user=" + ftpUser
                    + ", passive=" + ftpPassive + ", remoteDir=" + ftpRemoteDir);
            log("ftp.connect.timeout.ms=" + ftpConnectTimeoutMs + ", ftp.data.timeout.ms=" + ftpDataTimeoutMs);
            log("watch.recursive=" + watchRecursive + ", watch.files.maxDepth=" + watchFilesMaxDepth);
            log("watch.expand.enabled=" + watchExpandEnabled + ", subdir.regex=" + watchExpandSubdirRegex
                    + ", depth=" + watchExpandDepth);
        }
    }

    // ================= ConfigLoader =================
    static class ConfigLoader {
        static Map<String, Properties> load(String path) throws IOException {
            Map<String, Properties> sections = new LinkedHashMap<String, Properties>();
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
                Properties current = null;
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0) continue;
                    if (line.startsWith("#") || line.startsWith(";")) continue;

                    if (line.startsWith("[") && line.endsWith("]")) {
                        String name = line.substring(1, line.length() - 1).trim();
                        current = new Properties();
                        sections.put(name, current);
                    } else {
                        if (current == null) {
                            current = new Properties();
                            sections.put("Global", current);
                        }
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String k = line.substring(0, eq).trim();
                            String v = line.substring(eq + 1).trim();
                            current.setProperty(k, v);
                        }
                    }
                }
            } finally {
                if (br != null) try { br.close(); } catch (Exception ignore) {}
            }
            return sections;
        }
    }

    // ================= 掃描/搬移 相關 Utils =================
    static String resolvePlaceholders(String s, String hostname, String tsHour) {
        if (s == null) return null;
        String out = s;
        out = out.replace("{hostname}", hostname);
        out = out.replace("{yyyyMMddHH}", tsHour);
        // 若未來需要 {yyyyMMdd}，可在此補：new SimpleDateFormat("yyyyMMdd")
        return out;
    }

    static List<File> resolveTargetDirs(AppConfig cfg) {
        // 若未啟用 expand，直接把 watch.dirs 轉成 File
        if (!cfg.watchExpandEnabled || cfg.watchExpandSubdirRegex == null || cfg.watchExpandSubdirRegex.trim().length() == 0) {
            List<File> out = new ArrayList<File>();
            for (int i = 0; i < cfg.watchDirs.size(); i++) {
                File f = new File(cfg.watchDirs.get(i));
                if (f.isDirectory()) out.add(f);
                else LoggerUtil.error("watch.dir 非資料夾或不存在：" + f.getAbsolutePath());
            }
            return out;
        }
        // 啟用 expand：先從每個 watch.dir 挑出符合 regex 的子資料夾
        Pattern pat = Pattern.compile(cfg.watchExpandSubdirRegex);
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < cfg.watchDirs.size(); i++) {
            File base = new File(cfg.watchDirs.get(i));
            if (!base.isDirectory()) {
                LoggerUtil.error("watch.dir 非資料夾或不存在：" + base.getAbsolutePath());
                continue;
            }
            collectMatchingDirs(base, pat, cfg.watchExpandDepth, 0, out);
        }
        return out;
    }

    static void collectMatchingDirs(File dir, Pattern pat, int maxDepth, int depth, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f.isDirectory()) {
                String name = f.getName();
                if (pat.matcher(name).matches()) out.add(f);
                if (maxDepth < 0 || depth < maxDepth) {
                    collectMatchingDirs(f, pat, maxDepth, depth + 1, out);
                }
            }
        }
    }

    static List<File> listFilesWithDepth(File root, int maxDepth) {
        List<File> out = new ArrayList<File>();
        if (root == null || !root.isDirectory()) return out;
        walkFiles(root, maxDepth, 0, out);
        return out;
    }

    static void walkFiles(File dir, int maxDepth, int depth, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f.isFile()) out.add(f);
            else if (f.isDirectory()) {
                if (maxDepth < 0 || depth < maxDepth) {
                    walkFiles(f, maxDepth, depth + 1, out);
                }
            }
        }
    }

    static boolean isStable(File f, long stableMs) {
        long age = System.currentTimeMillis() - f.lastModified();
        return age >= stableMs;
    }

    static List<Pattern> compileGlobList(List<String> globs) {
        List<Pattern> list = new ArrayList<Pattern>();
        if (globs == null) return list;
        for (int i = 0; i < globs.size(); i++) {
            String g = globs.get(i);
            if (g == null || g.trim().length() == 0) continue;
            list.add(Pattern.compile(globToRegex(g.trim())));
        }
        return list;
    }

    static boolean matchAny(String name, List<Pattern> pats) {
        if (pats == null) return false;
        if (pats.isEmpty()) return false;
        for (int i = 0; i < pats.size(); i++) {
            if (pats.get(i).matcher(name).matches()) return true;
        }
        return false;
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        sb.append("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append("."); break;
                case '.': sb.append("\\."); break;
                case '\\': sb.append("\\\\"); break;
                case '+': case '(': case ')': case '^': case '$':
                case '{': case '}': case '[': case ']': case '|':
                    sb.append("\\").append(c); break;
                default: sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    static void copyFile(File src, File dst) throws IOException {
        ensureDir(dst.getParentFile());
        FileInputStream fis = null;
        FileOutputStream fos = null;
        FileChannel in = null, out = null;
        try {
            fis = new FileInputStream(src);
            fos = new FileOutputStream(dst);
            in = fis.getChannel();
            out = fos.getChannel();
            long size = in.size();
            long pos = 0;
            while (pos < size) {
                long transferred = in.transferTo(pos, Math.min(16 * 1024 * 1024, size - pos), out);
                if (transferred <= 0) break;
                pos += transferred;
            }
            out.force(true);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) {}
            if (out != null) try { out.close(); } catch (Exception ignore) {}
            if (fis != null) try { fis.close(); } catch (Exception ignore) {}
            if (fos != null) try { fos.close(); } catch (Exception ignore) {}
        }
    }

    static void ensureDir(File d) {
        if (d == null) return;
        if (!d.exists()) d.mkdirs();
        if (!d.isDirectory()) throw new IllegalArgumentException("不是資料夾: " + d.getAbsolutePath());
    }

    static void deleteQuietly(File f) {
        try {
            if (f == null) return;
            if (f.isDirectory()) {
                File[] list = f.listFiles();
                if (list != null) for (int i = 0; i < list.length; i++) deleteQuietly(list[i]);
            }
            f.delete();
        } catch (Exception ignore) {}
    }

    static List<File> filterTargetsUnder(File root, List<File> allTargets) throws IOException {
        List<File> out = new ArrayList<File>();
        String base = root.getCanonicalPath();
        for (int i = 0; i < allTargets.size(); i++) {
            File t = allTargets.get(i);
            String v = t.getCanonicalPath();
            if (v.startsWith(base)) out.add(t);
        }
        return out;
    }

    static boolean matchesAnyTargetRoot(File srcRoot, AppConfig cfg) {
        // 若未啟用 expand，預設回 true 讓傳統行為生效
        if (!cfg.watchExpandEnabled) return srcRoot.isDirectory();
        // 啟用 expand，但沒命中任何子資料夾時，不直接把 srcRoot 當目標
        return false;
    }

    static void zipDirectory(File sourceDir, File zipFile) throws IOException {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            throw new IllegalArgumentException("zipDirectory: 來源不是資料夾：" + 
                                               (sourceDir == null ? "null" : sourceDir.getAbsolutePath()));
        }
        ensureDir(zipFile.getParentFile());

        ZipOutputStream zos = null;
        try {
            FileOutputStream fos = new FileOutputStream(zipFile);
            zos = new ZipOutputStream(new BufferedOutputStream(fos));
            String basePath = sourceDir.getCanonicalPath();

            zipWalk(sourceDir, sourceDir, basePath, zos);
            zos.flush();
        } finally {
            if (zos != null) try { zos.close(); } catch (Exception ignore) {}
        }
    }

    static void zipWalk(File root, File node, String basePath, ZipOutputStream zos) throws IOException {
        File[] list = node.listFiles();
        if (list == null || list.length == 0) {
            // 空資料夾也要放入 zip（保留結構）
            String relDir = toZipEntryPath(root, basePath);
            if (relDir.length() > 0 && !relDir.endsWith("/")) relDir += "/";
            if (relDir.length() > 0) {
                ZipEntry ze = new ZipEntry(relDir);
                zos.putNextEntry(ze);
                zos.closeEntry();
            }
            return;
        }

        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f.isDirectory()) {
                zipWalk(f, f, basePath, zos);
            } else {
                String relPath = toZipEntryPath(f, basePath);
                ZipEntry ze = new ZipEntry(relPath);
                zos.putNextEntry(ze);

                // 以 BufferedInputStream 讀入並寫入 zip
                BufferedInputStream bis = null;
                try {
                    bis = new BufferedInputStream(new FileInputStream(f));
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = bis.read(buf)) != -1) {
                        zos.write(buf, 0, len);
                    }
                } finally {
                    if (bis != null) try { bis.close(); } catch (Exception ignore) {}
                    zos.closeEntry();
                }
            }
        }
    }

    // 轉成 zip entry 路徑（相對於 basePath，並使用 / 作為分隔）
    static String toZipEntryPath(File file, String basePath) throws IOException {
        String abs = file.getCanonicalPath();
        String rel = abs.substring(basePath.length());
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        // zip 規格要求使用正斜線
        rel = rel.replace(File.separatorChar, '/');
        return rel;
    }

    static boolean ftpUploadWithRetry(File localFile, AppConfig cfg, int maxRetry, long backoffMs) {
    for (int attempt = 1; attempt <= maxRetry; attempt++) {
        try {
            ftpUploadFile(localFile, cfg);
            return true;
        } catch (Exception e) {
            LoggerUtil.info("FTP 嘗試第 " + attempt + " 次失敗：" + e.getMessage());
            if (attempt == maxRetry) break;
            try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { /* ignore */ }
        }
    }
    return false;
}

    static void ftpUploadFile(File localFile, AppConfig cfg) throws IOException {
        if (localFile == null || !localFile.isFile()) {
            throw new IllegalArgumentException("FTP 上傳: 本機檔案不存在：" + (localFile == null ? "null" : localFile.getAbsolutePath()));
        }
        if (cfg.ftpHost == null || cfg.ftpUser == null || cfg.ftpPass == null || cfg.ftpRemoteDir == null) {
            throw new IllegalStateException("FTP 參數不完整（host/user/pass/remoteDir 必填）");
        }

        FTPClient ftp = new FTPClient();
        // 超時設定
        ftp.setConnectTimeout(cfg.ftpConnectTimeoutMs);
        ftp.setDataTimeout(cfg.ftpDataTimeoutMs);
        // 控制連線 keep-alive（秒）
        ftp.setControlKeepAliveTimeout(60);

        try {
            // 1) Connect
            ftp.connect(cfg.ftpHost, cfg.ftpPort);
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                throw new IOException("連線失敗，回應碼：" + reply + " / " + ftp.getReplyString());
            }

            // 2) Login
            if (!ftp.login(cfg.ftpUser, cfg.ftpPass)) {
                throw new IOException("登入失敗：" + ftp.getReplyString());
            }

            // 3) 模式與型態
            if (cfg.ftpPassive) ftp.enterLocalPassiveMode();
            else                ftp.enterLocalActiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.setBufferSize(64 * 1024);

            // 4) 確保遠端目錄存在並切換
            String remoteDir = normalizeRemoteDir(cfg.ftpRemoteDir);
            ensureAndCwd(ftp, remoteDir);  // 逐層 makeDirectory + changeWorkingDirectory

            // 5) 以 .part 暫名上傳 → rename 正式檔
            String finalName = localFile.getName();
            String tempName  = finalName + ".part";

            BufferedInputStream bis = null;
            try {
                bis = new BufferedInputStream(new FileInputStream(localFile));
                if (!ftp.storeFile(tempName, bis)) {
                    throw new IOException("上傳失敗：" + ftp.getReplyString());
                }
            } finally {
                if (bis != null) try { bis.close(); } catch (Exception ignore) {}
            }

            if (!ftp.rename(tempName, finalName)) {
                // rename 失敗時嘗試清掉暫名檔
                try { ftp.deleteFile(tempName); } catch (Exception ignore) {}
                throw new IOException("rename 失敗：" + ftp.getReplyString());
            }

            // 6) Logout
            ftp.logout();

        } finally {
            if (ftp.isConnected()) {
                try { ftp.disconnect(); } catch (Exception ignore) {}
            }
        }
    }

    // 正規化遠端目錄：確保以 / 開頭、無尾端 /
    static String normalizeRemoteDir(String dir) {
        if (dir == null) return "/";
        String s = dir.trim();
        if (s.length() == 0) return "/";
        if (!s.startsWith("/")) s = "/" + s;
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    // 逐層建立並切換到目標目錄
    static void ensureAndCwd(FTPClient ftp, String path) throws IOException {
        String[] parts = path.split("/");
        String cur = "";
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p == null || p.length() == 0) continue;
            cur += "/" + p;
            if (!ftp.changeWorkingDirectory(cur)) {
                // 嘗試建立
                if (!ftp.makeDirectory(cur)) {
                    throw new IOException("建立目錄失敗：" + cur + " / " + ftp.getReplyString());
                }
                if (!ftp.changeWorkingDirectory(cur)) {
                    throw new IOException("切換目錄失敗：" + cur + " / " + ftp.getReplyString());
                }
            }
        }
    }

    // ================= 基礎 Utils =================
    static void log(String s) { System.out.println(new Date() + " | " + s); }

    static String getHostnameFromSystem() {
        String h = System.getProperty("hostname");
        if (h != null && h.trim().length() > 0) return h.trim();
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return null; }
    }

    static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String[] arr = s.split("\\s*,\\s*");
        for (int i = 0; i < arr.length; i++) if (arr[i].length() > 0) out.add(arr[i]);
        return out;
    }

    static String defaultIfBlank(String s, String dft) {
        if (s == null) return dft;
        String t = s.trim();
        return t.length() == 0 ? dft : t;
    }

    static String firstNonBlank(String... arr) {
        if (arr == null) return null;
        for (int i = 0; i < arr.length; i++) {
            String s = arr[i];
            if (s != null && s.trim().length() > 0) return s.trim();
        }
        return null;
    }

    static int parseInt(String s, int dft) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dft; }
    }

    static long parseLong(String s, long dft) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return dft; }
    }

    static boolean parseBool(String s, boolean dft) {
        if (s == null) return dft;
        String t = s.trim().toLowerCase(Locale.ENGLISH);
        if ("true".equals(t) || "yes".equals(t) || "1".equals(t)) return true;
        if ("false".equals(t) || "no".equals(t) || "0".equals(t)) return false;
        return dft;
    }

    // ================= LoggerUtil =================
    static class LoggerUtil {
        private static File logDir;
        private static String logLevel = "INFO";
        private static final Object lock = new Object();

        // 初始化：設定 log 目錄與等級
        static void init(String dir, String level) {
            logDir = new File(dir == null ? "logs" : dir.trim());
            if (!logDir.exists()) logDir.mkdirs();
            logLevel = (level == null || level.trim().length() == 0)
                    ? "INFO"
                    : level.trim().toUpperCase(Locale.ENGLISH);
            info("Logger 初始化完成：dir=" + logDir.getAbsolutePath() + ", level=" + logLevel);
        }

        // 對外介面
        static void info(String message)  { logWithLevel("INFO",  message); }
        static void debug(String message) { logWithLevel("DEBUG", message); }
        static void error(String message) { logWithLevel("ERROR", message); }

        // 核心：同時寫 console 與每日分檔
        private static void logWithLevel(String level, String message) {
            if (!shouldLog(level)) return;

            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String line = ts + " [" + level + "] " + message;

            // Console
            System.out.println(line);

            // File（每日輪替）
            writeToFile(line);
        }

        // 等級過濾：只輸出 >= 設定等級
        private static boolean shouldLog(String level) {
            String[] levels = { "DEBUG", "INFO", "ERROR" };
            int want = idx(levels, logLevel);
            int cur  = idx(levels, level);
            return cur >= want;
        }

        private static int idx(String[] arr, String v) {
            for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
            return arr.length; // 未知等級放最後
        }

        private static void writeToFile(String msg) {
            synchronized (lock) {
                try {
                    String day = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
                    File lf = new File(logDir, day + ".log");
                    // 確保目錄存在
                    File parent = lf.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    BufferedWriter bw = new BufferedWriter(new FileWriter(lf, true));
                    bw.write(msg);
                    bw.newLine();
                    bw.close();
                } catch (Exception e) {
                    // 檔案寫入失敗不阻斷主流程
                    System.err.println("寫入 log 檔失敗：" + e.getMessage());
                }
            }
        }
    }

}
