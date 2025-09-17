import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.net.InetAddress;
import java.util.zip.*;

public class Main {

    // ====== 第 1 段開始：初始化 + 設定載入 + 昨天區間 + 來源路徑解析 ======
    // ====== 第 1 段開始：初始化 + 設定載入 + 昨天區間 + 來源路徑解析（修正版） ======
    public static void main(String[] args) {
        // 預設讀取 config.txt（可用參數覆寫）
        String propPath = (args != null && args.length > 0) ? args[0] : "config.txt";

        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(propPath);
            p.load(in);
        } catch (Exception e) {
            log("讀取設定檔失敗: " + e.getMessage());
            return;
        } finally {
            closeQuietly(in);
        }

        // 初始化 Logger 與時區（★避免重複宣告）
        String timezoneId = p.getProperty("timezone", "Asia/Taipei");
        TimeZone tz = TimeZone.getTimeZone(timezoneId);
        Log.init(p, tz);
        Log.info("Logger initialized. Level=" + p.getProperty("log.level","INFO"));

        // 讀取關鍵設定
        String sourcePathsCsv  = p.getProperty("source.paths", "");
        int    expandDepth     = parseInt(p.getProperty("source.expand.depth", "0"), 0);
        String includeRegexStr = p.getProperty("source.subdir.regex", "^.+$"); // 預設全收
        String excludeRegexStr = p.getProperty("source.exclude.regex", "");    // 預設不排除

        // 計算「昨天」區間 [昨日00:00, 今日00:00)
        long[] range = computeYesterdayRange(tz);
        long startMillis = range[0];
        long endMillis   = range[1];

        // 日期字串（檔名/遠端目錄用）
        String remoteDatePattern = p.getProperty("remote.dir.date.pattern", "yyyyMMdd");
        SimpleDateFormat ymd = new SimpleDateFormat(remoteDatePattern);
        ymd.setTimeZone(tz);
        String dateStr = ymd.format(new Date(startMillis));

        // 壓縮策略（僅作訊息；實際第 2 段按你的 staging/搬移邏輯）
        String combineMode = p.getProperty("zip.combine.mode", "single");

        // 展開來源根目錄（支援 C:/GIT/data/* 一層展開）
        List<File> baseDirs = parseAndExpandSourcePaths(
                sourcePathsCsv, expandDepth, includeRegexStr, excludeRegexStr);

        if (baseDirs.isEmpty()) {
            log("沒有有效的來源路徑，結束。");
            return;
        }

        // 列印確認
        log("時區: " + timezoneId + "；昨天區間: " + new Date(startMillis) + " ~ " + new Date(endMillis));
        log("zip.combine.mode=" + combineMode + "（single=所有來源合併一顆）");
        log("本次來源根目錄（展開後）共 " + baseDirs.size() + " 個：");
        for (File d : baseDirs) {
            log("  - " + d.getAbsolutePath());
        }

        // 預告壓縮檔名（供你核對）
        String hostname = getHostname();
        log("預計壓縮檔名（single 模式）： " + hostname + "_" + dateStr + ".zip");

        // === 下面銜接「第 2 段：Staging 搬移 -> 壓縮」 ===

        
        // === 第 2 段開始：收集前一天檔案 + 壓縮（Hybrid：固定路徑各一顆；* 展開合併一顆） ===
        // === 第 2 段開始：Staging 搬移 -> 壓縮（Hybrid：固定路徑各一顆；* 合併一顆） ===
        String zipOutDir = p.getProperty("zip.output.dir", "out");
        File outDir = new File(zipOutDir);
        if (!outDir.exists()) { outDir.mkdirs(); }

        String stagingBase = p.getProperty("staging.base.dir", "staging");
        boolean stagingCleanup = Boolean.parseBoolean(p.getProperty("staging.cleanup", "false"));
        File stagingBaseDir = new File(stagingBase);
        if (!stagingBaseDir.exists()) { stagingBaseDir.mkdirs(); }

        // 重新依 source.paths 拆成：固定路徑 vs 萬用字元父層
        List<String> tokens = splitCsv(sourcePathsCsv);
        List<File> fixedRoots = new ArrayList<File>();
        List<File> globParents = new ArrayList<File>();

