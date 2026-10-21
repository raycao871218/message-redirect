"use strict";
(async () => {
await I18n.ready;

const $ = id => document.getElementById(id);
const t = key => I18n.t(key);
let csrf = "", config = null, noticeTimer;
function notice(message, error = false) {
  clearTimeout(noticeTimer); I18n.assign($("notice"), message); $("notice").classList.toggle("error", error); $("notice").hidden = false;
  noticeTimer = setTimeout(() => { $("notice").hidden = true; }, error ? 12000 : 6500);
}
async function api(path, data) {
  const response = await fetch(path, { method: data === undefined ? "GET" : "POST", credentials: "same-origin", cache: "no-store",
    headers: { "Accept-Language": I18n.language, ...(data === undefined ? {} : { "Content-Type": "application/json", "X-CSRF-Token": csrf }) },
    body: data === undefined ? undefined : JSON.stringify(data) });
  const value = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== "/admin/api/login") showLogin();
    throw new Error(typeof value.detail === "string" ? value.detail : t("could_not_complete_the_action_try_again_later"));
  }
  return value;
}
function showLogin() {
  closePasswordDialog();
  csrf = ""; config = null; $("dashboard").hidden = true; $("login-view").hidden = false; $("logout").hidden = true;
  $("api-token").value = ""; $("pairing-qr").removeAttribute("src"); $("brand-dove").hidden = true;
}
function closePasswordDialog() {
  $("password-dialog").close(); $("password-form").reset(); $("password-error").hidden = true; I18n.assign($("password-error"), "");
}
function fields() {
  const kind = $("db-type").value;
  $("sqlite-fields").hidden = kind !== "sqlite"; $("remote-fields").hidden = kind === "sqlite"; $("postgres-tls").hidden = kind !== "postgresql";
}
function databaseInput() {
  const result = { type: $("db-type").value, path: $("db-path").value, host: $("db-host").value.trim(), port: Number($("db-port").value),
    dbname: $("db-name").value.trim(), user: $("db-user").value.trim(), password: $("db-password").value };
  if (result.type === "postgresql") result.sslmode = $("db-sslmode").value;
  return result;
}
function pairing() {
  const ready = Boolean(config.public_url);
  $("pairing-qr").hidden = !ready; $("qr-placeholder").hidden = ready;
  if (ready) $("pairing-qr").src = "/admin/api/pairing.svg?v=" + Date.now();
  else $("pairing-qr").removeAttribute("src");
}
async function load() {
  config = await api("/admin/api/config");
  $("login-view").hidden = true; $("dashboard").hidden = false; $("logout").hidden = false; $("brand-dove").hidden = false;
  $("db-type").value = config.database.type;
  for (const [id, key, fallback] of [["db-path", "path", "/data/messages.sqlite3"], ["db-host", "host", ""], ["db-port", "port", 5432], ["db-name", "dbname", ""], ["db-user", "user", ""], ["db-sslmode", "sslmode", "prefer"]]) $(id).value = config.database[key] ?? fallback;
  $("db-password").value = ""; I18n.assign($("db-password"), config.database.password_saved ? t("saved_leave_blank_to_keep_the_current_password") : t("enter_database_password"), "placeholder");
  I18n.assign($("password-hint"), config.database.password_saved ? t("leave_blank_to_keep_this_connection_s_password_enter_it") : t("create_the_database_first_the_account_needs_table_creat"));
  $("api-token").value = config.api_token; $("api-token").type = "password"; I18n.assign($("show-token"), t("show")); $("show-token").setAttribute("aria-pressed", "false");
  $("public-url").value = config.public_url || window.location.origin;
  I18n.assign($("service-version"), "v" + config.version);
  let healthy = false;
  try { healthy = (await fetch("/health", { cache: "no-store" })).ok; } catch { /* Keep settings available during database outages. */ }
  I18n.assign($("health-badge"), healthy ? t("database_ready") : t("database_not_ready"));
  $("health-badge").classList.toggle("offline", !healthy);
  fields(); pairing();
}
async function busy(button, work) {
  button.disabled = true;
  try { await work(); } catch (error) { notice(error.message || t("action_not_completed"), true); }
  finally { button.disabled = false; }
}
function confirmChange(title, message, label) {
  return new Promise(resolve => {
    I18n.assign($("confirm-title"), title); I18n.assign($("confirm-message"), message); I18n.assign($("confirm-ok"), label);
    let done = false;
    const finish = result => { if (done) return; done = true; $("confirm-dialog").close(); resolve(result); };
    $("confirm-cancel").onclick = () => finish(false); $("confirm-ok").onclick = () => finish(true);
    $("confirm-dialog").oncancel = event => { event.preventDefault(); finish(false); };
    $("confirm-dialog").showModal(); $("confirm-cancel").focus();
  });
}
$("login-form").addEventListener("submit", event => {
  event.preventDefault(); busy(event.submitter, async () => {
    const value = await api("/admin/api/login", { password: $("admin-password").value });
    $("admin-password").value = ""; csrf = value.csrf; await load();
  });
});
$("db-type").addEventListener("change", () => { fields(); $("db-port").value = $("db-type").value === "postgresql" ? 5432 : 3306; $("db-password").value = ""; });
$("test-database").addEventListener("click", event => busy(event.currentTarget, async () => { const value = await api("/admin/api/database/test", databaseInput()); notice(value.message); }));
$("database-form").addEventListener("submit", async event => {
  event.preventDefault(); const input = databaseInput();
  if (!await confirmChange(t("send_messages_here"), t("the_connection_will_be_checked_and_required_sms_tables"), t("confirm_and_save"))) return;
  await busy(event.submitter, async () => { const value = await api("/admin/api/database", input); await load(); notice(value.message); });
});
$("public-url-form").addEventListener("submit", event => {
  event.preventDefault(); busy(event.submitter, async () => {
    const value = await api("/admin/api/public-url", { url: $("public-url").value }); config.public_url = value.url; $("public-url").value = value.url; pairing(); notice(t("pairing_code_generated_scan_it_in_the_app_confirm_and_s"));
  });
});
$("show-token").addEventListener("click", () => { const show = $("api-token").type === "password"; $("api-token").type = show ? "text" : "password"; I18n.assign($("show-token"), show ? t("hide") : t("show")); $("show-token").setAttribute("aria-pressed", String(show)); });
$("copy-token").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(config.api_token); notice(t("token_copied_share_it_only_with_your_own_clients")); }
  catch { $("api-token").type = "text"; $("api-token").select(); I18n.assign($("show-token"), t("hide")); $("show-token").setAttribute("aria-pressed", "true"); notice(t("automatic_copying_is_unavailable_the_token_is_selected")); }
});
$("reset-token").addEventListener("click", async event => {
  const button = event.currentTarget;
  if (!await confirmChange(t("generate_a_new_access_token"), t("the_old_token_stops_working_immediately_reconfigure_con"), t("reset_access_token"))) return;
  await busy(button, async () => { await api("/admin/api/token/reset", {}); await load(); notice(t("token_reset_scan_the_new_pairing_code_in_the_app")); });
});
$("logout").addEventListener("click", event => busy(event.currentTarget, async () => { await api("/admin/api/logout", {}); showLogin(); }));
$("change-password").addEventListener("click", () => {
  $("password-form").reset(); $("password-error").hidden = true; I18n.assign($("password-error"), "");
  $("password-dialog").showModal(); $("current-password").focus();
});
$("password-cancel").addEventListener("click", closePasswordDialog);
$("password-dialog").addEventListener("cancel", event => { event.preventDefault(); if (!$("password-save").disabled) closePasswordDialog(); });
$("password-form").addEventListener("submit", async event => {
  event.preventDefault(); const button = $("password-save");
  if (button.disabled) return;
  const data = { current_password: $("current-password").value, new_password: $("new-password").value, confirm_password: $("confirm-password").value };
  $("password-error").hidden = true;
  if (data.new_password !== data.confirm_password) { I18n.assign($("password-error"), t("the_new_passwords_do_not_match")); $("password-error").hidden = false; return; }
  button.disabled = true; $("password-cancel").disabled = true;
  try {
    const value = await api("/admin/api/password", data); showLogin(); notice(value.message); $("admin-password").focus();
  } catch (error) {
    if (!$("password-dialog").open) notice(error.message || t("please_sign_in_again"), true);
    else { I18n.assign($("password-error"), error.message || t("password_change_not_completed_try_again")); $("password-error").hidden = false; }
  } finally { button.disabled = false; $("password-cancel").disabled = false; }
});
(async () => {
  try { const value = await api("/admin/api/session"); csrf = value.csrf || ""; $("setup-hint").hidden = value.configured; $("login-form").hidden = !value.configured; if (value.authenticated) await load(); }
  catch { notice(t("service_unavailable_refresh_and_try_again_later"), true); }
})();

})();
