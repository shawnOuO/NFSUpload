import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class Main {

    // ====== 第 1 段開始：初始化 + 設定載入 + 昨天區間 + 來源路徑解析 ======
    public static void main(String[] args) {
        String propPath = (args != null && args.length > 0) ? args[0] : "app.properties";
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

        // 設定讀取
        String sourcePathsCsv   = p.getProperty("source.paths", "");
        int    expandDepth      = parseInt(p.getProperty("source.expand.depth", "0"), 0);
        String subdirRegexStr   = p.getProperty("source.subdir.regex", "^.+$");
        String excludeRegexStr  = p.getProperty("source.exclude.regex", "");
        String timezoneId       = p.getProperty("timezone", "Asia/Taipei");

        // 計算「昨天」時間區間 [yStart, todayStart)
        TimeZone tz = TimeZone.getTimeZone(timezoneId);
        long[] range = computeYesterdayRange(tz);
        long startMillis = range[0];
        long endMillis   = range[1];

        // 展開來源根目錄（支援萬用字元 * 與白/黑名單）
        List<File> baseDirs = parseAndExpandSourcePaths(
                sourcePathsCsv,
                expandDepth,
                subdirRegexStr,
                excludeRegexStr
        );

        if (baseDirs.isEmpty()) {
            log("沒有有效的來源路徑，結束。");
            return;
        }

        // 先列印確認（之後第 2 段會在這些 baseDirs 上做「收檔 + 壓縮」）
        log("本次來源根目錄（展開後）共 " + baseDirs.size() + " 個：");
        for (int i = 0; i < baseDirs.size(); i++) {
            log("  - " + baseDirs.get(i).getAbsolutePath());
        }

        // TODO：第 2 段：收集昨天檔案 + 逐個 baseDir 壓縮成 zip
        // TODO：第 3 段：FTP 連線 + 建立遠端目錄 + 上傳 zip

        // 先到此收尾；完整流程會在貼完第 2/3 段後串起來
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

        String[] tokens = csv.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String raw = tokens[i].trim();
            if (raw.length() == 0) continue;

            // 標準化路徑分隔符
            String norm = raw.replace('\\', '/');

            if (norm.endsWith("/*")) {
                // 只支援「尾端一層 * 」的展開（C:/GIT/data/*）
                String parent = norm.substring(0, norm.length() - 2); // 去掉 "/*"
                File parentDir = new File(parent);
                if (!parentDir.isDirectory()) {
                    log("展開根目錄不存在或不是資料夾，略過: " + parentDir.getAbsolutePath());
                    continue;
                }
                expandOneLevel(parentDir, expandDepth, includePattern, excludePattern, result);
            } else {
                // 非萬用字元：直接加入（若存在且為資料夾）
                File f = new File(norm);
                if (f.exists() && f.isDirectory()) {
                    result.add(f);
                } else {
                    log("來源路徑不存在或非資料夾，略過: " + f.getAbsolutePath());
                }
            }
        }
        return result;
    }

    // 依 expandDepth 展開資料夾；對於 C:/GIT/data/*，通常設 expandDepth=1
    private static void expandOneLevel(File root,
                                       int depth,
                                       Pattern includePattern,
                                       Pattern excludePattern,
                                       List<File> out) {
        if (depth <= 0) {
            // 不展開就直接加入 root（理論上不會用到此分支，保留以防設定錯誤）
            if (root.isDirectory()) out.add(root);
            return;
        }
        // BFS/DFS 任一即可；這裡只展開一層時直接 listFiles
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            File k = kids[i];
            if (!k.isDirectory()) continue;

            String name = k.getName();
            if (excludePattern != null && excludePattern.matcher(name).matches()) {
                // 黑名單命中 → 排除
                continue;
            }
            if (includePattern != null && !includePattern.matcher(name).matches()) {
                // 不在白名單 → 排除
                continue;
            }
            out.add(k);

            // 若要展開到更深層，可遞減 depth 並遞迴（此案通常 depth=1，不再深入）
            if (depth > 1) {
                expandOneLevel(k, depth - 1, includePattern, excludePattern, out);
            }
        }
    }

    // 昨天的 [00:00, 今天 00:00)
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

    // 基本工具
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
    private static void log(String s) { System.out.println(ts() + " " + s); }
    private static String ts() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date());
    }
    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignore) {}
    }
    // ====== 第 1 段結束 ======
}