        for (int i = 0; i < tokens.size(); i++) {
            String norm = sanitizeToken(tokens.get(i));
            if (norm.length() == 0) continue;
            if (norm.endsWith("/*")) {
                File parent = new File(norm.substring(0, norm.length() - 2));
                if (parent.isDirectory()) {
                    globParents.add(parent);
                } else {
                    Log.warn("展開根目錄不存在或不是資料夾，略過: " + parent.getAbsolutePath());
                }
            } else {
                File f = new File(norm);
                if (f.exists() && f.isDirectory()) fixedRoots.add(f);
                else Log.warn("來源路徑不存在或非資料夾，略過: " + f.getAbsolutePath());
            }
        }

        List<File> zipsToUpload = new ArrayList<File>();
        List<File> stagingRoots = new ArrayList<File>(); // 供清理用

        // 1) 固定路徑：各建立 staging/<basename>_yyyyMMdd，搬移昨天檔案進去，再各自壓一顆 zip
        for (int i = 0; i < fixedRoots.size(); i++) {
            File base = fixedRoots.get(i);
            List<File> selected = new ArrayList<File>();
            collectFilesByLastModified(base, startMillis, endMillis, selected);
            if (selected.isEmpty()) {
                Log.info("→ [" + base.getName() + "] 無前一天檔案，略過搬移與壓縮。");
                continue;
            }
            File stagingRoot = new File(stagingBaseDir, base.getName() + "_" + dateStr);
            ensureDir(stagingRoot);
            stagingRoots.add(stagingRoot);

            // 搬移（保留相對於 base 的路徑結構）
            String baseAbs = base.getAbsolutePath();
            for (int j = 0; j < selected.size(); j++) {
                File src = selected.get(j);
                String rel = toRelativePath(baseAbs, src.getAbsolutePath());
                File dst = new File(stagingRoot, rel);
                ensureDir(dst.getParentFile());
                try {
                    moveFileWithFallback(src, dst);
                } catch (IOException e) {
                    Log.error("搬移失敗: " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath() + "，原因: " + e.getMessage());
                }
            }

            // 壓縮 stagingRoot → out/<basename>_yyyyMMdd.zip
            File zipFile = new File(outDir, base.getName() + "_" + dateStr + ".zip");
            try {
                zipFolder(stagingRoot, zipFile);
                Log.info("→ 已建立壓縮檔: " + zipFile.getAbsolutePath() + " (" + zipFile.length() + " bytes)");
                zipsToUpload.add(zipFile);
            } catch (IOException e) {
                Log.error("壓縮失敗(perBase): " + stagingRoot.getAbsolutePath() + " -> " + zipFile.getAbsolutePath() + "，原因: " + e.getMessage());
            }
        }

