package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generic local dictionary library. Dictionaries are downloaded independently from the APK,
 * normalized into one small SQLite schema and can then be enabled/disabled independently.
 */
public final class DictionaryLibraryManager {
    private static final String DIR = "dictionary_library";
    private static final String PREFS = "floatlens_dictionary_library";
    private static final String SCHEMA = "floatlens_dictionary_v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String busyId = "";

    private static final int FORMAT_STARDICT_ZIP = 1;
    private static final int FORMAT_STARDICT_TAR_XZ = 2;
    private static final int FORMAT_CEDICT_GZ = 3;
    private static final int MAX_HITS_PER_DICTIONARY = 12;

    public interface Callback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onFailure(String message);
    }

    public static final class CatalogItem {
        public final String id;
        public final String title;
        public final String description;
        public final String direction;
        public final String license;
        public final String homepage;
        public final int format;
        final String directUrl;
        final String resolver;

        CatalogItem(String id, String title, String description, String direction, String license,
                    String homepage, int format, String directUrl, String resolver) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.direction = direction;
            this.license = license;
            this.homepage = homepage;
            this.format = format;
            this.directUrl = directUrl;
            this.resolver = resolver;
        }
    }

    public static final class Hit {
        public final String dictionaryId;
        public final String dictionaryTitle;
        public final String word;
        public final String displayWord;
        public final String content;
        public final String contentType;

        Hit(String dictionaryId, String dictionaryTitle, String word, String displayWord,
            String content, String contentType) {
            this.dictionaryId = safe(dictionaryId);
            this.dictionaryTitle = safe(dictionaryTitle);
            this.word = safe(word);
            this.displayWord = safe(displayWord);
            this.content = safe(content);
            this.contentType = safe(contentType);
        }
    }

    private static final List<CatalogItem> CATALOG;
    static {
        ArrayList<CatalogItem> items = new ArrayList<>();
        items.add(new CatalogItem(
                "ecdict-stardict", "ECDICT 简明英汉增强版", "约 340 万词；官方 StarDict 发布包，覆盖量最大。",
                "English → 中文", "MIT（项目代码；词库来源请同时遵循上游说明）",
                "https://github.com/skywind3000/ECDICT", FORMAT_STARDICT_ZIP, "", "ecdict_latest_stardict"));
        items.add(new CatalogItem(
                "wikdict-en-zh", "WikDict English → 中文", "Wiktionary 自动生成的轻量双语 StarDict，体积小、更新方便。",
                "English → 中文", "Wiktionary / WikDict licenses",
                "https://www.wikdict.com/", FORMAT_STARDICT_ZIP,
                "https://download.wikdict.com/dictionaries/stardict/wikdict-en-zh.zip", ""));
        items.add(new CatalogItem(
                "wikdict-zh-en", "WikDict 中文 → English", "Wiktionary 自动生成的中文到英文 StarDict，适合反查。",
                "中文 → English", "Wiktionary / WikDict licenses",
                "https://www.wikdict.com/", FORMAT_STARDICT_ZIP,
                "https://download.wikdict.com/dictionaries/stardict/wikdict-zh-en.zip", ""));
        items.add(new CatalogItem(
                "freedict-eng-zho", "FreeDict English → Chinese", "FreeDict 官方开放词典；通过官方 JSON API 自动获取最新版 StarDict。",
                "English → 中文", "FreeDict dictionary-specific open license",
                "https://freedict.org/", FORMAT_STARDICT_TAR_XZ, "", "freedict_eng_zho"));
        items.add(new CatalogItem(
                "cc-cedict", "CC-CEDICT 中文 → English", "成熟的中文英文字典，含简体、繁体、拼音和英文释义。",
                "中文 → English", "CC BY-SA 4.0",
                "https://cc-cedict.org/", FORMAT_CEDICT_GZ,
                "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz", ""));
        CATALOG = Collections.unmodifiableList(items);
    }

    public static List<CatalogItem> catalog() { return CATALOG; }

    public static CatalogItem find(String id) {
        for (CatalogItem item : CATALOG) if (item.id.equals(id)) return item;
        return null;
    }

    public static boolean isBusy() { return !busyId.isBlank(); }
    public static boolean isBusy(String id) { return id != null && id.equals(busyId); }

    public static boolean isInstalled(Context context, String id) {
        File f = dbFile(context, id);
        if (!f.isFile() || f.length() < 4096) return false;
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT value FROM meta WHERE key='schema' LIMIT 1", null);
            if (!c.moveToFirst() || !SCHEMA.equals(c.getString(0))) return false;
            c.close();
            c = db.rawQuery("SELECT value FROM meta WHERE key='ready' LIMIT 1", null);
            return c.moveToFirst() && "1".equals(c.getString(0));
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    public static boolean isEnabled(Context context, String id) {
        return prefs(context).getBoolean("enabled_" + id, true);
    }

    public static void setEnabled(Context context, String id, boolean enabled) {
        prefs(context).edit().putBoolean("enabled_" + id, enabled).apply();
    }

    public static long installedBytes(Context context, String id) {
        File f = dbFile(context, id);
        return f.isFile() ? f.length() : 0L;
    }

    public static int installedCount(Context context) {
        int count = 0;
        for (CatalogItem item : CATALOG) if (isInstalled(context, item.id)) count++;
        return count;
    }

    public static void delete(Context context, String id) {
        if (context == null || isBusy(id)) return;
        deleteDatabaseFamily(dbFile(context, id));
        File dir = rootDir(context);
        deleteRecursive(new File(dir, id + ".work"));
        new File(dir, id + ".download").delete();
    }

    public static void install(Context context, CatalogItem item, Callback callback) {
        if (context == null || item == null) {
            failure(callback, "无效的词典来源");
            return;
        }
        synchronized (DictionaryLibraryManager.class) {
            if (!busyId.isBlank()) {
                failure(callback, "已有词典正在下载或导入");
                return;
            }
            busyId = item.id;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            File dir = rootDir(app);
            File download = new File(dir, item.id + ".download");
            File work = new File(dir, item.id + ".work");
            File tempDb = new File(dir, item.id + ".db.tmp");
            File finalDb = dbFile(app, item.id);
            File oldDb = new File(dir, item.id + ".db.old");
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建词典目录");
                deleteRecursive(work);
                if (!work.mkdirs()) throw new IOException("无法创建导入目录");
                download.delete();
                deleteDatabaseFamily(tempDb);
                deleteDatabaseFamily(oldDb);

                progress(callback, "获取下载地址", 1);
                String url = resolveDownloadUrl(item);
                if (url.isBlank()) throw new IOException("没有可用下载地址");
                download(url, download, callback);
                progress(callback, "导入词典", 72);

                if (item.format == FORMAT_CEDICT_GZ) {
                    importCedict(item, download, tempDb, callback);
                } else {
                    importStarDict(item, download, work, tempDb, callback);
                }
                validateDb(tempDb);
                progress(callback, "安装词典", 98);

                if (finalDb.exists() && !finalDb.renameTo(oldDb)) throw new IOException("无法备份旧词典");
                if (!tempDb.renameTo(finalDb)) {
                    if (oldDb.exists()) oldDb.renameTo(finalDb);
                    throw new IOException("无法安装新词典数据库");
                }
                deleteDatabaseFamily(oldDb);
                prefs(app).edit().putBoolean("enabled_" + item.id, true).apply();
                progress(callback, "完成", 100);
                success(callback);
            } catch (Throwable t) {
                deleteDatabaseFamily(tempDb);
                failure(callback, friendly(t));
                DiagnosticLog.i(app, "DICT_LIBRARY", "install " + item.id + " failed=" + t);
            } finally {
                download.delete();
                deleteRecursive(work);
                busyId = "";
            }
        }, "FloatLens-Dict-" + item.id).start();
    }

    public static List<Hit> lookup(Context context, String rawQuery) {
        String query = DictionaryManager.normalizeQuery(rawQuery);
        if (context == null || query.isBlank()) return Collections.emptyList();
        ArrayList<Hit> out = new ArrayList<>();
        for (CatalogItem item : CATALOG) {
            if (!isInstalled(context, item.id) || !isEnabled(context, item.id)) continue;
            SQLiteDatabase db = null;
            Cursor c = null;
            try {
                db = SQLiteDatabase.openDatabase(dbFile(context, item.id).getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                c = db.rawQuery("SELECT word,display_word,content,content_type FROM entries WHERE word=? COLLATE NOCASE LIMIT "
                        + MAX_HITS_PER_DICTIONARY, new String[]{query});
                while (c.moveToNext()) {
                    out.add(new Hit(item.id, item.title, c.getString(0), c.getString(1), c.getString(2), c.getString(3)));
                }
            } catch (Throwable t) {
                DiagnosticLog.i(context, "DICT_LIBRARY", "lookup " + item.id + " failed=" + t.getClass().getSimpleName());
            } finally {
                if (c != null) c.close();
                if (db != null) db.close();
            }
        }
        return out;
    }

    private static void importStarDict(CatalogItem item, File archive, File work, File dbFile,
                                       Callback callback) throws Exception {
        if (item.format == FORMAT_STARDICT_ZIP) extractZip(archive, work);
        else if (item.format == FORMAT_STARDICT_TAR_XZ) extractTarXz(archive, work);
        else throw new IOException("不支持的 StarDict 压缩格式");

        File ifo = findBySuffix(work, ".ifo");
        File idx = findBySuffix(work, ".idx");
        File idxGz = findBySuffix(work, ".idx.gz");
        File dict = findBySuffix(work, ".dict");
        File dictDz = findBySuffix(work, ".dict.dz");
        if (idx == null && idxGz != null) {
            idx = new File(work, "dictionary.idx");
            gunzip(idxGz, idx);
        }
        if (dict == null && dictDz != null) {
            dict = new File(work, "dictionary.dict");
            gunzip(dictDz, dict);
        }
        if (ifo == null || idx == null || dict == null) throw new IOException("StarDict 包缺少 .ifo/.idx/.dict 文件");

        Map<String, String> info = readIfo(ifo);
        boolean offset64 = "64".equals(info.get("idxoffsetbits"));
        String sequence = safe(info.get("sametypesequence"));
        String contentType = sequence.isBlank() ? "stardict" : sequence;

        createDb(dbFile, item);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        SQLiteStatement insert = db.compileStatement("INSERT INTO entries(word,display_word,content,content_type) VALUES(?,?,?,?)");
        long idxLength = Math.max(1L, idx.length());
        long consumed = 0L;
        int rows = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(idx), 128 * 1024));
             RandomAccessFile data = new RandomAccessFile(dict, "r")) {
            db.beginTransaction();
            try {
                while (true) {
                    CString wordRead = readCString(in);
                    if (wordRead == null) break;
                    consumed += wordRead.bytes + 1L;
                    long offset = offset64 ? in.readLong() : Integer.toUnsignedLong(in.readInt());
                    int size = in.readInt();
                    consumed += offset64 ? 12L : 8L;
                    if (size < 0 || size > 16 * 1024 * 1024) throw new IOException("异常词条大小: " + size);
                    byte[] bytes = new byte[size];
                    data.seek(offset);
                    data.readFully(bytes);
                    String content = decodeStarDictContent(bytes, sequence);
                    if (!wordRead.value.isBlank() && !content.isBlank()) {
                        insert.clearBindings();
                        insert.bindString(1, wordRead.value);
                        insert.bindString(2, wordRead.value);
                        insert.bindString(3, content);
                        insert.bindString(4, contentType);
                        insert.executeInsert();
                        rows++;
                    }
                    if ((rows & 4095) == 0) {
                        int p = 74 + (int) Math.min(22L, consumed * 22L / idxLength);
                        progress(callback, "建立本地索引", p);
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            putMeta(db, "entry_count", String.valueOf(rows));
            putMeta(db, "ready", "1");
        } finally {
            insert.close();
            db.close();
        }
        if (rows < 100) throw new IOException("导入词条过少: " + rows);
    }

    private static void importCedict(CatalogItem item, File archive, File dbFile,
                                     Callback callback) throws Exception {
        createDb(dbFile, item);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        SQLiteStatement insert = db.compileStatement("INSERT INTO entries(word,display_word,content,content_type) VALUES(?,?,?,?)");
        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive), 128 * 1024)), StandardCharsets.UTF_8))) {
            db.beginTransaction();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    int first = line.indexOf(' ');
                    int bracket = line.indexOf(" [", first + 1);
                    int close = bracket < 0 ? -1 : line.indexOf("] ", bracket + 2);
                    if (first <= 0 || bracket <= first || close <= bracket) continue;
                    String trad = line.substring(0, first).trim();
                    String simp = line.substring(first + 1, bracket).trim();
                    String pinyin = line.substring(bracket + 2, close).trim();
                    String defs = line.substring(close + 2).trim();
                    if (defs.startsWith("/")) defs = defs.substring(1);
                    if (defs.endsWith("/")) defs = defs.substring(0, defs.length() - 1);
                    defs = defs.replace("/", "\n");
                    String display = simp.equals(trad) ? simp : simp + "（" + trad + "）";
                    String content = (pinyin.isBlank() ? "" : "[" + pinyin + "]\n") + defs;
                    bindCedict(insert, simp, display, content);
                    rows++;
                    if (!trad.equals(simp)) {
                        bindCedict(insert, trad, display, content);
                        rows++;
                    }
                    if ((rows & 4095) == 0) progress(callback, "建立 CC-CEDICT 索引", 74 + Math.min(22, rows / 12000));
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            putMeta(db, "entry_count", String.valueOf(rows));
            putMeta(db, "ready", "1");
        } finally {
            insert.close();
            db.close();
        }
        if (rows < 50_000) throw new IOException("CC-CEDICT 导入词条过少: " + rows);
    }

    private static void bindCedict(SQLiteStatement insert, String word, String display, String content) {
        insert.clearBindings();
        insert.bindString(1, word);
        insert.bindString(2, display);
        insert.bindString(3, content);
        insert.bindString(4, "cedict");
        insert.executeInsert();
    }

    private static void createDb(File file, CatalogItem item) throws IOException {
        deleteDatabaseFamily(file);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL COLLATE NOCASE, display_word TEXT, content TEXT NOT NULL, content_type TEXT)");
            db.execSQL("CREATE INDEX idx_entries_word ON entries(word COLLATE NOCASE)");
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)");
            putMeta(db, "schema", SCHEMA);
            putMeta(db, "id", item.id);
            putMeta(db, "title", item.title);
            putMeta(db, "direction", item.direction);
            putMeta(db, "license", item.license);
            putMeta(db, "source", item.homepage);
            putMeta(db, "ready", "0");
        } finally {
            db.close();
        }
    }

    private static void validateDb(File file) throws IOException {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT COUNT(*) FROM entries", null);
            if (!c.moveToFirst() || c.getLong(0) < 100) throw new IOException("词典数据库校验失败");
            c.close();
            c = db.rawQuery("PRAGMA quick_check", null);
            if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0))) throw new IOException("SQLite quick_check 失败");
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    private static String resolveDownloadUrl(CatalogItem item) throws Exception {
        if (!item.directUrl.isBlank()) return item.directUrl;
        if ("ecdict_latest_stardict".equals(item.resolver)) {
            JSONObject json = new JSONObject(httpText("https://api.github.com/repos/skywind3000/ECDICT/releases/latest"));
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.startsWith("ecdict-stardict-") && name.endsWith(".zip")) {
                        return a.optString("browser_download_url", "");
                    }
                }
            }
        }
        if ("freedict_eng_zho".equals(item.resolver)) {
            JSONArray root = new JSONArray(httpText("https://freedict.org/freedict-database.json"));
            for (int i = 0; i < root.length(); i++) {
                JSONObject d = root.optJSONObject(i);
                if (d == null || !"eng-zho".equals(d.optString("name"))) continue;
                JSONArray releases = d.optJSONArray("releases");
                if (releases == null) break;
                ArrayList<JSONObject> candidates = new ArrayList<>();
                for (int j = 0; j < releases.length(); j++) {
                    JSONObject r = releases.optJSONObject(j);
                    if (r != null && "stardict".equalsIgnoreCase(r.optString("platform"))) candidates.add(r);
                }
                candidates.sort(Comparator.comparing(o -> o.optString("version", "")));
                if (!candidates.isEmpty()) return candidates.get(candidates.size() - 1).optString("URL", "");
            }
        }
        return "";
    }

    private static void download(String url, File output, Callback callback) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "FloatLens/1 DictionaryLibrary");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("下载 HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[128 * 1024];
                long done = 0;
                int last = -1;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    out.write(buffer, 0, n);
                    done += n;
                    int p = total > 0 ? 4 + (int) Math.min(66L, done * 66L / total) : 4;
                    if (p != last) {
                        last = p;
                        progress(callback, "下载词典", p);
                    }
                }
            }
            if (output.length() < 1024) throw new IOException("下载文件过小");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpText(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
            conn.setRequestProperty("User-Agent", "FloatLens/1 DictionaryLibrary");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    if (n > 0) out.write(b, 0, n);
                    if (out.size() > 8 * 1024 * 1024) throw new IOException("元数据响应过大");
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void extractZip(File input, File dir) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(input), 128 * 1024))) {
            ZipEntry e;
            byte[] buffer = new byte[64 * 1024];
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName();
                if (!wanted(name)) continue;
                File out = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buffer)) >= 0) if (n > 0) fos.write(buffer, 0, n);
                }
            }
        }
    }

    private static void extractTarXz(File input, File dir) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(input), 128 * 1024);
             XZInputStream xz = new XZInputStream(raw)) {
            byte[] header = new byte[512];
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                int got = readFullyOrEof(xz, header);
                if (got == 0) break;
                if (got != 512) throw new IOException("损坏的 tar 头");
                if (allZero(header)) break;
                String path = tarString(header, 0, 100);
                long size = tarOctal(header, 124, 12);
                int type = header[156] & 0xFF;
                String name = new File(path).getName();
                boolean keep = (type == 0 || type == '0') && wanted(name);
                FileOutputStream fos = keep ? new FileOutputStream(new File(dir, name)) : null;
                try {
                    long remaining = size;
                    while (remaining > 0) {
                        int n = xz.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (n < 0) throw new IOException("tar 提前结束");
                        if (fos != null) fos.write(buffer, 0, n);
                        remaining -= n;
                    }
                } finally {
                    if (fos != null) fos.close();
                }
                long padding = (512 - (size % 512)) % 512;
                while (padding > 0) {
                    long skipped = xz.skip(padding);
                    if (skipped <= 0) {
                        if (xz.read() < 0) throw new IOException("tar padding 提前结束");
                        skipped = 1;
                    }
                    padding -= skipped;
                }
            }
        }
    }

    private static boolean wanted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ifo") || n.endsWith(".idx") || n.endsWith(".idx.gz")
                || n.endsWith(".dict") || n.endsWith(".dict.dz");
    }

    private static File findBySuffix(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        String s = suffix.toLowerCase(Locale.ROOT);
        for (File f : files) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(s)) return f;
        return null;
    }

    private static Map<String, String> readIfo(File ifo) throws IOException {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(ifo), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static String decodeStarDictContent(byte[] bytes, String sequence) {
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == 0) length--;
        int start = 0;
        if (sequence.isBlank() && length > 1) {
            int type = bytes[0] & 0xFF;
            if ((type >= 'a' && type <= 'z') || (type >= 'A' && type <= 'Z')) start = 1;
        }
        return new String(bytes, start, Math.max(0, length - start), StandardCharsets.UTF_8).trim();
    }

    private static final class CString {
        final String value;
        final int bytes;
        CString(String value, int bytes) { this.value = value; this.bytes = bytes; }
    }

    private static CString readCString(DataInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int count = 0;
        while (true) {
            int b;
            try { b = in.readUnsignedByte(); }
            catch (java.io.EOFException eof) { return count == 0 ? null : new CString(out.toString(StandardCharsets.UTF_8), count); }
            if (b == 0) break;
            out.write(b);
            count++;
            if (count > 64 * 1024) throw new IOException("StarDict 索引词条异常");
        }
        return new CString(out.toString(StandardCharsets.UTF_8), count);
    }

    private static void gunzip(File input, File output) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(input), 128 * 1024));
             FileOutputStream out = new FileOutputStream(output)) {
            byte[] b = new byte[128 * 1024];
            int n;
            while ((n = in.read(b)) >= 0) if (n > 0) out.write(b, 0, n);
        }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", new Object[]{key, safe(value)});
    }

    private static File rootDir(Context context) { return new File(context.getFilesDir(), DIR); }
    private static File dbFile(Context context, String id) { return new File(rootDir(context), id + ".db"); }
    private static SharedPreferences prefs(Context context) { return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static void deleteDatabaseFamily(File file) {
        if (file == null) return;
        file.delete();
        new File(file.getAbsolutePath() + "-wal").delete();
        new File(file.getAbsolutePath() + "-shm").delete();
        new File(file.getAbsolutePath() + "-journal").delete();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private static int readFullyOrEof(InputStream in, byte[] data) throws IOException {
        int pos = 0;
        while (pos < data.length) {
            int n = in.read(data, pos, data.length - pos);
            if (n < 0) break;
            if (n > 0) pos += n;
        }
        return pos;
    }

    private static boolean allZero(byte[] data) {
        for (byte b : data) if (b != 0) return false;
        return true;
    }

    private static String tarString(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long tarOctal(byte[] h, int off, int len) {
        long value = 0;
        int end = off + len;
        int i = off;
        while (i < end && (h[i] == 0 || h[i] == ' ')) i++;
        while (i < end && h[i] >= '0' && h[i] <= '7') {
            value = (value << 3) + (h[i] - '0');
            i++;
        }
        return value;
    }

    private static String friendly(Throwable t) {
        String m = t == null ? "未知错误" : t.getMessage();
        return m == null || m.isBlank() ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : m;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static void progress(Callback cb, String stage, int percent) {
        if (cb == null) return;
        MAIN.post(() -> cb.onProgress(stage, Math.max(0, Math.min(100, percent))));
    }
    private static void success(Callback cb) { if (cb != null) MAIN.post(cb::onSuccess); }
    private static void failure(Callback cb, String message) { if (cb != null) MAIN.post(() -> cb.onFailure(message)); }

    private DictionaryLibraryManager() {}
}
