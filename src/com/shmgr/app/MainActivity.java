package com.shmgr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    private TextView pathView;
    private ListView fileListView;
    private String currentPath = "/sdcard";
    private List<Map<String, Object>> fileItems;
    private List<SFile> currentFiles;
    private Set<String> bookmarks;
    private List<String> execHistory;
    private Handler handler;
    private SharedPreferences prefs;

    private static class SFile {
        String name, fullPath, permissions;
        boolean isDir;
        long size;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("shmgr_prefs", MODE_PRIVATE);
        bookmarks = new HashSet<String>(prefs.getStringSet("bookmarks", new HashSet<String>()));
        execHistory = new ArrayList<String>();
        loadHistory();
        createUI();
        navigateTo(currentPath);
    }

    private void createUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1a1a2e"));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(12, 32, 12, 12);
        toolbar.setBackgroundColor(Color.parseColor("#16213e"));

        int dp4 = dp(4);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(52), dp(36));
        btnParams.setMargins(dp4, 0, dp4, 0);

        Button btnBack = mkBtn("←"); btnBack.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { goParent(); } }); toolbar.addView(btnBack, btnParams);
        Button btnRoot = mkBtn("/"); btnRoot.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { navigateTo("/"); } }); toolbar.addView(btnRoot, btnParams);
        Button btnSd = mkBtn("SD"); btnSd.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { navigateTo("/sdcard"); } }); toolbar.addView(btnSd, btnParams);
        Button btnData = mkBtn("DATA"); btnData.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { navigateTo("/data"); } }); toolbar.addView(btnData, btnParams);
        Button btnStar = mkBtn("★"); btnStar.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showBookmarks(); } }); toolbar.addView(btnStar, btnParams);
        root.addView(toolbar);

        pathView = new TextView(this);
        pathView.setTextColor(Color.parseColor("#e94560"));
        pathView.setTextSize(11);
        pathView.setPadding(16, 8, 16, 8);
        pathView.setBackgroundColor(Color.parseColor("#0f3460"));
        pathView.setTypeface(Typeface.MONOSPACE);
        root.addView(pathView);

        LinearLayout funcBar = new LinearLayout(this);
        funcBar.setOrientation(LinearLayout.HORIZONTAL);
        funcBar.setPadding(8, 4, 8, 4);

        Button btnNew = mkFuncBtn("+新建"); btnNew.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { createNewScript(); } }); funcBar.addView(btnNew);
        Button btnHist = mkFuncBtn("历史"); btnHist.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showHistory(); } }); funcBar.addView(btnHist);
        Button btnProps = mkFuncBtn("属性"); btnProps.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showDirProps(); } }); funcBar.addView(btnProps);
        Button btnRefresh = mkFuncBtn("刷新"); btnRefresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { refresh(); } }); funcBar.addView(btnRefresh);
        root.addView(funcBar);

        fileListView = new ListView(this);
        fileListView.setBackgroundColor(Color.parseColor("#1a1a2e"));
        fileListView.setDividerHeight(1);
        fileListView.setDivider(new ColorDrawable(Color.parseColor("#0f3460")));

        fileListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < currentFiles.size()) {
                    SFile f = currentFiles.get(pos);
                    if (f.isDir) navigateTo(f.fullPath);
                    else confirmExecute(f);
                }
            }
        });
        fileListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < currentFiles.size()) showFileOptions(currentFiles.get(pos));
                return true;
            }
        });

        root.addView(fileListView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    // ================== 导航（File API 优先，失败则 su） ==================

    private void navigateTo(final String path) {
        currentPath = path;
        pathView.setText(path);
        // 先用 Java File API 尝试
        new Thread(new Runnable() {
            public void run() {
                List<SFile> files = listByFileApi(path);
                if (files != null && !files.isEmpty()) {
                    final List<SFile> result = files;
                    handler.post(new Runnable() {
                        public void run() { showFiles(path, result); }
                    });
                } else {
                    // File API 失败，用 su 兜底
                    String lsOutput = suExec("ls -la " + path + "/ 2>/dev/null");
                    final List<SFile> files2 = parseLsOutput(lsOutput, path);
                    handler.post(new Runnable() {
                        public void run() { showFiles(path, files2); }
                    });
                }
            }
        }).start();
    }

    // Java File API 列表
    private List<SFile> listByFileApi(String path) {
        try {
            java.io.File dir = new java.io.File(path);
            java.io.File[] files = dir.listFiles();
            if (files == null) return null;
            List<SFile> result = new ArrayList<SFile>();
            for (java.io.File f : files) {
                SFile sf = new SFile();
                sf.name = f.getName();
                sf.fullPath = f.getAbsolutePath();
                sf.isDir = f.isDirectory();
                sf.size = f.length();
                sf.permissions = f.isDirectory() ? "d---------" : "----------";
                result.add(sf);
            }
            if (result.isEmpty()) return null; // 空目录也是"失败"，走 su fallback
            Collections.sort(result, new Comparator<SFile>() {
                public int compare(SFile a, SFile b) {
                    if (a.isDir && !b.isDir) return -1;
                    if (!a.isDir && b.isDir) return 1;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    // su ls 解析（按 split 字段索引：fields[0]=perms, [7]=YYYY-MM-DD, [8+]=name）
    private List<SFile> parseLsOutput(String lsOutput, String path) {
        List<SFile> result = new ArrayList<SFile>();
        if (lsOutput == null || lsOutput.trim().isEmpty()) return result;
        String[] lines = lsOutput.split("\n");
        for (String line : lines) {
            if (line.startsWith("total ")) continue;
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            // 格式: perms links user group size mon day time/year name...
            // 最少需要 parts[0]..parts[7] (8个字段)
            if (parts.length < 8) continue;
            String perms = parts[0];
            boolean isDir = perms.startsWith("d") || perms.startsWith("l");

            // parts[5] 是月份或年份，判断名称起始索引
            // "Nov  9" -> parts[5]="Nov", parts[6]="9", parts[7]="2023"或"12:34" -> nameStart=8
            // "1971-02-15" -> parts[5]="1971-02-15", parts[6]="10:59" -> nameStart=7
            int nameStart = 8;
            if (parts[5].contains("-")) {
                // YYYY-MM-DD 格式：日期占了 parts[5]，时间占了 parts[6]，名字从 parts[7] 开始
                nameStart = 7;
            }

            if (parts.length <= nameStart) continue;

            StringBuilder nameBuilder = new StringBuilder();
            for (int i = nameStart; i < parts.length; i++) {
                if (i > nameStart) nameBuilder.append(" ");
                nameBuilder.append(parts[i]);
            }
            String name = nameBuilder.toString();

            // 去除箭头后的链接目标 ("adb_keys -> /product/..." -> "adb_keys")
            int arrowIdx = name.indexOf(" ->");
            if (arrowIdx > 0) name = name.substring(0, arrowIdx).trim();

            if (name.equals(".") || name.equals("..")) continue;
            if (name.isEmpty()) continue;

            SFile sf = new SFile();
            sf.name = name;
            sf.fullPath = path.equals("/") ? "/" + name : path + "/" + name;
            sf.isDir = isDir;
            sf.permissions = perms;
            try { sf.size = Long.parseLong(parts[4]); } catch (Exception e) { sf.size = 0; }

            result.add(sf);
        }

        Collections.sort(result, new Comparator<SFile>() {
            public int compare(SFile a, SFile b) {
                if (a.isDir && !b.isDir) return -1;
                if (!a.isDir && b.isDir) return 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    private void showFiles(String path, List<SFile> files) {
        currentFiles = files;
        fileItems = new ArrayList<Map<String, Object>>();
        for (SFile f : files) {
            Map<String, Object> item = new HashMap<String, Object>();
            String prefix = f.isDir ? "📁 " : (f.name.endsWith(".sh") ? "📄 " : "📋 ");
            item.put("name", prefix + f.name);
            item.put("info", formatFileInfo(f));
            fileItems.add(item);
        }
        if (currentFiles.isEmpty()) {
            showEmpty();
        } else {
            fileListView.setAdapter(new SimpleAdapter(this, fileItems,
                android.R.layout.simple_list_item_2,
                new String[]{"name", "info"},
                new int[]{android.R.id.text1, android.R.id.text2}) {
                @Override
                public View getView(int pos, View cv, ViewGroup parent) {
                    View v = super.getView(pos, cv, parent);
                    ((TextView) v.findViewById(android.R.id.text1)).setTextColor(Color.parseColor("#e0e0e0"));
                    ((TextView) v.findViewById(android.R.id.text2)).setTextColor(Color.parseColor("#888888"));
                    return v;
                }
            });
        }
    }

    private void showEmpty() {
        currentFiles = new ArrayList<SFile>();
        fileItems = new ArrayList<Map<String, Object>>();
        Map<String, Object> empty = new HashMap<String, Object>();
        empty.put("name", "📭 此目录为空或无法访问");
        empty.put("info", "");
        fileItems.add(empty);
        fileListView.setAdapter(new SimpleAdapter(this, fileItems,
                android.R.layout.simple_list_item_2,
                new String[]{"name", "info"},
                new int[]{android.R.id.text1, android.R.id.text2}));
    }

    // ================== su 执行 ==================

    private String suExec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("/system/bin/su");
            OutputStreamWriter writer = new OutputStreamWriter(p.getOutputStream(), "UTF-8");
            writer.write(cmd + "\n");
            writer.write("echo __SHDONE__\n");
            writer.write("exit\n");
            writer.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            while ((line = r.readLine()) != null) {
                if (line.contains("__SHDONE__")) break;
                sb.append(line).append("\n");
                if (System.currentTimeMillis() - start > 30000) break;
            }
            r.close(); writer.close();
            p.waitFor(); p.destroy();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void suAsync(final String cmd, final ResultCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                final String result = suExec(cmd);
                handler.post(new Runnable() { public void run() { cb.onResult(result); } });
            }
        }).start();
    }

    private interface ResultCallback { void onResult(String result); }

    // ================== 导航辅助 ==================

    private void goParent() {
        if (currentPath == null || currentPath.equals("/")) return;
        String parent = currentPath.substring(0, currentPath.lastIndexOf("/"));
        if (parent.isEmpty()) parent = "/";
        navigateTo(parent);
    }

    private void refresh() { navigateTo(currentPath); }

    // ================== 脚本执行 ==================

    private void confirmExecute(final SFile script) {
        new AlertDialog.Builder(this)
            .setTitle("执行脚本")
            .setMessage("以 Root 权限执行？\n\n" + script.fullPath)
            .setPositiveButton("静默执行", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { executeScript(script, true); }
            })
            .setNeutralButton("前台执行", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { executeScript(script, false); }
            })
            .setNegativeButton("取消", null).show();
    }

    private void executeScript(final SFile script, final boolean silent) {
        addHistory(script.fullPath);
        final String parent = getParentPath(script.fullPath);
        suAsync("cd " + parent + " && sh " + script.fullPath + " 2>&1", new ResultCallback() {
            public void onResult(String result) {
                String out = result == null ? "" : result.trim();
                if (silent && out.isEmpty()) {
                    Toast.makeText(MainActivity.this, "✅ 执行完成（无输出）", Toast.LENGTH_SHORT).show();
                } else {
                    showOutputDialog(script.name, out.isEmpty() ? "执行完毕，无输出" : result);
                }
            }
        });
    }

    private String getParentPath(String p) {
        int idx = p.lastIndexOf("/");
        return idx <= 0 ? "/" : p.substring(0, idx);
    }

    private void showOutputDialog(String title, String output) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("输出: " + title);
        final EditText editText = new EditText(this);
        String text = output.length() > 20000 ? output.substring(0, 20000) + "\n...截断" : output;
        editText.setText(text);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMinLines(8); editText.setMaxLines(20);
        editText.setGravity(Gravity.TOP);
        editText.setBackgroundColor(Color.parseColor("#0d1117"));
        editText.setTextColor(Color.parseColor("#c9d1d9"));
        editText.setTypeface(Typeface.MONOSPACE);
        editText.setPadding(12, 12, 12, 12);
        editText.setVerticalScrollBarEnabled(true);
        builder.setView(editText);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("复制", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("out", output));
                Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
    }

    // ================== 文件选项 ==================

    private void showFileOptions(final SFile file) {
        final String[] items;
        if (file.isDir) {
            items = new String[]{"设为书签", "查看属性", "在此目录新建脚本", "复制路径"};
        } else {
            items = new String[]{"执行脚本", "查看内容", "查看属性", "复制路径"};
        }
        new AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    switch (w) {
                        case 0:
                            if (file.isDir) addBookmark(file.fullPath);
                            else confirmExecute(file);
                            break;
                        case 1:
                            if (file.isDir) showProps(file);
                            else viewContent(file);
                            break;
                        case 2:
                            if (file.isDir) createScriptInDir(file.fullPath);
                            else showProps(file);
                            break;
                        case 3:
                            copyText(file.fullPath);
                            Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                            break;
                    }
                }
            }).show();
    }

    private void viewContent(final SFile file) {
        suAsync("cat " + file.fullPath + " 2>/dev/null | head -500", new ResultCallback() {
            public void onResult(String result) { showOutputDialog(file.name, result == null ? "" : result); }
        });
    }

    private void showProps(SFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("路径: ").append(file.fullPath).append("\n");
        sb.append("类型: ").append(file.isDir ? "目录" : "文件").append("\n");
        sb.append("权限: ").append(file.permissions).append("\n");
        sb.append("大小: ").append(formatSize(file.size));
        new AlertDialog.Builder(this)
            .setTitle(file.name).setMessage(sb.toString())
            .setPositiveButton("确定", null).show();
    }

    private void showDirProps() {
        suAsync("ls -lad " + currentPath + "/ 2>/dev/null", new ResultCallback() {
            public void onResult(String result) {
                if (result == null || result.trim().isEmpty()) { toast("无法获取属性"); return; }
                String[] parts = result.trim().split("\\s+");
                if (parts.length < 6) { toast("无法解析"); return; }
                SFile sf = new SFile();
                sf.name = lastSegment(currentPath);
                sf.fullPath = currentPath; sf.isDir = true;
                sf.permissions = parts[0];
                try { sf.size = Long.parseLong(parts[4]); } catch (Exception e) { sf.size = 0; }
                showProps(sf);
            }
        });
    }

    private void createNewScript() { if (currentPath != null) createScriptInDir(currentPath); }

    private void createScriptInDir(final String dirPath) {
        final EditText input = new EditText(this);
        input.setHint("脚本名（自动加 .sh）");
        input.setTextColor(Color.BLACK);
        new AlertDialog.Builder(this)
            .setTitle("新建脚本 @ " + (dirPath.length() > 30 ? "..." + dirPath.substring(dirPath.length() - 30) : dirPath))
            .setView(input)
            .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) { toast("名称不能为空"); return; }
                    if (!name.endsWith(".sh")) name += ".sh";
                    String full = dirPath.equals("/") ? "/" + name : dirPath + "/" + name;
                    suAsync("touch " + full + " && chmod 755 " + full + " && echo OK", new ResultCallback() {
                        public void onResult(String result) {
                            if (result != null && result.contains("OK")) { toast("已创建"); navigateTo(dirPath); }
                            else toast("创建失败");
                        }
                    });
                }
            }).setNegativeButton("取消", null).show();
    }

    // ================== 书签/历史 ==================

    private void addBookmark(String path) { bookmarks.add(path); prefs.edit().putStringSet("bookmarks", bookmarks).apply(); toast("已添加书签"); }
    private void showBookmarks() {
        if (bookmarks.isEmpty()) { toast("暂无书签"); return; }
        final String[] items = bookmarks.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("书签").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { navigateTo(items[w]); }
        }).setNegativeButton("清空", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { bookmarks.clear(); prefs.edit().putStringSet("bookmarks", bookmarks).apply(); toast("已清空"); }
        }).show();
    }

    private void loadHistory() { String s = prefs.getString("history", ""); if(s != null && !s.isEmpty()) for(String line : s.split("\n")) execHistory.add(line); }
    private void saveHistory() { StringBuilder sb = new StringBuilder(); int start = Math.max(0, execHistory.size() - 50); for(int i = start; i < execHistory.size(); i++) sb.append(execHistory.get(i)).append("\n"); prefs.edit().putString("history", sb.toString()).apply(); }
    private void addHistory(String path) { execHistory.add(new SimpleDateFormat("MM-dd HH:mm").format(new Date()) + " | " + path); saveHistory(); }
    private void showHistory() {
        if(execHistory.isEmpty()) { toast("暂无历史"); return; }
        final String[] items = new String[execHistory.size()];
        for(int i = 0; i < execHistory.size(); i++) items[items.length-1-i] = execHistory.get(i);
        new AlertDialog.Builder(this).setTitle("执行历史").setItems(items, null)
            .setPositiveButton("关闭", null)
            .setNegativeButton("清空", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { execHistory.clear(); saveHistory(); toast("已清空"); }
            }).show();
    }

    // ================== 工具 ==================

    private Button mkBtn(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(11); b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.parseColor("#0f3460")); b.setPadding(2,2,2,2); return b; }
    private Button mkFuncBtn(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(10); b.setTextColor(Color.parseColor("#e0e0e0")); b.setBackgroundColor(Color.parseColor("#533483")); b.setPadding(6,2,6,2); b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))); return b; }
    private String formatFileInfo(SFile f) { if(f.isDir) return "📁 " + (f.permissions != null ? f.permissions : ""); return formatSize(f.size) + " | " + (f.permissions != null ? f.permissions : ""); }
    private String formatSize(long bytes) { if(bytes < 1024) return bytes + " B"; if(bytes < 1024*1024) return String.format("%.1f KB", bytes/1024.0); if(bytes < 1024*1024*1024) return String.format("%.1f MB", bytes/(1024.0*1024)); return String.format("%.2f GB", bytes/(1024.0*1024*1024)); }
    private int dp(int px) { return (int)(px * getResources().getDisplayMetrics().density); }
    private void copyText(String s) { ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("t", s)); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private String lastSegment(String p) { int idx = p.lastIndexOf("/"); return idx <= 0 || idx + 1 >= p.length() ? p : p.substring(idx + 1); }
}
