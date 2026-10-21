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
            if (eyebrow.startsWith(getString(R.string.setup_wizard))) label(root, eyebrow, 12, BLUE);
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
        brandCaption.setText(SyncConfig.DATABASE.equals(selected) ? getString(R.string.cyber_messenger) : getString(R.string.your_little_messenger));
    }
    private void showStep() {
        if (new SyncConfig(this).setupComplete()) { showSettings(); return; }
        wizard = true; editing = false; profilesPage = false;
        boolean mail = SyncConfig.EMAIL.equals(mode);
        String[] titles = {getString(R.string.your_sms_wherever_you_need_it), getString(R.string.set_the_receiving_phone_numbers), mail ? getString(R.string.connect_your_email) : getString(R.string.connect_your_self_hosted_archive), getString(R.string.ready_for_new_messages)};
        page(getString(R.string.setup_wizard_9) + (step + 1) + " / 4", titles[step], "");
        LinearLayout progress = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            View segment = new View(this); segment.setBackground(sketch(i <= step ? WAX_BLUE : 0xffe5e0d7, i <= step ? BLUE : 0xffc6c0b5, .7f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(10), 1); lp.setMargins(0, 0, dp(5), 0); progress.addView(segment, lp);
        }
        root.addView(progress); space(root, 22);
        if (step == 0) methodStep(); else if (step == 1) numberStep(); else if (step == 2) { if (mail) emailStep(); else databaseStep(); } else reviewStep();
        root.addView(notice); space(root, 14);
        button(root, step == 3 ? getString(R.string.finish_setup) : getString(R.string.next), true, view -> next());
        if (step > 0) button(root, getString(R.string.back), false, view -> { rememberInputs(); step--; showStep(); });
        if (new SyncConfig(this).setupComplete() || new SyncConfig(this).enabled()) button(root, getString(R.string.cancel_and_return_home), false, view -> { password = ""; showHome(); });
    }
    private void showSettings() {
        wizard = false; editing = false; phoneEditing = false; profilesPage = true; password = "";
        page("CONFIGURATIONS", getString(R.string.forwarding_profiles), getString(R.string.tap_a_card_to_switch_profiles));
        SyncConfig config = new SyncConfig(this); ProfileStore profiles = new ProfileStore(config);
        try {
            profiles.migrate();
            if (profiles.list().isEmpty()) label(root, getString(R.string.no_saved_profiles_yet_add_one_below), 15, MUTED);
            for (ProfileStore.Profile profile : profiles.list()) {
                boolean selected = profile.id().equals(profiles.activeId());
                LinearLayout item = card(root);
                item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18677ea9), sketch(selected ? 0xfffff9e9 : PAPER, selected ? BLUE : EDGE, selected ? 2.8f : 1.5f), null));
                TextView badge = label(item, selected ? getString(R.string.active) : getString(R.string.tap_to_use), 12, selected ? INK : MUTED);
                if (selected) { badge.setBackground(sketch(0xfff0c8aa, 0xffc48e71, .8f)); badge.setPadding(dp(12), dp(7), dp(12), dp(7)); LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(-2, -2); badgeLayout.bottomMargin = dp(8); badge.setLayoutParams(badgeLayout); }
                LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL); item.addView(heading);
                TextView profileTitle = label(heading, profile.name(), 18, INK); profileTitle.setTypeface(null, Typeface.BOLD); profileTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                Button edit = button(heading, getString(R.string.edit_profile), false, view -> editProfile(profile.id())); edit.setMinHeight(dp(48)); edit.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams editLayout = new LinearLayout.LayoutParams(dp(112), -2); editLayout.leftMargin = dp(8); edit.setLayoutParams(editLayout);
                String account = profile.get("smtp_username");
                label(item, SyncConfig.EMAIL.equals(profile.mode()) ? (account.equals(profile.target()) ? getString(R.string.sender_and_recipient) + account : getString(R.string.from) + account + getString(R.string.to) + profile.target()) : getString(R.string.service) + profile.target(), 13, MUTED);
                item.setContentDescription(getString(R.string.select_profile) + profile.name()); item.setClickable(true);
                item.setOnClickListener(view -> selectProfile(profile.id()));
            }
        } catch (IllegalArgumentException error) { notice.setText(UserError.message(this, error)); notice.setVisibility(View.VISIBLE); }
        LinearLayout actions = new LinearLayout(this); root.addView(actions);
        Button add = button(actions, getString(R.string.add_profile), true, view -> showProfileTypes());
        Button numbers = button(actions, getString(R.string.receiving_numbers), false, view -> { loadDraft(); showPhoneEditor(); });
        LinearLayout.LayoutParams addLayout = new LinearLayout.LayoutParams(0, -2, 1); addLayout.rightMargin = dp(5); add.setLayoutParams(addLayout);
        LinearLayout.LayoutParams numberLayout = new LinearLayout.LayoutParams(0, -2, 1); numberLayout.leftMargin = dp(5); numbers.setLayoutParams(numberLayout);
        button(root, getString(R.string.home), false, view -> showHome()); root.addView(notice);
    }
    private void showProfileTypes() {
        wizard = false; editing = false; profilesPage = true;
        page("NEW CONFIGURATION", getString(R.string.add_profile_28), "");
        button(root, getString(R.string.email_forwarding), true, view -> newProfile(SyncConfig.EMAIL));
        button(root, getString(R.string.self_hosted_archive), false, view -> newProfile(SyncConfig.DATABASE));
        button(root, getString(R.string.back_to_profiles), false, view -> showSettings());
        root.addView(notice);
    }
    private void selectProfile(String id) {
        ProfileStore profiles = new ProfileStore(new SyncConfig(this));
        if (id.equals(profiles.activeId())) return;
        try { profiles.select(id); resetJobs(); showSettings(); }
        catch (IllegalArgumentException error) { say(UserError.message(this, error)); }
    }
    private void editProfile(String id) {
        ProfileStore.Profile profile = new ProfileStore(new SyncConfig(this)).find(id);
        if (profile == null) { say(getString(R.string.profile_not_found_refresh_and_try_again)); return; }
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
        page("CONFIGURATION", profileId.isEmpty() ? getString(R.string.add_profile_28) : getString(R.string.edit_profile), "");
        profileNameInput = field(root, getString(R.string.profile_name), profileName, InputType.TYPE_CLASS_TEXT, SyncConfig.EMAIL.equals(mode) ? getString(R.string.for_example_gmail_forwarding) : getString(R.string.for_example_home_archive));
        if (SyncConfig.EMAIL.equals(mode)) emailStep(); else databaseStep();
        root.addView(notice);
        button(root, getString(R.string.save_profile), true, view -> saveSettings());
        button(root, getString(R.string.cancel_and_return_to_profiles), false, view -> showSettings());
    }
    private void showPhoneEditor() {
        wizard = false; profilesPage = false; editing = true; phoneEditing = true;
        page("PHONE NUMBERS", getString(R.string.receiving_numbers), "");
        numberStep(); root.addView(notice);
        button(root, getString(R.string.save_numbers), true, view -> saveSettings());
        button(root, getString(R.string.cancel_and_return_to_profiles), false, view -> showSettings());
    }
    private void saveSettings() {
        rememberInputs();
        try {
            SyncConfig config = new SyncConfig(this); ProfileStore profiles = new ProfileStore(config);
            if (phoneEditing) config.completeSetup(config.url(), config.token(), config.mode(), SyncConfig.EMAIL.equals(config.mode()) ? config.smtp() : null, phone1, phone2, config.enabled());
            else profiles.save(profileId, profileName, mode, url, token, SyncConfig.EMAIL.equals(mode) ? draftSmtp() : null);
            if (phoneEditing || profileId.equals(profiles.activeId())) resetJobs();
            password = ""; showSettings();
        } catch (IllegalArgumentException error) { say(UserError.message(this, error)); }
    }
    private void methodStep() {
        RadioGroup options = new RadioGroup(this); root.addView(options);
        RadioButton mail = choice(options, getString(R.string.email_forwarding), SyncConfig.EMAIL.equals(mode));
        choice(options, getString(R.string.self_hosted_archive), !SyncConfig.EMAIL.equals(mode));
        TextView hint = label(root, methodHint(), 13, MUTED);
        options.setOnCheckedChangeListener((group, id) -> { mode = id == mail.getId() ? SyncConfig.EMAIL : SyncConfig.DATABASE; updateMascot(true); hint.setText(methodHint()); });
    }
    private String methodHint() { return SyncConfig.EMAIL.equals(mode) ? getString(R.string.receive_forwarded_sms_by_email_no_server_deployment_needed) : getString(R.string.archive_sms_on_your_own_service_with_sqlite_postgresql_mysql_or_m); }
    private void numberStep() {
        numberStep(root);
    }
    private void numberStep(LinearLayout container) {
        LinearLayout panel = card(container);
        button(panel, getString(R.string.read_numbers_from_sim), false, view -> requestPhoneNumbers());
        numberStatus = label(panel, getString(R.string.allow_sim_access_or_enter_the_numbers_manually_check_that_the_num), 13, MUTED);
        phone1Input = field(panel, getString(R.string.this_phone_sim_1), phone1, InputType.TYPE_CLASS_PHONE, getString(R.string.for_example_81_90_1234_5678));
        phone2Input = field(panel, getString(R.string.sim_2_number_optional), phone2, InputType.TYPE_CLASS_PHONE, getString(R.string.second_number_for_dual_sim_phones));
        label(container, getString(R.string.enter_each_number_by_sim_slot_numbers_apply_to_new_sms_only_older), 13, MUTED);
    }
    private void emailStep() {
        LinearLayout provider = card(root); label(provider, getString(R.string.step_1_choose_your_email_provider), 17, INK).setTypeface(null, Typeface.BOLD);
        Button picker = button(provider, getString(SmtpPresets.matching(host).name) + "  ▾", false, null);
        TextView instructions = label(provider, getString(SmtpPresets.matching(host).hint), 14, MUTED);
        button(provider, getString(R.string.get_an_email_app_password), false, view -> openHelp(value(hostInput)));
        LinearLayout account = card(root); label(account, getString(R.string.step_2_enter_sender_account), 17, INK).setTypeface(null, Typeface.BOLD);
        usernameInput = field(account, getString(R.string.sender_email_smtp_account), username, EMAIL_INPUT, getString(R.string.full_email_address));
        ProfileStore.Profile edited = editing ? new ProfileStore(new SyncConfig(this)).find(profileId) : null;
        boolean stored = editing ? edited != null && !edited.get("smtp_password_encrypted").isEmpty() : !new SyncConfig(this).prefs.getString("smtp_password_encrypted", "").isEmpty();
        passwordInput = field(account, getString(R.string.authorization_code_app_password), password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, stored ? getString(R.string.saved_leave_blank_to_keep_it) : getString(R.string.follow_your_email_provider_s_instructions));
        LinearLayout recipient = card(root); label(recipient, getString(R.string.step_3_where_should_sms_go), 17, INK).setTypeface(null, Typeface.BOLD);
        toInput = field(recipient, getString(R.string.recipient_email), to, EMAIL_INPUT, getString(R.string.one_address_only_can_be_the_same_as_the_sender));
        label(recipient, getString(R.string.each_new_sms_becomes_one_email_the_receiving_phone_number_appears), 13, MUTED);
        LinearLayout advanced = card(root), advancedFields = column();
        Button advancedButton = button(advanced, getString(R.string.server_and_sender_settings), false, null); advanced.addView(advancedFields);
        advancedFields.setVisibility(host.isEmpty() ? View.VISIBLE : View.GONE);
        advancedButton.setOnClickListener(view -> {
            boolean show = advancedFields.getVisibility() != View.VISIBLE; advancedFields.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedButton.setText(show ? getString(R.string.hide_server_settings) : getString(R.string.server_and_sender_settings));
        });
        hostInput = field(advancedFields, getString(R.string.smtp_server), host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, getString(R.string.no_protocol_port_or_path));
        portInput = field(advancedFields, getString(R.string.port), port, InputType.TYPE_CLASS_NUMBER, getString(R.string.port_465_or_587));
        securityInput = new RadioGroup(this); advancedFields.addView(securityInput);
        RadioButton ssl = choice(securityInput, getString(R.string.ssl_tls_usually_465), "ssl".equals(security)); sslId = ssl.getId();
        RadioButton tls = choice(securityInput, getString(R.string.starttls_usually_587), "starttls".equals(security));
        securityInput.setOnCheckedChangeListener((group, id) -> {
            String current = value(portInput); if (current.equals("465") || current.equals("587")) portInput.setText(id == sslId ? "465" : "587");
        });
        fromInput = field(advancedFields, getString(R.string.sender_address_blank_uses_account_address), from, EMAIL_INPUT, getString(R.string.usually_the_same_as_the_smtp_account));
        picker.setOnClickListener(view -> {
            String[] names = new String[SmtpPresets.ALL.length]; for (int i = 0; i < names.length; i++) names[i] = getString(SmtpPresets.ALL[i].name);
            dialog().setTitle(getString(R.string.email_provider)).setItems(names, (dialog, which) -> {
                SmtpPresets.Preset preset = SmtpPresets.ALL[which];
                if (!preset.host.isEmpty()) {
                    if (!preset.host.equalsIgnoreCase(value(hostInput))) { passwordInput.setText(""); passwordInput.setHint(getString(R.string.enter_a_new_app_password_after_changing_providers)); }
                    hostInput.setText(preset.host); securityInput.check("ssl".equals(preset.security) ? sslId : tls.getId()); portInput.setText(String.valueOf(preset.port));
                }
                picker.setText(getString(preset.name) + "  ▾"); instructions.setText(getString(preset.hint));
                advancedFields.setVisibility(preset.host.isEmpty() ? View.VISIBLE : View.GONE);
                advancedButton.setText(preset.host.isEmpty() ? getString(R.string.hide_server_settings) : getString(R.string.server_and_sender_settings));
            }).setNegativeButton(getString(R.string.cancel), null).show();
        });
    }
    private void databaseStep() {
        LinearLayout panel = card(root);
        label(panel, getString(R.string.connect_the_companion_service), 17, INK).setTypeface(null, Typeface.BOLD);
        label(panel, getString(R.string.deploy_the_service_first_then_scan_its_qr_code_or_enter_the_conne), 13, MUTED);
        button(panel, getString(R.string.scan_configuration), false, view -> { rememberInputs(); startActivityForResult(new Intent(this, PairingScannerActivity.class), 41); });
        urlInput = field(panel, getString(R.string.archive_service_url), url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "https://sms.example.com");
        tokenInput = field(panel, getString(R.string.service_access_token), token, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, getString(R.string.match_the_server_s_api_token_at_least_32_characters));
        label(panel, getString(R.string.enter_database_credentials_on_the_server_only), 13, MUTED);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 41 || result != RESULT_OK || data == null) return;
        if ((!wizard && !editing) || phoneEditing || !SyncConfig.DATABASE.equals(mode)) return;
        try {
            PairingConfig pairing = PairingConfig.parse(data.getStringExtra("pairing"));
            dialog().setTitle(getString(R.string.connect_to_this_nest))
                .setMessage(pairing.name + "\n" + pairing.url + getString(R.string.confirming_only_fills_in_the_form_save_the_profile_to_apply_it_th))
                .setPositiveButton(getString(R.string.fill_in_settings), (dialog, which) -> {
                    url = pairing.url; token = pairing.token; urlInput.setText(url); tokenInput.setText(token);
                    if (editing && profileId.isEmpty() && value(profileNameInput).isEmpty()) { profileName = pairing.name; profileNameInput.setText(profileName); }
                    say(getString(R.string.url_and_token_filled_in_check_the_details_and_save_the_profile));
                }).setNegativeButton(getString(R.string.cancel), null).show();
        } catch (IllegalArgumentException error) { say(UserError.message(this, error)); }
    }
    private SmtpProfile draftSmtp() {
        int number; try { number = Integer.parseInt(port); } catch (NumberFormatException error) { throw new UserError(R.string.smtp_port_must_be_between_1_and_65535); }
        String secret = editing ? new ProfileStore(new SyncConfig(this)).password(profileId, host, username, password) : new SyncConfig(this).smtpPassword(host, username, password, true);
        SmtpProfile smtp = new SmtpProfile(host, number, security, username, secret, from.isEmpty() ? username : from, to); smtp.validate(); return smtp;
    }
    private void next() {
        rememberInputs();
        try {
            if (step == 1) {
                phone1 = SyncConfig.normalizePhone(phone1); phone2 = SyncConfig.normalizePhone(phone2);
                if (phone1.isEmpty() && phone2.isEmpty()) throw new UserError(R.string.enter_the_phone_number_that_receives_sms);
                if (!phone1.isEmpty() && phone1.equals(phone2)) throw new UserError(R.string.enter_different_numbers_for_the_two_sims);
            }
            if (step == 2) {
                if (SyncConfig.EMAIL.equals(mode)) draftSmtp();
                else {
                    java.net.URI uri = new java.net.URI(url);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new UserError(R.string.enter_the_full_http_https_url_of_the_companion_service);
                    if (token.length() < 32) throw new UserError(R.string.access_tokens_need_at_least_32_characters_check_the_server_settin);
                }
            }
            if (step < 3) { step++; showStep(); return; }
            if (turnOn && !allowed()) throw new UserError(R.string.allow_sms_access_first_or_disable_automatic_forwarding_before_sav);
            new SyncConfig(this).completeSetup(url, token, mode, SyncConfig.EMAIL.equals(mode) ? draftSmtp() : null, phone1, phone2, turnOn);
            password = ""; resetJobs(); showHome();
        } catch (IllegalArgumentException error) { say(UserError.message(this, error)); }
        catch (java.net.URISyntaxException error) { say(getString(R.string.invalid_archive_service_url)); }
    }
    private void reviewStep() {
        LinearLayout review = card(root); label(review, SyncConfig.EMAIL.equals(mode) ? getString(R.string.email_forwarding) : getString(R.string.self_hosted_archive), 18, INK).setTypeface(null, Typeface.BOLD);
        label(review, (phone1.isEmpty() ? "" : "SIM 1  " + phone1) + (phone2.isEmpty() ? "" : (phone1.isEmpty() ? "" : "\n") + "SIM 2  " + phone2), 15, MUTED);
        label(review, SyncConfig.EMAIL.equals(mode) ? getString(R.string.sender_email) + username + getString(R.string.recipient_email_92) + to : getString(R.string.archive_service) + url, 14, MUTED);
        LinearLayout permissions = card(root); label(permissions, getString(R.string.sms_permission), 17, INK).setTypeface(null, Typeface.BOLD);
        permissionStatus = label(permissions, allowed() ? getString(R.string.permission_granted_ready_for_new_sms) : getString(R.string.permission_needed_to_forward_new_sms), 14, MUTED);
        button(permissions, getString(R.string.allow_sms_access), false, view -> {
            if (allowed()) { permissionStatus.setText(getString(R.string.permission_granted_ready_for_new_sms)); return; }
            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 1);
        });
        button(permissions, getString(R.string.open_app_permissions), false, view -> openPermissions());
        enableInput = new CheckBox(this); enableInput.setText(getString(R.string.enable_automatic_forwarding_after_setup)); enableInput.setTextColor(INK); enableInput.setTextSize(15); enableInput.setChecked(turnOn); root.addView(enableInput);
        label(root, getString(R.string.only_new_sms_will_be_forwarded_your_existing_inbox_is_not_importe), 13, MUTED);
    }
    private void showHome() {
        wizard = false; editing = false; profilesPage = false; phoneEditing = false; password = ""; SyncConfig c = new SyncConfig(this);
        if (c.setupComplete()) { try { new ProfileStore(c).migrate(); } catch (IllegalArgumentException ignored) { /* Cards report a retryable migration error. */ } }
        page("MESSAGE REDIRECT", "", "");
        LinearLayout state = card(root); label(state, getString(R.string.forwarding_status), 13, MUTED);
        LinearLayout statusLine = new LinearLayout(this); statusLine.setGravity(Gravity.CENTER_VERTICAL); state.addView(statusLine);
        homeState = label(statusLine, "", 23, BLUE); homeState.setTypeface(null, Typeface.BOLD); homeState.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button toggle = button(statusLine, c.enabled() ? getString(R.string.pause) : getString(R.string.enable), !c.enabled(), view -> toggle());
        LinearLayout.LayoutParams toggleLayout = new LinearLayout.LayoutParams(dp(80), -2); toggleLayout.leftMargin = dp(8); toggle.setLayoutParams(toggleLayout);
        homeDetails = label(state, "", 13, MUTED); space(state, 4); homeCounts = label(state, "", 13, MUTED);
        ProfileStore.Profile active = new ProfileStore(c).active();
        LinearLayout destination = card(root); label(destination, getString(R.string.destination), 13, MUTED); label(destination, active == null ? c.modeLabel() : active.name(), 18, INK).setTypeface(null, Typeface.BOLD);
        label(destination, c.target().isEmpty() ? getString(R.string.no_destination_configured) : c.target(), 15, MUTED);
        label(destination, c.phoneSummary().isEmpty() ? getString(R.string.receiving_phone_numbers_not_set) : c.phoneSummary(), 14, MUTED);
        button(root, c.setupComplete() ? getString(R.string.manage_profiles) : getString(R.string.complete_setup), true, view -> {
            loadDraft(); if (c.setupComplete()) showSettings(); else { step = 0; showStep(); }
        });
        button(root, getString(R.string.more_actions), false, view -> dialog().setTitle(getString(R.string.more_actions))
            .setItems(new String[]{getString(R.string.retry_unsent_sms), getString(R.string.check_email_delivery), getString(R.string.usage_guide), getString(R.string.open_app_permissions), getString(R.string.about_gugugu)}, (dialog, which) -> {
                if (which == 0) uploadNow(); else if (which == 1) resolveUncertain(); else if (which == 2) showUsage(); else if (which == 3) openPermissions(); else showAbout();
            }).setNegativeButton(getString(R.string.close), null).show());
        root.addView(notice); refresh();
    }
    private void showAbout() {
        wizard = false; editing = false; profilesPage = false; phoneEditing = false; password = "";
        page("ABOUT", getString(R.string.about_gugugu), "");
        LinearLayout portrait = column(); portrait.setGravity(Gravity.CENTER); space(portrait, 12);
        MascotView pigeon = new MascotView(this); pigeon.setMode(new SyncConfig(this).mode(), false);
        portrait.addView(pigeon, new LinearLayout.LayoutParams(dp(210), dp(210)));
        TextView greeting = label(portrait, getString(R.string.coo_coo_your_message_is_on_its_way), 16, MUTED); greeting.setGravity(Gravity.CENTER);
        space(portrait, 22); root.addView(portrait);
        LinearLayout version = card(root);
        try {
            android.content.pm.PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), 0);
            long build = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            label(version, getString(R.string.version), 13, MUTED);
            label(version, installed.versionName, 24, INK).setTypeface(null, Typeface.BOLD);
            label(version, getString(R.string.build) + build, 13, MUTED);
        } catch (PackageManager.NameNotFoundException error) {
            label(version, getString(R.string.version_information_is_unavailable), 16, INK);
        }
        button(root, getString(R.string.home), false, view -> showHome());
    }
    private void showUsage() {
        dialog().setTitle(getString(R.string.usage_guide))
            .setMessage(getString(R.string.automatic_forwarding_new_sms_is_sent_automatically_when_enabled_w))
            .setPositiveButton(getString(R.string.got_it), null).show();
    }
    private void toggle() {
        SyncConfig c = new SyncConfig(this);
        if (!c.enabled() && !c.setupComplete()) { loadDraft(); step = 0; showStep(); return; }
        if (!c.enabled() && !allowed()) { say(getString(R.string.grant_sms_permission_in_system_settings_before_enabling_forwardin)); openPermissions(); return; }
        try { c.save(c.url(), c.token(), !c.enabled(), c.mode(), SyncConfig.EMAIL.equals(c.mode()) ? c.smtp() : null); resetJobs(); showHome(); }
        catch (IllegalArgumentException error) { say(UserError.message(this, error)); }
    }
    private void resetJobs() {
        android.app.job.JobScheduler jobs = (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        jobs.cancel(UploadJobService.IMMEDIATE); jobs.cancel(UploadJobService.PERIODIC); UploadJobService.schedule(this);
    }
    private void refresh() {
        if (wizard || editing || profilesPage || aboutPage) { if (permissionStatus != null) permissionStatus.setText(allowed() ? getString(R.string.permission_granted_ready_for_new_sms) : getString(R.string.permission_needed_to_forward_new_sms)); return; }
        worker.execute(() -> {
            try (SmsStore store = new SmsStore(this)) {
                if (DeliveryLock.LOCK.tryLock()) { try { store.recoverInFlightEmails(); } finally { DeliveryLock.LOCK.unlock(); } }
                SyncConfig c = new SyncConfig(this); long pending = store.pendingCount(), eligible = store.eligibleCount(c.mode(), c.target()), unfinishedMail = store.uncertainCount(), review = store.reviewCount();
                long sending = Math.max(0, unfinishedMail - review), other = Math.max(0, pending - eligible - unfinishedMail);
                runOnUiThread(() -> {
                    if (isDestroyed() || wizard || editing || profilesPage || aboutPage) return;
                    homeState.setText(!allowed() ? getString(R.string.sms_permission_needed) : !c.enabled() ? getString(R.string.paused) : getString(R.string.enabled));
                    homeDetails.setText(!allowed() ? getString(R.string.go_to_more_actions_open_app_permissions_to_allow_sms_access) : review > 0 ? getString(R.string.some_emails_have_an_uncertain_delivery_status_go_to_more_actions) : !c.enabled() ? getString(R.string.new_sms_stays_on_this_phone_until_forwarding_is_enabled) : c.status());
                    homeCounts.setText(quantity(R.plurals.queue_pending, eligible)
                        + (sending > 0 ? "\n" + quantity(R.plurals.queue_sending, sending) : "")
                        + (review > 0 ? "\n" + quantity(R.plurals.queue_review, review) : "")
                        + (other > 0 ? "\n" + quantity(R.plurals.queue_other, other) : ""));
                });
            }
        });
    }
    private void uploadNow() {
        if (!new SyncConfig(this).enabled()) { say(getString(R.string.enable_forwarding_before_retrying)); return; } say(getString(R.string.retrying_unsent_sms_in_the_current_profile));
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
                if (rows.isEmpty()) { say(getString(R.string.no_emails_need_checking_normal_forwarding_needs_no_manual_action)); return; }
                SmsStore.Message row = rows.get(0);
                dialog().setTitle(getString(R.string.did_this_email_arrive))
                    .setMessage(getString(R.string.the_connection_dropped_while_sending_so_this_email_may_already_ha) + row.target + getString(R.string.sms_sender) + row.sender + getString(R.string.sms_received_at) + DateFormat.getDateTimeInstance().format(new java.util.Date(row.capturedAt)) + getString(R.string.still_to_check) + rows.size() + getString(R.string.emails_mark_as_received_if_it_arrived_otherwise_choose_resend))
                    .setPositiveButton(getString(R.string.received), (dialog, which) -> resolveEmail(row.id, false))
                    .setNeutralButton(getString(R.string.not_received_resend), (dialog, which) -> dialog().setTitle(getString(R.string.send_this_email_again))
                        .setMessage(getString(R.string.first_confirm_that_the_email_is_in_neither_your_inbox_nor_spam_fo))
                        .setPositiveButton(getString(R.string.resend_to_original_inbox), (d, w) -> resolveEmail(row.id, true)).setNegativeButton(getString(R.string.do_not_resend_yet), null).show())
                    .setNegativeButton(getString(R.string.check_later), null).show();
            });
        });
    }
    private void resolveEmail(String id, boolean retry) {
        worker.execute(() -> {
            int outcome;
            if (!DeliveryLock.LOCK.tryLock()) outcome = R.string.sending_in_progress_try_again_later;
            else {
                try (SmsStore store = new SmsStore(this)) {
                    if (retry) store.retryEmail(id); else store.markUploaded(java.util.Collections.singletonList(id));
                    outcome = retry ? R.string.resend_scheduled_keep_forwarding_enabled_and_select_the_original : R.string.marked_as_received_this_email_will_not_be_sent_again;
                    new SyncConfig(this).status(outcome);
                }
                catch (Exception error) { outcome = R.string.the_record_changed_refresh_and_try_again; }
                finally { DeliveryLock.LOCK.unlock(); }
            }
            if (retry) UploadJobService.schedule(this); final String message = getString(outcome);
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
            numberStatus.setText(numbers[0].isEmpty() && numbers[1].isEmpty() ? getString(R.string.the_system_did_not_provide_a_number_enter_it_manually_sim_access) : getString(R.string.sim_numbers_filled_into_empty_fields_check_them_and_enter_any_mis));
        } catch (SecurityException error) { numberStatus.setText(getString(R.string.sim_access_was_not_granted_enter_the_numbers_manually_or_allow_ac)); }
        catch (UnsupportedOperationException | IllegalStateException error) { numberStatus.setText(getString(R.string.sim_numbers_cannot_be_read_on_this_device_enter_them_manually)); }
    }
    private void openPermissions() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void openHelp(String host) {
        String help = SmtpPresets.matching(host).help;
        if (help.isEmpty()) { say(getString(R.string.ask_your_email_provider_for_smtp_settings_and_authorization_instr)); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(help))); } catch (android.content.ActivityNotFoundException error) { say(getString(R.string.no_browser_is_available_to_open_this_page)); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 3) { readPhoneNumbers(); return; }
        refresh(); if (!allowed()) say(getString(R.string.sms_permission_was_not_granted_if_the_prompt_no_longer_appears_op));
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
    private String quantity(int resource, long count) {
        return getResources().getQuantityString(resource, (int) Math.min(count, Integer.MAX_VALUE), count);
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