        // 2) C:/GIT/data/*：建立 staging/{hostname}_yyyyMMdd，將每個 tester 的昨天檔搬到對應子資料夾，再合併壓一顆
        if (!globParents.isEmpty()) {
            Pattern includePattern = null;
            Pattern excludePattern = null;
            try { includePattern = Pattern.compile(includeRegexStr); } catch (Exception ignore) {}
            try { if (excludeRegexStr != null && excludeRegexStr.length() > 0) excludePattern = Pattern.compile(excludeRegexStr); } catch (Exception ignore) {}

            File mergedStagingRoot = new File(stagingBaseDir, hostname + "_" + dateStr);
            boolean hasAny = false;

            for (int g = 0; g < globParents.size(); g++) {
                File parent = globParents.get(g);

                List<File> testers = new ArrayList<File>();
                expandOneLevel(parent, expandDepth, includePattern, excludePattern, testers);
                for (int t = 0; t < testers.size(); t++) {
                    File testerRoot = testers.get(t);
                    List<File> selected = new ArrayList<File>();
                    collectFilesByLastModified(testerRoot, startMillis, endMillis, selected);
                    if (selected.isEmpty()) continue;

                    hasAny = true;
                    File testerStage = new File(mergedStagingRoot, testerRoot.getName());
                    ensureDir(testerStage);

                    String baseAbs = testerRoot.getAbsolutePath();
                    for (int j = 0; j < selected.size(); j++) {
                        File src = selected.get(j);
                        String rel = toRelativePath(baseAbs, src.getAbsolutePath());
                        File dst = new File(testerStage, rel);
                        ensureDir(dst.getParentFile());
                        try {
                            moveFileWithFallback(src, dst);
                        } catch (IOException e) {
                            Log.error("搬移失敗(合併): " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath() + "，原因: " + e.getMessage());
                        }
                    }
                }
            }

            if (hasAny) {
                ensureDir(mergedStagingRoot);
                stagingRoots.add(mergedStagingRoot);
                File mergedZip = new File(outDir, hostname + "_" + dateStr + ".zip");
                try {
                    zipFolder(mergedStagingRoot, mergedZip);
                    Log.info("→ 已建立合併壓縮檔: " + mergedZip.getAbsolutePath() + " (" + mergedZip.length() + " bytes)");
                    zipsToUpload.add(mergedZip);
                } catch (IOException e) {
                    Log.error("壓縮失敗(合併): " + mergedStagingRoot.getAbsolutePath() + " -> " + mergedZip.getAbsolutePath() + "，原因: " + e.getMessage());
                }
            } else {
                Log.info("→ [* 合併] 無前一天檔案，略過 staging 與壓縮。");
            }
        }

        // 若需要在壓縮完成後清空 staging
        if (stagingCleanup) {
            for (int i = 0; i < stagingRoots.size(); i++) {
                deleteDirectoryRecursive(stagingRoots.get(i));
            }
            // 若整個 staging 目錄已空，可嘗試清掉 root（可選）
            // deleteDirectoryRecursive(stagingBaseDir);
        }
        // === 第 2 段結束：已產生 zipsToUpload 可供上傳 ===



        // TODO（第 3 段）：FTP 連線 + 遞迴建立遠端目錄 + 上傳 zip
        // === 第 3 段開始：FTP 上傳 ===
        String ftpHost = p.getProperty("ftp.host", "");
        int    ftpPort = parseInt(p.getProperty("ftp.port", "21"), 21);
        String ftpUser = p.getProperty("ftp.username", "");
        String ftpPass = p.getProperty("ftp.password", "");
        String ftpRemoteBaseRaw = p.getProperty("ftp.remote.base", "/upload");
        boolean ftpPassive = Boolean.parseBoolean(p.getProperty("ftp.passive", "true"));
        int connectTimeout = parseInt(p.getProperty("ftp.connect.timeout.ms", "15000"), 15000);
        int dataTimeout = parseInt(p.getProperty("ftp.data.timeout.ms", "30000"), 30000);
        boolean remoteAppendDateDir = Boolean.parseBoolean(p.getProperty("remote.append.date.dir", "true"));

        boolean dryRun = Boolean.parseBoolean(p.getProperty("dry.run", "false"));

        if (zipsToUpload.isEmpty()) {
            Log.info("沒有可上傳的壓縮檔，結束。");
            return;
        }

        String ftpRemoteBase = replaceHostnameVars(ftpRemoteBaseRaw, hostname); // 支援 {hostname}
        String remoteDir = ftpRemoteBase;
        if (remoteAppendDateDir) remoteDir = ftpRemoteBase + "/" + dateStr;

        if (dryRun) {
            Log.info("dry.run=true，僅列出將上傳的檔案與遠端目錄：");
            Log.info("  遠端目錄: " + remoteDir);
            for (int i = 0; i < zipsToUpload.size(); i++) {
                Log.info("  - " + zipsToUpload.get(i).getAbsolutePath());
            }
            return;
        }

        // 連線與上傳（需 commons-net）
        org.apache.commons.net.ftp.FTPClient ftp = new org.apache.commons.net.ftp.FTPClient();
        try {
            ftp.setConnectTimeout(connectTimeout);
            ftp.setDefaultTimeout(connectTimeout);
            ftp.setDataTimeout(dataTimeout);
            ftp.setControlEncoding("UTF-8");

            Log.info("連線 FTP: " + ftpHost + ":" + ftpPort);
            ftp.connect(ftpHost, ftpPort);
            int reply = ftp.getReplyCode();
            if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(reply)) {
                throw new IOException("FTP 連線被拒絕, replyCode=" + reply);
            }

