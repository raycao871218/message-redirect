package com.nas.messageredirect;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.text.DateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Status, saved configuration cards and first-run setup; SMS stay in the private queue. */
public final class MainActivity extends Activity {
    private static final int INK = 0xff3b3a49, MUTED = 0xff706b75, BLUE = 0xff4d669d, BG = 0xfffcf6e8;
    private static final int PAPER = 0xfffffcf4, EDGE = 0xff8a8a9a, WAX_BLUE = 0xffbacceb;
    private static final int EMAIL_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private MascotView brandMascot;
    private TextView brandCaption;
    private TextView notice, homeState, homeCounts, homeDetails, permissionStatus, numberStatus;
    private boolean registered, wizard, editing, profilesPage, phoneEditing, aboutPage, turnOn;
    private int step, sslId;
    private String mode, phone1, phone2, url, token, host, port, security, username, password = "", from, to, profileId = "", profileName = "";
    private EditText profileNameInput;
    private CrayonDialog activeDialog;
    private EditText phone1Input, phone2Input, urlInput, tokenInput, hostInput, portInput, usernameInput, passwordInput, fromInput, toInput;
    private RadioGroup securityInput;
    private CheckBox enableInput;
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); loadDraft();
        if (saved != null) {
            step = saved.getInt("step"); mode = saved.getString("mode", mode);
            phone1 = saved.getString("phone1", phone1); phone2 = saved.getString("phone2", phone2); url = saved.getString("url", url);
            host = saved.getString("host", host); port = saved.getString("port", port); security = saved.getString("security", security);
            username = saved.getString("username", username); from = saved.getString("from", from); to = saved.getString("to", to); turnOn = saved.getBoolean("turnOn", turnOn);
            profileId = saved.getString("profileId", ""); profileName = saved.getString("profileName", "");
        }
        wizard = saved != null ? saved.getBoolean("wizard") : !new SyncConfig(this).setupComplete();
        editing = saved != null && saved.getBoolean("editing");
        profilesPage = saved != null && saved.getBoolean("profilesPage"); phoneEditing = saved != null && saved.getBoolean("phoneEditing");
        // Restoring an older settings form should land on cards, not reopen the form.
        if ((wizard && new SyncConfig(this).setupComplete()) || (editing && saved != null && !saved.containsKey("profileId"))) { wizard = false; editing = false; profilesPage = true; }
        if (saved != null && saved.getBoolean("aboutPage")) showAbout();
        else if (editing) { if (phoneEditing) showPhoneEditor(); else showProfileEditor(); }
        else if (profilesPage) showSettings(); else if (wizard) showStep(); else showHome();
    }
    private void loadDraft() {
        SyncConfig c = new SyncConfig(this); mode = c.url().isEmpty() && c.prefs.getString("smtp_host", "").isEmpty() ? SyncConfig.EMAIL : c.mode(); phone1 = c.phone(0); phone2 = c.phone(1); url = c.url(); token = c.token();
        host = c.prefs.getString("smtp_host", ""); port = String.valueOf(c.prefs.getInt("smtp_port", 465)); security = c.prefs.getString("smtp_security", "ssl");
        username = c.prefs.getString("smtp_username", ""); from = c.prefs.getString("smtp_from", ""); to = c.prefs.getString("smtp_to", "");
        if (from.equals(username)) from = "";
        password = ""; turnOn = c.setupComplete() ? c.enabled() : true;
    }
    private void rememberInputs() {
        if (!wizard && !editing) return;
        if ((editing && phoneEditing) || (wizard && step == 1)) { phone1 = value(phone1Input); phone2 = value(phone2Input); }
        if ((editing && !phoneEditing) || (wizard && step == 2)) {
            if (editing) profileName = value(profileNameInput);
            if (SyncConfig.EMAIL.equals(mode)) {
                host = value(hostInput); port = value(portInput); username = value(usernameInput); password = passwordInput.getText().toString();
                from = value(fromInput); to = value(toInput); security = securityInput.getCheckedRadioButtonId() == sslId ? "ssl" : "starttls";
            } else { url = value(urlInput); token = value(tokenInput); }
        }
        if (wizard && step == 3) turnOn = enableInput.isChecked();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        rememberInputs(); state.putBoolean("wizard", wizard); state.putBoolean("editing", editing); state.putInt("step", step); state.putString("mode", mode);
        state.putBoolean("profilesPage", profilesPage); state.putBoolean("phoneEditing", phoneEditing); state.putBoolean("aboutPage", aboutPage); state.putString("profileId", profileId); state.putString("profileName", profileName);
        state.putString("phone1", phone1); state.putString("phone2", phone2); state.putString("url", url);
        state.putString("host", host); state.putString("port", port); state.putString("security", security);
        state.putString("username", username); state.putString("from", from); state.putString("to", to); state.putBoolean("turnOn", turnOn);
        // Do not put secret drafts in saved view/instance state.
        super.onSaveInstanceState(state);
    }
    private void page(String eyebrow, String title, String subtitle) {
        aboutPage = eyebrow.equals("ABOUT");
        permissionStatus = null;
        String previousMode = brandMascot == null ? null : brandMascot.mode();
        boolean home = eyebrow.equals("MESSAGE REDIRECT");
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackground(CrayonTexture.paper(BG, getResources().getDisplayMetrics().density));
        if (Build.VERSION.SDK_INT >= 29) scroll.setEdgeEffectColor(WAX_BLUE);
        root = column(); root.setPadding(dp(20), dp(12), dp(20), dp(24)); scroll.addView(root); setContentView(scroll);
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        brandMascot = new MascotView(this); int mascotSize = home ? 92 : 64;
        brand.addView(brandMascot, new LinearLayout.LayoutParams(dp(mascotSize), dp(mascotSize)));
        LinearLayout brandText = column(); brandText.setPadding(dp(5), 0, 0, 0);
        TextView name = label(brandText, getString(R.string.app_name), home ? 22 : 20, INK); name.setTypeface(null, Typeface.BOLD);
        brandCaption = label(brandText, "", 12, MUTED); brand.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(brand); brand.setVisibility(aboutPage ? View.GONE : View.VISIBLE); space(root, 12);
        updateMascot(previousMode != null && !previousMode.equals(mascotMode()));
        if (!home) {
            if (eyebrow.startsWith("设置向导")) label(root, eyebrow, 12, BLUE);
            label(root, title, 24, INK).setTypeface(null, Typeface.BOLD);
            root.addView(new CrayonAccent(this), new LinearLayout.LayoutParams(dp(80), dp(10)));
            if (!subtitle.isEmpty()) label(root, subtitle, 13, MUTED);
            space(root, 12);
        }
        notice = text("", 14, 0xffa73934); notice.setVisibility(View.GONE);
    }
    private String mascotMode() { return wizard || (editing && !phoneEditing) ? mode : new SyncConfig(this).mode(); }
    private void updateMascot(boolean animate) {
        String selected = mascotMode(); brandMascot.setMode(selected, animate);
        brandCaption.setText(SyncConfig.DATABASE.equals(selected) ? "赛博信使" : "你的小信使");
    }
    private void showStep() {
        if (new SyncConfig(this).setupComplete()) { showSettings(); return; }
        wizard = true; editing = false; profilesPage = false;
        boolean mail = SyncConfig.EMAIL.equals(mode);
        String[] titles = {"短信，送到你需要的地方", "标记接收短信的号码", mail ? "连接你的邮箱" : "连接自建数据库", "准备好接收新短信"};
        page("设置向导  ·  " + (step + 1) + " / 4", titles[step], "");
        LinearLayout progress = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            View segment = new View(this); segment.setBackground(sketch(i <= step ? WAX_BLUE : 0xffe5e0d7, i <= step ? BLUE : 0xffc6c0b5, .7f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(10), 1); lp.setMargins(0, 0, dp(5), 0); progress.addView(segment, lp);
        }
        root.addView(progress); space(root, 22);
        if (step == 0) methodStep(); else if (step == 1) numberStep(); else if (step == 2) { if (mail) emailStep(); else databaseStep(); } else reviewStep();
        root.addView(notice); space(root, 14);
        button(root, step == 3 ? "完成设置" : "下一步", true, view -> next());
        if (step > 0) button(root, "上一步", false, view -> { rememberInputs(); step--; showStep(); });
        if (new SyncConfig(this).setupComplete() || new SyncConfig(this).enabled()) button(root, "取消修改，返回首页", false, view -> { password = ""; showHome(); });
    }
    private void showSettings() {
        wizard = false; editing = false; phoneEditing = false; profilesPage = true; password = "";
        page("CONFIGURATIONS", "转发配置", "点卡片切换使用的配置。");
        SyncConfig config = new SyncConfig(this); ProfileStore profiles = new ProfileStore(config);
        try {
            profiles.migrate();
            if (profiles.list().isEmpty()) label(root, "还没有保存的配置，点击下方新增。", 15, MUTED);
            for (ProfileStore.Profile profile : profiles.list()) {
                boolean selected = profile.id().equals(profiles.activeId());
                LinearLayout item = card(root);
                item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18677ea9), sketch(selected ? 0xfffff9e9 : PAPER, selected ? BLUE : EDGE, selected ? 2.8f : 1.5f), null));
                TextView badge = label(item, selected ? "● 当前使用" : "点选使用", 12, selected ? INK : MUTED);
                if (selected) { badge.setBackground(sketch(0xfff0c8aa, 0xffc48e71, .8f)); badge.setPadding(dp(12), dp(7), dp(12), dp(7)); LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(-2, -2); badgeLayout.bottomMargin = dp(8); badge.setLayoutParams(badgeLayout); }
                LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL); item.addView(heading);
                TextView profileTitle = label(heading, profile.name(), 18, INK); profileTitle.setTypeface(null, Typeface.BOLD); profileTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                Button edit = button(heading, "编辑配置", false, view -> editProfile(profile.id())); edit.setMinHeight(dp(48)); edit.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams editLayout = new LinearLayout.LayoutParams(dp(88), -2); editLayout.leftMargin = dp(8); edit.setLayoutParams(editLayout);
                String account = profile.get("smtp_username");
                label(item, SyncConfig.EMAIL.equals(profile.mode()) ? (account.equals(profile.target()) ? "发件与收件：" + account : "发件：" + account + "\n收件：" + profile.target()) : "服务：" + profile.target(), 13, MUTED);
                item.setContentDescription("选择配置：" + profile.name()); item.setClickable(true);
                item.setOnClickListener(view -> selectProfile(profile.id()));
            }
        } catch (IllegalArgumentException error) { notice.setText(error.getMessage()); notice.setVisibility(View.VISIBLE); }
        LinearLayout actions = new LinearLayout(this); root.addView(actions);
        Button add = button(actions, "＋ 新增配置", true, view -> showProfileTypes());
        Button numbers = button(actions, "接收手机号", false, view -> { loadDraft(); showPhoneEditor(); });
        LinearLayout.LayoutParams addLayout = new LinearLayout.LayoutParams(0, -2, 1); addLayout.rightMargin = dp(5); add.setLayoutParams(addLayout);
        LinearLayout.LayoutParams numberLayout = new LinearLayout.LayoutParams(0, -2, 1); numberLayout.leftMargin = dp(5); numbers.setLayoutParams(numberLayout);
        button(root, "返回首页", false, view -> showHome()); root.addView(notice);
    }
    private void showProfileTypes() {
        wizard = false; editing = false; profilesPage = true;
        page("NEW CONFIGURATION", "新增配置", "");
        button(root, "邮件转发", true, view -> newProfile(SyncConfig.EMAIL));
        button(root, "自建数据库", false, view -> newProfile(SyncConfig.DATABASE));
        button(root, "返回配置列表", false, view -> showSettings());
        root.addView(notice);
    }
    private void selectProfile(String id) {
        ProfileStore profiles = new ProfileStore(new SyncConfig(this));
        if (id.equals(profiles.activeId())) return;
        try { profiles.select(id); resetJobs(); showSettings(); }
        catch (IllegalArgumentException error) { say(error.getMessage()); }
    }
    private void editProfile(String id) {
        ProfileStore.Profile profile = new ProfileStore(new SyncConfig(this)).find(id);
        if (profile == null) { say("配置不存在，请刷新后重试"); return; }
        loadDraft(); profileId = id; profileName = profile.name(); mode = profile.mode();
        if (SyncConfig.EMAIL.equals(mode)) {
            host = profile.get("smtp_host"); port = String.valueOf(profile.port()); security = profile.get("smtp_security");
            username = profile.get("smtp_username"); from = profile.get("smtp_from"); to = profile.get("smtp_to");
            if (from.equals(username)) from = "";
        } else { url = profile.get("url"); token = profile.get("token"); }
        showProfileEditor();
    }
    private void newProfile(String selectedMode) {
        loadDraft(); profileId = ""; profileName = ""; mode = selectedMode;
        host = ""; port = "465"; security = "ssl"; username = ""; from = ""; to = ""; password = ""; url = ""; token = "";
        showProfileEditor();
    }
    private void showProfileEditor() {
        wizard = false; profilesPage = false; editing = true; phoneEditing = false;
        page("CONFIGURATION", profileId.isEmpty() ? "新增配置" : "编辑配置", "");
        profileNameInput = field(root, "配置名称", profileName, InputType.TYPE_CLASS_TEXT, SyncConfig.EMAIL.equals(mode) ? "例如 Gmail 转发" : "例如 家里的数据库");
        if (SyncConfig.EMAIL.equals(mode)) emailStep(); else databaseStep();
        root.addView(notice);
        button(root, "保存配置", true, view -> saveSettings());
        button(root, "取消，返回配置列表", false, view -> showSettings());
    }
    private void showPhoneEditor() {
        wizard = false; profilesPage = false; editing = true; phoneEditing = true;
        page("PHONE NUMBERS", "接收手机号", "");
        numberStep(); root.addView(notice);
        button(root, "保存号码", true, view -> saveSettings());
        button(root, "取消，返回配置列表", false, view -> showSettings());
    }
    private void saveSettings() {
        rememberInputs();
        try {
            SyncConfig config = new SyncConfig(this); ProfileStore profiles = new ProfileStore(config);
            if (phoneEditing) config.completeSetup(config.url(), config.token(), config.mode(), SyncConfig.EMAIL.equals(config.mode()) ? config.smtp() : null, phone1, phone2, config.enabled());
            else profiles.save(profileId, profileName, mode, url, token, SyncConfig.EMAIL.equals(mode) ? draftSmtp() : null);
            if (phoneEditing || profileId.equals(profiles.activeId())) resetJobs();
            password = ""; showSettings();
        } catch (IllegalArgumentException error) { say(error.getMessage()); }
    }
    private void methodStep() {
        RadioGroup options = new RadioGroup(this); root.addView(options);
        RadioButton mail = choice(options, "邮件转发", SyncConfig.EMAIL.equals(mode));
        choice(options, "自建数据库", !SyncConfig.EMAIL.equals(mode));
        TextView hint = label(root, methodHint(), 13, MUTED);
        options.setOnCheckedChangeListener((group, id) -> { mode = id == mail.getId() ? SyncConfig.EMAIL : SyncConfig.DATABASE; updateMascot(true); hint.setText(methodHint()); });
    }
    private String methodHint() { return SyncConfig.EMAIL.equals(mode) ? "用邮箱接收转发短信，无需部署服务。" : "通过自建服务保存短信，支持 SQLite、PostgreSQL、MySQL 和 MariaDB。"; }
    private void numberStep() {
        numberStep(root);
    }
    private void numberStep(LinearLayout container) {
        LinearLayout panel = card(container);
        button(panel, "从 SIM 卡读取号码", false, view -> requestPhoneNumbers());
        numberStatus = label(panel, "可授权从 SIM 卡读取；读不到时手动填写。请核对号码是否正确。", 13, MUTED);
        phone1Input = field(panel, "本机号码 / SIM 1", phone1, InputType.TYPE_CLASS_PHONE, "例如 +86 138 0000 0000");
        phone2Input = field(panel, "SIM 2 号码 · 可选", phone2, InputType.TYPE_CLASS_PHONE, "双卡手机可填写第二个号码");
        label(container, "双卡按卡槽填写。号码只标记新短信，历史短信中的号码不会补填。", 13, MUTED);
    }
    private void emailStep() {
        LinearLayout provider = card(root); label(provider, "1  选择发件邮箱服务商", 17, INK).setTypeface(null, Typeface.BOLD);
        Button picker = button(provider, SmtpPresets.matching(host).name + "  ▾", false, null);
        TextView instructions = label(provider, SmtpPresets.matching(host).hint, 14, MUTED);
        button(provider, "如何获取邮箱授权码 ↗", false, view -> openHelp(value(hostInput)));
        LinearLayout account = card(root); label(account, "2  填写发件账号", 17, INK).setTypeface(null, Typeface.BOLD);
        usernameInput = field(account, "发件邮箱 / SMTP 账号", username, EMAIL_INPUT, "完整邮箱地址");
        ProfileStore.Profile edited = editing ? new ProfileStore(new SyncConfig(this)).find(profileId) : null;
        boolean stored = editing ? edited != null && !edited.get("smtp_password_encrypted").isEmpty() : !new SyncConfig(this).prefs.getString("smtp_password_encrypted", "").isEmpty();
        passwordInput = field(account, "邮箱授权码 / 应用专用密码", password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, stored ? "已保存，留空即可保留" : "按所选邮箱的说明获取后填写");
        LinearLayout recipient = card(root); label(recipient, "3  短信转发到哪里", 17, INK).setTypeface(null, Typeface.BOLD);
        toInput = field(recipient, "收件邮箱", to, EMAIL_INPUT, "可以与发件邮箱相同，只填一个地址");
        label(recipient, "每条新短信一封邮件，标题和正文都会注明接收手机号。", 13, MUTED);
        LinearLayout advanced = card(root), advancedFields = column();
        Button advancedButton = button(advanced, "服务器与发件人设置  ＋", false, null); advanced.addView(advancedFields);
        advancedFields.setVisibility(host.isEmpty() ? View.VISIBLE : View.GONE);
        advancedButton.setOnClickListener(view -> {
            boolean show = advancedFields.getVisibility() != View.VISIBLE; advancedFields.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedButton.setText(show ? "收起服务器设置  −" : "服务器与发件人设置  ＋");
        });
        hostInput = field(advancedFields, "SMTP 服务器", host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "不含协议、端口或路径");
        portInput = field(advancedFields, "端口", port, InputType.TYPE_CLASS_NUMBER, "465 或 587");
        securityInput = new RadioGroup(this); advancedFields.addView(securityInput);
        RadioButton ssl = choice(securityInput, "SSL/TLS · 通常为 465", "ssl".equals(security)); sslId = ssl.getId();
        RadioButton tls = choice(securityInput, "STARTTLS · 通常为 587", "starttls".equals(security));
        securityInput.setOnCheckedChangeListener((group, id) -> {
            String current = value(portInput); if (current.equals("465") || current.equals("587")) portInput.setText(id == sslId ? "465" : "587");
        });
        fromInput = field(advancedFields, "发件人地址 · 留空使用账号", from, EMAIL_INPUT, "通常与发件邮箱 / SMTP 账号一致");
        picker.setOnClickListener(view -> {
            String[] names = new String[SmtpPresets.ALL.length]; for (int i = 0; i < names.length; i++) names[i] = SmtpPresets.ALL[i].name;
            dialog().setTitle("发件邮箱服务商").setItems(names, (dialog, which) -> {
                SmtpPresets.Preset preset = SmtpPresets.ALL[which];
                if (!preset.host.isEmpty()) {
                    if (!preset.host.equalsIgnoreCase(value(hostInput))) { passwordInput.setText(""); passwordInput.setHint("更换服务商后请重新填写授权码"); }
                    hostInput.setText(preset.host); securityInput.check("ssl".equals(preset.security) ? sslId : tls.getId()); portInput.setText(String.valueOf(preset.port));
                }
                picker.setText(preset.name + "  ▾"); instructions.setText(preset.hint);
                advancedFields.setVisibility(preset.host.isEmpty() ? View.VISIBLE : View.GONE);
                advancedButton.setText(preset.host.isEmpty() ? "收起服务器设置  −" : "服务器与发件人设置  ＋");
            }).setNegativeButton("取消", null).show();
        });
    }
    private void databaseStep() {
        LinearLayout panel = card(root);
        label(panel, "连接配套服务", 17, INK).setTypeface(null, Typeface.BOLD);
        label(panel, "先在自己的服务器部署配套服务，再填写下方信息。", 13, MUTED);
        urlInput = field(panel, "转存服务地址", url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "https://sms.example.com");
        tokenInput = field(panel, "服务访问令牌", token, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "与服务端 api_token 一致，至少 32 位");
        label(panel, "数据库账号和密码只填在服务端。", 13, MUTED);
    }
    private SmtpProfile draftSmtp() {
        int number; try { number = Integer.parseInt(port); } catch (NumberFormatException error) { throw new IllegalArgumentException("SMTP 端口需为 1–65535"); }
        String secret = editing ? new ProfileStore(new SyncConfig(this)).password(profileId, host, username, password) : new SyncConfig(this).smtpPassword(host, username, password, true);
        SmtpProfile smtp = new SmtpProfile(host, number, security, username, secret, from.isEmpty() ? username : from, to); smtp.validate(); return smtp;
    }
    private void next() {
        rememberInputs();
        try {
            if (step == 1) {
                phone1 = SyncConfig.normalizePhone(phone1); phone2 = SyncConfig.normalizePhone(phone2);
                if (phone1.isEmpty() && phone2.isEmpty()) throw new IllegalArgumentException("请填写接收短信的本机号码");
                if (!phone1.isEmpty() && phone1.equals(phone2)) throw new IllegalArgumentException("两张 SIM 卡请填写不同号码");
            }
            if (step == 2) {
                if (SyncConfig.EMAIL.equals(mode)) draftSmtp();
                else {
                    java.net.URI uri = new java.net.URI(url);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("请填写完整的 HTTP/HTTPS 配套服务地址");
                    if (token.length() < 32) throw new IllegalArgumentException("访问令牌至少需要 32 位，请与服务端配置核对");
                }
            }
            if (step < 3) { step++; showStep(); return; }
            if (turnOn && !allowed()) throw new IllegalArgumentException("请先授权接收短信，或取消勾选自动转发后保存");
            new SyncConfig(this).completeSetup(url, token, mode, SyncConfig.EMAIL.equals(mode) ? draftSmtp() : null, phone1, phone2, turnOn);
            password = ""; resetJobs(); showHome();
        } catch (IllegalArgumentException error) { say(error.getMessage()); }
        catch (java.net.URISyntaxException error) { say("转存服务地址格式不正确"); }
    }
    private void reviewStep() {
        LinearLayout review = card(root); label(review, SyncConfig.EMAIL.equals(mode) ? "邮件转发" : "自建数据库", 18, INK).setTypeface(null, Typeface.BOLD);
        label(review, (phone1.isEmpty() ? "" : "SIM 1  " + phone1) + (phone2.isEmpty() ? "" : (phone1.isEmpty() ? "" : "\n") + "SIM 2  " + phone2), 15, MUTED);
        label(review, SyncConfig.EMAIL.equals(mode) ? "发件邮箱  " + username + "\n收件邮箱  " + to : "转存服务  " + url, 14, MUTED);
        LinearLayout permissions = card(root); label(permissions, "接收短信权限", 17, INK).setTypeface(null, Typeface.BOLD);
        permissionStatus = label(permissions, allowed() ? "已授权，可以接收新短信" : "尚未授权，开启后才能转发新短信", 14, MUTED);
        button(permissions, "授权接收短信", false, view -> {
            if (allowed()) { permissionStatus.setText("已授权，可以接收新短信"); return; }
            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 1);
        });
        button(permissions, "打开系统权限设置", false, view -> openPermissions());
        enableInput = new CheckBox(this); enableInput.setText("完成后开启自动转发"); enableInput.setTextColor(INK); enableInput.setTextSize(15); enableInput.setChecked(turnOn); root.addView(enableInput);
        label(root, "开启后自动转发新短信，不会导入历史收件箱。若转发延迟，可在系统电池设置中允许本应用后台运行。", 13, MUTED);
    }
    private void showHome() {
        wizard = false; editing = false; profilesPage = false; phoneEditing = false; password = ""; SyncConfig c = new SyncConfig(this);
        if (c.setupComplete()) { try { new ProfileStore(c).migrate(); } catch (IllegalArgumentException ignored) { /* Cards report a retryable migration error. */ } }
        page("MESSAGE REDIRECT", "", "");
        LinearLayout state = card(root); label(state, "转发状态", 13, MUTED);
        LinearLayout statusLine = new LinearLayout(this); statusLine.setGravity(Gravity.CENTER_VERTICAL); state.addView(statusLine);
        homeState = label(statusLine, "", 23, BLUE); homeState.setTypeface(null, Typeface.BOLD); homeState.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button toggle = button(statusLine, c.enabled() ? "暂停" : "开启", !c.enabled(), view -> toggle());
        LinearLayout.LayoutParams toggleLayout = new LinearLayout.LayoutParams(dp(80), -2); toggleLayout.leftMargin = dp(8); toggle.setLayoutParams(toggleLayout);
        homeDetails = label(state, "", 13, MUTED); space(state, 4); homeCounts = label(state, "", 13, MUTED);
        ProfileStore.Profile active = new ProfileStore(c).active();
        LinearLayout destination = card(root); label(destination, "转发去向", 13, MUTED); label(destination, active == null ? c.modeLabel() : active.name(), 18, INK).setTypeface(null, Typeface.BOLD);
        label(destination, c.target().isEmpty() ? "尚未配置目标" : c.target(), 15, MUTED);
        label(destination, c.phoneSummary().isEmpty() ? "接收手机号尚未设置" : c.phoneSummary(), 14, MUTED);
        button(root, c.setupComplete() ? "管理转发配置" : "完成设置向导", true, view -> {
            loadDraft(); if (c.setupComplete()) showSettings(); else { step = 0; showStep(); }
        });
        button(root, "更多操作", false, view -> dialog().setTitle("更多操作")
            .setItems(new String[]{"重试尚未发送的短信", "检查邮件是否收到", "使用说明", "打开系统权限设置", "关于咕咕咕"}, (dialog, which) -> {
                if (which == 0) uploadNow(); else if (which == 1) resolveUncertain(); else if (which == 2) showUsage(); else if (which == 3) openPermissions(); else showAbout();
            }).setNegativeButton("关闭", null).show());
        root.addView(notice); refresh();
    }
    private void showAbout() {
        wizard = false; editing = false; profilesPage = false; phoneEditing = false; password = "";
        page("ABOUT", "关于咕咕咕", "");
        LinearLayout portrait = column(); portrait.setGravity(Gravity.CENTER); space(portrait, 12);
        MascotView pigeon = new MascotView(this); pigeon.setMode(new SyncConfig(this).mode(), false);
        portrait.addView(pigeon, new LinearLayout.LayoutParams(dp(210), dp(210)));
        TextView greeting = label(portrait, "咕咕，把消息送到。", 16, MUTED); greeting.setGravity(Gravity.CENTER);
        space(portrait, 22); root.addView(portrait);
        LinearLayout version = card(root);
        try {
            android.content.pm.PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), 0);
            long build = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            label(version, "版本号", 13, MUTED);
            label(version, installed.versionName, 24, INK).setTypeface(null, Typeface.BOLD);
            label(version, "构建号  " + build, 13, MUTED);
        } catch (PackageManager.NameNotFoundException error) {
            label(version, "暂时无法读取版本信息", 16, INK);
        }
        button(root, "返回首页", false, view -> showHome());
    }
    private void showUsage() {
        dialog().setTitle("使用说明")
            .setMessage("自动转发\n开启后自动发送新短信；暂停时，新短信会留在手机，重新开启后继续发送。不会自动导入历史收件箱。\n\n发送失败怎么办\n一般网络错误会稍后自动重试，也可以点“重试尚未发送的短信”。\n\n检查邮件是否收到\n发送时连接中断，App 可能无法判断邮件是否寄出。为避免重复，这类邮件不自动重发。请先检查收件箱和垃圾箱，再选择“已收到”或“没收到，重发”。\n\n切换配置\n点卡片切换，点“编辑配置”修改内容。已经开始发送的短信保留原收件邮箱或服务地址，切回对应配置后才能继续发送。\n\n后台运行\n若转发延迟，可在系统电池设置中允许本应用后台运行。强行停止应用后，需要重新打开。")
            .setPositiveButton("知道了", null).show();
    }
    private void toggle() {
        SyncConfig c = new SyncConfig(this);
        if (!c.enabled() && !c.setupComplete()) { loadDraft(); step = 0; showStep(); return; }
        if (!c.enabled() && !allowed()) { say("请先在系统设置中授予短信权限，再开启自动转发"); openPermissions(); return; }
        try { c.save(c.url(), c.token(), !c.enabled(), c.mode(), SyncConfig.EMAIL.equals(c.mode()) ? c.smtp() : null); resetJobs(); showHome(); }
        catch (IllegalArgumentException error) { say(error.getMessage()); }
    }
    private void resetJobs() {
        android.app.job.JobScheduler jobs = (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        jobs.cancel(UploadJobService.IMMEDIATE); jobs.cancel(UploadJobService.PERIODIC); UploadJobService.schedule(this);
    }
    private void refresh() {
        if (wizard || editing || profilesPage || aboutPage) { if (permissionStatus != null) permissionStatus.setText(allowed() ? "已授权，可以接收新短信" : "尚未授权，开启后才能转发新短信"); return; }
        worker.execute(() -> {
            try (SmsStore store = new SmsStore(this)) {
                if (DeliveryLock.LOCK.tryLock()) { try { store.recoverInFlightEmails(); } finally { DeliveryLock.LOCK.unlock(); } }
                SyncConfig c = new SyncConfig(this); long pending = store.pendingCount(), eligible = store.eligibleCount(c.mode(), c.target()), unfinishedMail = store.uncertainCount(), review = store.reviewCount();
                long sending = Math.max(0, unfinishedMail - review), other = Math.max(0, pending - eligible - unfinishedMail);
                runOnUiThread(() -> {
                    if (isDestroyed() || wizard || editing || profilesPage || aboutPage) return;
                    homeState.setText(!allowed() ? "需要短信权限" : !c.enabled() ? "已暂停" : "已开启");
                    homeDetails.setText(!allowed() ? "请到“更多操作 → 打开系统权限设置”授予短信权限。" : review > 0 ? "有邮件没能确认是否寄出。请到“更多操作 → 检查邮件是否收到”核对。" : !c.enabled() ? "新短信会保留在手机，开启后继续发送。" : c.status());
                    homeCounts.setText("等待发送：" + eligible + " 条" + (sending > 0 ? "\n正在发送邮件：" + sending + " 封" : "") + (review > 0 ? "\n请检查收件箱：" + review + " 封" : "") + (other > 0 ? "\n其他配置未发送：" + other + " 条（切回原配置后发送）" : ""));
                });
            }
        });
    }
    private void uploadNow() {
        if (!new SyncConfig(this).enabled()) { say("请先开启自动转发，再重试发送"); return; } say("正在重试当前配置下尚未发送的短信…");
        worker.execute(() -> {
            boolean retry = SmsUploader.upload(getApplicationContext()); if (retry) UploadJobService.schedule(getApplicationContext());
            runOnUiThread(() -> { if (!isDestroyed() && !wizard && !editing && !profilesPage && !aboutPage) { say(new SyncConfig(this).status()); refresh(); } });
        });
    }
    private void resolveUncertain() {
        worker.execute(() -> {
            java.util.List<SmsStore.Message> rows; try (SmsStore store = new SmsStore(this)) { rows = store.uncertain(); }
            runOnUiThread(() -> {
                if (isDestroyed() || wizard || editing || profilesPage || aboutPage) return;
                if (rows.isEmpty()) { say("目前没有需要你检查的邮件，正常转发无需手动操作。"); return; }
                SmsStore.Message row = rows.get(0);
                dialog().setTitle("这封邮件收到了吗？")
                    .setMessage("发送时连接中断，邮件可能已经寄出。为避免重复，App 暂时不会重发这封邮件。\n\n请检查以下邮箱的收件箱和垃圾箱：\n" + row.target + "\n\n短信发送号码：" + row.sender + "\n短信接收时间：" + DateFormat.getDateTimeInstance().format(new java.util.Date(row.capturedAt)) + "\n\n还需检查 " + rows.size() + " 封。已收到就标记完成；没有收到再选择重发。")
                    .setPositiveButton("已收到", (dialog, which) -> resolveEmail(row.id, false))
                    .setNeutralButton("没收到，重发", (dialog, which) -> dialog().setTitle("再发一次这封邮件？")
                        .setMessage("请先确认收件箱和垃圾箱都没有这封邮件。重发后，原收件人可能收到重复邮件。")
                        .setPositiveButton("重发到原邮箱", (d, w) -> resolveEmail(row.id, true)).setNegativeButton("先不重发", null).show())
                    .setNegativeButton("稍后检查", null).show();
            });
        });
    }
    private void resolveEmail(String id, boolean retry) {
        worker.execute(() -> {
            String outcome;
            if (!DeliveryLock.LOCK.tryLock()) outcome = "正在发送，请稍后处理";
            else {
                try (SmsStore store = new SmsStore(this)) {
                    if (retry) store.retryEmail(id); else store.markUploaded(java.util.Collections.singletonList(id));
                    outcome = retry ? "已安排重发。请保持自动转发开启，并选择原收件邮箱的配置。" : "已标记为收到，这封邮件不会再次发送。";
                    new SyncConfig(this).status(outcome);
                }
                catch (Exception error) { outcome = "记录状态已改变，请刷新后重试"; }
                finally { DeliveryLock.LOCK.unlock(); }
            }
            if (retry) UploadJobService.schedule(this); final String message = outcome;
            runOnUiThread(() -> { if (!isDestroyed() && !wizard && !editing && !profilesPage && !aboutPage) { say(message); refresh(); } });
        });
    }
    private boolean allowed() { return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED; }
    private void requestPhoneNumbers() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 26 && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_PHONE_NUMBERS);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 3); else readPhoneNumbers();
    }
    private void readPhoneNumbers() {
        if (!(editing && phoneEditing) && (!wizard || step != 1)) return;
        try {
            String[] numbers = PhoneNumbers.read(this);
            // Keep manual entries; filling SIM metadata never silently replaces a user's number.
            if (value(phone1Input).isEmpty()) phone1Input.setText(numbers[0]);
            if (value(phone2Input).isEmpty()) phone2Input.setText(numbers[1]);
            numberStatus.setText(numbers[0].isEmpty() && numbers[1].isEmpty() ? "系统未提供本机号码，请手动填写。拒绝号码权限也不影响手动设置。" : "已按卡槽读取号码并填入空白项，请核对。没有读出的号码仍可手动填写。");
        } catch (SecurityException error) { numberStatus.setText("未获得读取号码的权限，请手动填写；也可以在系统权限设置中授权。"); }
        catch (UnsupportedOperationException | IllegalStateException error) { numberStatus.setText("此设备暂时无法读取 SIM 号码，请手动填写。"); }
    }
    private void openPermissions() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void openHelp(String host) {
        String help = SmtpPresets.matching(host).help;
        if (help.isEmpty()) { say("自定义邮箱请向服务商获取 SMTP 参数和授权说明"); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(help))); } catch (android.content.ActivityNotFoundException error) { say("没有可打开网页的浏览器"); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 3) { readPhoneNumbers(); return; }
        refresh(); if (!allowed()) say("尚未授予短信权限。若系统不再弹窗，请打开系统权限设置。");
    }
    @Override protected void onResume() {
        super.onResume(); IntentFilter filter = new IntentFilter(SmsStore.CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changed, filter, Context.RECEIVER_NOT_EXPORTED); else registerLegacyReceiver(filter);
        registered = true; UploadJobService.schedule(this); refresh();
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") private void registerLegacyReceiver(IntentFilter filter) { registerReceiver(changed, filter, getPackageName() + ".INTERNAL", null); }
    @Override protected void onPause() { if (registered) { unregisterReceiver(changed); registered = false; } super.onPause(); }
    @Override protected void onDestroy() { if (activeDialog != null) activeDialog.dismiss(); worker.shutdown(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (aboutPage) showHome();
        else if (editing) showSettings();
        else if (profilesPage) showHome();
        else if (wizard && step > 0) { rememberInputs(); step--; showStep(); }
        else if (wizard && (new SyncConfig(this).setupComplete() || new SyncConfig(this).enabled())) showHome(); else super.onBackPressed();
    }
    private void say(String message) { notice.setText(message); notice.setVisibility(View.VISIBLE); notice.announceForAccessibility(message); }
    private CrayonDialog.Builder dialog() {
        return new CrayonDialog.Builder(this).setMode(mascotMode()).onShown(dialog -> {
            if (activeDialog != null && activeDialog != dialog) activeDialog.dismiss();
            activeDialog = dialog;
        });
    }
    private String value(EditText input) { return input.getText().toString().trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); return panel; }
    private SketchDrawable sketch(int fill, int outline, float width) { return new SketchDrawable(fill, outline, width, getResources().getDisplayMetrics().density); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(18), dp(17), dp(18), dp(17)); card.setBackground(sketch(PAPER, EDGE, 1.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(14); parent.addView(card, lp); return card;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); view.setPadding(0, dp(5), 0, dp(5)); return view; }
    private TextView label(LinearLayout parent, String value, int size, int color) { TextView view = text(value, size, color); parent.addView(view); return view; }
    private Button button(LinearLayout parent, String value, boolean primary, View.OnClickListener action) {
        Button button = new Button(this); button.setText(value); button.setTextSize(15); button.setAllCaps(false); button.setTextColor(INK); button.setTypeface(null, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20677ea9), sketch(primary ? WAX_BLUE : PAPER, primary ? BLUE : 0xff69677a, primary ? 2.4f : 1.6f), null)); button.setElevation(0); button.setStateListAnimator(null);
        button.setMinHeight(dp(52)); button.setPadding(dp(14), dp(11), dp(14), dp(11));
        button.setOnClickListener(action); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); parent.addView(button, lp); return button;
    }
    private EditText field(LinearLayout parent, String label, String value, int type, String hint) {
        label(parent, label, 14, INK); EditText input = new EditText(this); input.setInputType(type); input.setSingleLine(true); input.setTextSize(16); input.setTextColor(INK); input.setHintTextColor(MUTED);
        input.setText(value); input.setHint(hint); input.setPadding(dp(14), dp(14), dp(14), dp(14)); input.setBackground(sketch(0xffeef0f6, 0xffa7a7b7, 1.1f)); input.setSaveEnabled(false); input.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12); parent.addView(input, lp); return input;
    }
    private RadioButton choice(RadioGroup parent, String label, boolean checked) {
        RadioButton radio = new RadioButton(this); radio.setId(View.generateViewId()); radio.setText(label); radio.setTextSize(15); radio.setTextColor(INK); radio.setPadding(dp(12), dp(15), dp(12), dp(15));
        parent.addView(radio, new RadioGroup.LayoutParams(-1, -2)); if (checked) parent.check(radio.getId()); return radio;
    }
}