            if (!ftp.login(ftpUser, ftpPass)) {
                throw new IOException("FTP 登入失敗，請檢查帳密。");
            }

            if (ftpPassive) ftp.enterLocalPassiveMode();
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
            ftp.setBufferSize(8192);

            ensureRemoteDirectory(ftp, remoteDir);
            Log.info("遠端上傳目錄: " + remoteDir);

            for (int i = 0; i < zipsToUpload.size(); i++) {
                File zf = zipsToUpload.get(i);
                String remotePath = remoteDir + "/" + zf.getName();
                Log.info("上傳: " + zf.getName());
                BufferedInputStream bis = null;
                try {
                    bis = new BufferedInputStream(new FileInputStream(zf));
                    boolean ok = ftp.storeFile(remotePath, bis);
                    if (!ok) throw new IOException("storeFile 失敗: " + ftp.getReplyString());
                } finally {
                    closeQuietly(bis);
                }
            }
            Log.info("全部上傳完成。");
        } catch (Exception e) {
            Log.error("FTP 發生錯誤: " + e.getMessage(), e);
        } finally {
            if (ftp.isConnected()) {
                try { ftp.logout(); } catch (Exception ignore) {}
                try { ftp.disconnect(); } catch (Exception ignore) {}
            }
        }
        // === 第 3 段結束 ===


    }

    // 解析並展開 source.paths，支援像 C:/GIT/data/* 這種一層展開
    private static List<File> parseAndExpandSourcePaths(String csv,
                                                        int expandDepth,
                                                        String includeRegexStr,
                                                        String excludeRegexStr) {
        List<File> result = new ArrayList<File>();
        if (csv == null || csv.trim().length() == 0) return result;

        Pattern includePattern = null;
        Pattern excludePattern = null;
        try { includePattern = Pattern.compile(includeRegexStr); } catch (Exception ignore) {}
        try { if (excludeRegexStr != null && excludeRegexStr.length() > 0) excludePattern = Pattern.compile(excludeRegexStr); } catch (Exception ignore) {}

        // 簡易 CSV 切分（支援用引號包住含空白的路徑）
        List<String> tokens = splitCsv(csv);

        for (int i = 0; i < tokens.size(); i++) {
            String raw = tokens.get(i);
            String norm = sanitizeToken(raw); // 去引號、去零寬字元、標準化斜線

            if (norm.length() == 0) continue;

            if (norm.endsWith("/*")) {
                // 只支援「尾端一層 * 」的展開（例：C:/GIT/data/*）
                String parent = norm.substring(0, norm.length() - 2);
                File parentDir = new File(parent);
                if (!parentDir.isDirectory()) {
                    log("展開根目錄不存在或不是資料夾，略過: " + parentDir.getAbsolutePath());
                    continue;
                }
                expandOneLevel(parentDir, 1, includePattern, excludePattern, result);
            } else {
                // 非萬用字元：直接加入（若存在且為資料夾）
                File f = new File(norm);
                if (f.exists() && f.isDirectory()) {
                    result.add(f);
                } else {
                    log("來源路徑不存在或非資料夾，略過: " + f.getPath());
                }
            }
        }
        return result;
    }

    // 以最後修改時間篩選 [startMillis, endMillis)
    private static void collectFilesByLastModified(File base, long startMillis, long endMillis, List<File> out) {
        File[] list = base.listFiles();
        if (list == null) return;
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f.isDirectory()) {
                collectFilesByLastModified(f, startMillis, endMillis, out);
            } else {
                long lm = f.lastModified();
                if (lm >= startMillis && lm < endMillis) {
                    out.add(f);
                }
            }
        }
    }

    // 生成相對路徑：不同磁碟/前綴則退回檔名
    private static String toRelativePath(String baseAbs, String targetAbs) {
        if (targetAbs.startsWith(baseAbs)) {
            String rel = targetAbs.substring(baseAbs.length());
            while (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel.length() == 0 ? new File(targetAbs).getName() : rel;
        }
        return new File(targetAbs).getName();
    }


    // 依 expandDepth 展開資料夾；對於 C:/GIT/data/*，通常設 expandDepth=1
    private static void expandOneLevel(File root,
                                       int depth,
                                       Pattern includePattern,
                                       Pattern excludePattern,
                                       List<File> out) {
        if (depth <= 0) {
            if (root.isDirectory()) out.add(root);
            return;
        }
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            File k = kids[i];
            if (!k.isDirectory()) continue;

            String name = k.getName();
            if (excludePattern != null && excludePattern.matcher(name).matches()) {
                continue; // 黑名單
            }
            if (includePattern != null && !includePattern.matcher(name).matches()) {
                continue; // 非白名單
            }
            out.add(k);

            if (depth > 1) {
                expandOneLevel(k, depth - 1, includePattern, excludePattern, out);
            }
        }
    }

    // ===== 工具：昨天時間區間 =====
    private static long[] computeYesterdayRange(TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_MONTH, -1);
        long yStart = cal.getTimeInMillis();
        return new long[]{ yStart, todayStart };
    }

    // ===== 工具：CSV 切分（支援引號）=====
    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (c == ',' && !inQuote) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ===== 工具：清理路徑 token（去引號/零寬字元/換行；標準化斜線）=====
    private static String sanitizeToken(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        // 去頭尾雙引號/單引號
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }

        // 移除常見不可見字元（零寬空白/BOM/Word Joiner/不換行空白 等）
        s = s.replace("\u200B", "")  // ZERO WIDTH SPACE
            .replace("\uFEFF", "")  // BOM
            .replace("\u2060", "")  // WORD JOINER
            .replace("\u00A0", ""); // NBSP

        // 去除換行與 \r
        s = s.replace("\r", "").replace("\n", "");

        // Windows 路徑標準化成使用 '/'
        s = s.replace('\\', '/');

        // 折疊重複的斜線（避免 'C://GIT//data/*' 之類）
        while (s.contains("//")) s = s.replace("//", "/");

        return s.trim();
    }


    // ===== 工具：Hostname（壓縮檔名會用到）=====
    private static String getHostname() {
        String hn = System.getenv("COMPUTERNAME");
        if (hn == null || hn.length() == 0) hn = System.getenv("HOSTNAME");
        if (hn == null || hn.length() == 0) {
            try { hn = InetAddress.getLocalHost().getHostName(); } catch (Exception ignore) {}
        }
        if (hn == null || hn.length() == 0) hn = "UNKNOWNHOST";
        return hn;
    }

    // 建立資料夾（若不存在）
    private static void ensureDir(File dir) {
        if (dir != null && !dir.exists()) dir.mkdirs();
    }

    // 將檔案搬移到目標（跨磁碟自動 fallback 成 copy+delete）
    private static void moveFileWithFallback(File src, File dst) throws IOException {
        ensureDir(dst.getParentFile());
        if (dst.exists() && !dst.isDirectory()) {
            // 直接覆蓋（保守作法：先刪除，再搬移）
            if (!dst.delete()) {
                throw new IOException("無法覆寫目的檔案: " + dst.getAbsolutePath());
            }
        }
        // 先嘗試 rename（同磁碟最有效率）
        if (src.renameTo(dst)) return;

        // 跨磁碟：改用 copy + delete
        BufferedInputStream in = null;
        BufferedOutputStream out = null;
        try {
            in = new BufferedInputStream(new FileInputStream(src));
            ensureDir(dst.getParentFile());
            out = new BufferedOutputStream(new FileOutputStream(dst));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (!src.delete()) {
            // 若刪不掉，至少不要留兩份；可視需要改成記錄警告
            throw new IOException("搬移後無法刪除來源檔: " + src.getAbsolutePath());
        }
    }

    // 將整個資料夾內容壓縮成 ZIP（ZIP 內路徑為相對於 root 的路徑；不含最外層資料夾名）
    private static void zipFolder(File root, File zipFile) throws IOException {
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
            String baseAbs = root.getAbsolutePath();
            zipFolderRecursive(root, baseAbs, zos);
        } finally {
            closeQuietly(zos);
        }
    }
    
    private static void zipFolderRecursive(File cur, String baseAbs, java.util.zip.ZipOutputStream zos) throws IOException {
        File[] list = cur.listFiles();
        if (list == null) return;

        // ★ 新增：排序（資料夾優先，其次依名稱）
        java.util.Arrays.sort(list, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        byte[] buf = new byte[8192];
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f.isDirectory()) {
                zipFolderRecursive(f, baseAbs, zos);
            } else {
                String abs = f.getAbsolutePath();
                String rel = abs.startsWith(baseAbs) ? abs.substring(baseAbs.length()) : f.getName();
                while (rel.startsWith(File.separator)) rel = rel.substring(1);
                rel = rel.replace('\\', '/');
                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(rel);
                ze.setTime(f.lastModified());
                zos.putNextEntry(ze);
                BufferedInputStream in = null;
                try {
                    in = new BufferedInputStream(new FileInputStream(f));
                    int n;
                    while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                } finally {
                    closeQuietly(in);
                    zos.closeEntry();
                }
            }
        }
    }


    // 遞迴刪除資料夾（for staging.cleanup=true）
    private static void deleteDirectoryRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (int i = 0; i < kids.length; i++) deleteDirectoryRecursive(kids[i]);
        }
        try { f.delete(); } catch (Exception ignore) {}
    }

    // FTP 目錄建立（逐層，容錯）
    private static void ensureRemoteDirectory(org.apache.commons.net.ftp.FTPClient ftp, String remoteDir) throws IOException {
        remoteDir = normalizeRemotePath(remoteDir);
        String[] parts = remoteDir.split("/");
        String path = "";
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (seg == null || seg.length() == 0) continue;
            path += "/" + seg;
            if (!ftp.changeWorkingDirectory(path)) {
                if (!ftp.makeDirectory(path) && !ftp.changeWorkingDirectory(path)) {
                    throw new IOException("建立/切換遠端目錄失敗: " + path + ", reply=" + ftp.getReplyString());
                }
            }
        }
    }
    private static String normalizeRemotePath(String p) {
        if (p == null || p.length() == 0) return "/";
        p = p.replace('\\', '/');
        while (p.contains("//")) p = p.replace("//", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p;
    }
    private static String replaceHostnameVars(String s, String hostname) {
        if (s == null) return null;
        return s.replace("{hostname}", hostname);
    }


    // ===== 基本工具 =====
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignore) {}
    }
    // ====== 第 1 段結束 ======
    // ===== 輕量 Logger（零相依，支援日期檔名與大小輪替）=====
    private static final class Log {
        enum Level { DEBUG, INFO, WARN, ERROR }
        private static File dir;
        private static String filePattern = "app_%yyyyMMdd%.log";
        private static String charset = "UTF-8";
        private static boolean toConsole = true;
        private static Level minLevel = Level.INFO;
        private static long maxBytes = 10L * 1024 * 1024; // 10 MiB
        private static int maxBackups = 7;
        private static TimeZone tz = TimeZone.getTimeZone("UTC");

        private static File currentFile;
        private static String currentDateStr = "";
        private static java.io.OutputStream fos;
        private static java.io.Writer writer;

        static synchronized void init(Properties p, TimeZone timeZone) {
            tz = timeZone != null ? timeZone : tz;
            dir = new File(p.getProperty("log.dir", "logs"));
            if (!dir.exists()) dir.mkdirs();

            filePattern = p.getProperty("log.filename.pattern", filePattern);
            charset = p.getProperty("log.charset", charset);
            toConsole = Boolean.parseBoolean(p.getProperty("log.console", "true"));
            maxBytes = parseLongMiB(p.getProperty("log.rotate.max.size.mb", "10"), 10L) * 1024 * 1024;
            maxBackups = parseIntSafe(p.getProperty("log.rotate.max.backups", "7"), 7);

            String lvl = p.getProperty("log.level", "INFO").trim().toUpperCase(Locale.ROOT);
            try { minLevel = Level.valueOf(lvl); } catch (Exception ignore) { minLevel = Level.INFO; }

            reopenIfNeeded(true); // 初次開檔
            // 捕捉未處理例外
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    error("Uncaught exception in thread " + t.getName(), e);
                    flushQuiet();
                }
            });
        }

        static void debug(String msg) { log(Level.DEBUG, msg, null); }
        static void info(String msg)  { log(Level.INFO,  msg, null); }
        static void warn(String msg)  { log(Level.WARN,  msg, null); }
        static void error(String msg) { log(Level.ERROR, msg, null); }
        static void error(String msg, Throwable t) { log(Level.ERROR, msg, t); }

        static synchronized void log(Level lvl, String msg, Throwable t) {
            if (lvl.ordinal() < minLevel.ordinal()) return;
            reopenIfNeeded(false);
            String ts = nowTs();
            String line = String.format("%s %-5s %s", ts, lvl.name(), msg == null ? "" : msg);

            try {
                writer.write(line);
                writer.write(System.getProperty("line.separator"));
                if (t != null) {
                    writer.write(stackTraceToString(t));
                }
                writer.flush();
            } catch (IOException ioe) {
                // 退而求其次寫到 stderr
                System.err.println("[Logger-ERROR] " + ioe.getMessage());
            }

            if (toConsole) {
                if (lvl.ordinal() >= Level.WARN.ordinal()) {
                    System.err.println(line + (t != null ? System.lineSeparator() + stackTraceToString(t) : ""));
                } else {
                    System.out.println(line + (t != null ? System.lineSeparator() + stackTraceToString(t) : ""));
                }
            }
        }

        private static synchronized void reopenIfNeeded(boolean force) {
            String d = formatDateForPattern();
            if (!force && d.equals(currentDateStr) && currentFile != null && currentFile.length() < maxBytes) return;

            // 日期變更或首次建立或大小超限
            try { closeQuiet(); } catch (Exception ignore) {}

            // 每日新檔：以日期展開樣式
            currentDateStr = d;
            String fileName = expandPattern(filePattern, d);
            currentFile = new File(dir, fileName);

            // 若是大小超限（舊檔存在且超過閾值），先做 size 滾動
            if (currentFile.exists() && currentFile.length() >= maxBytes) {
                rotateBySize(currentFile);
            }

            try {
                fos = new java.io.FileOutputStream(currentFile, true); // append
                writer = new java.io.OutputStreamWriter(fos, charset);
            } catch (IOException e) {
                // 若開檔失敗，退回主控台
                currentFile = null;
                System.err.println("[Logger-ERROR] open log file failed: " + e.getMessage());
            }
        }

        private static void rotateBySize(File base) {
            // 將 base.log.(maxBackups-1) 刪掉，其他往上移，base.log -> .1
            for (int i = maxBackups - 1; i >= 1; i--) {
                File older = new File(base.getParentFile(), base.getName() + "." + i);
                File olderNext = new File(base.getParentFile(), base.getName() + "." + (i + 1));
                if (older.exists()) older.renameTo(olderNext);
            }
            File first = new File(base.getParentFile(), base.getName() + ".1");
            base.renameTo(first);
        }

        private static String expandPattern(String pattern, String ymd) {
            // pattern 可能包含 %yyyyMMdd% 這種標記，也可能沒有
            return pattern.replace("%yyyyMMdd%", ymd);
        }

        private static String formatDateForPattern() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd");
            sdf.setTimeZone(tz);
            return sdf.format(new Date());
        }

        private static String nowTs() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            sdf.setTimeZone(tz);
            return sdf.format(new Date());
        }

        private static String stackTraceToString(Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        }

        private static void closeQuiet() {
            try { if (writer != null) writer.close(); } catch (Exception ignore) {}
            try { if (fos != null) fos.close(); } catch (Exception ignore) {}
            writer = null; fos = null;
        }

        private static void flushQuiet() {
            try { if (writer != null) writer.flush(); } catch (Exception ignore) {}
        }

        private static long parseLongMiB(String s, long def) {
            try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
        }
        private static int parseIntSafe(String s, int def) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }
    }

    // ===== 將既有的 log() 導到 Logger（保留你原介面）=====
    private static void log(String s) { Log.info(s); }

}
