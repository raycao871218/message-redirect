"use strict";
const $ = id => document.getElementById(id);
let csrf = "", config = null, noticeTimer;
function notice(message, error = false) {
  clearTimeout(noticeTimer); $("notice").textContent = message; $("notice").classList.toggle("error", error); $("notice").hidden = false;
  noticeTimer = setTimeout(() => { $("notice").hidden = true; }, error ? 12000 : 6500);
}
async function api(path, data) {
  const response = await fetch(path, { method: data === undefined ? "GET" : "POST", credentials: "same-origin", cache: "no-store",
    headers: data === undefined ? {} : { "Content-Type": "application/json", "X-CSRF-Token": csrf },
    body: data === undefined ? undefined : JSON.stringify(data) });
  const value = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== "/admin/api/login") showLogin();
    throw new Error(typeof value.detail === "string" ? value.detail : "操作未完成，请稍后重试");
  }
  return value;
}
function showLogin() {
  closePasswordDialog();
  csrf = ""; config = null; $("dashboard").hidden = true; $("login-view").hidden = false; $("logout").hidden = true;
  $("api-token").value = ""; $("pairing-qr").removeAttribute("src"); $("brand-dove").hidden = true;
}
function closePasswordDialog() {
  $("password-dialog").close(); $("password-form").reset(); $("password-error").hidden = true; $("password-error").textContent = "";
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
  $("db-password").value = ""; $("db-password").placeholder = config.database.password_saved ? "已保存，留空保留当前密码" : "输入数据库密码";
  $("password-hint").textContent = config.database.password_saved ? "保留当前连接时可留空；更换主机、数据库名或账号需重新填写密码。" : "数据库需事先创建，账号需要建表和读写权限。";
  $("api-token").value = config.api_token; $("api-token").type = "password"; $("show-token").textContent = "显示"; $("show-token").setAttribute("aria-pressed", "false");
  $("public-url").value = config.public_url || window.location.origin;
  $("service-version").textContent = "v" + config.version;
  let healthy = false;
  try { healthy = (await fetch("/health", { cache: "no-store" })).ok; } catch { /* Keep settings available during database outages. */ }
  $("health-badge").textContent = healthy ? "● 数据库已就绪" : "○ 数据库尚未就绪";
  $("health-badge").classList.toggle("offline", !healthy);
  fields(); pairing();
}
async function busy(button, work) {
  button.disabled = true;
  try { await work(); } catch (error) { notice(error.message || "操作未完成", true); }
  finally { button.disabled = false; }
}
function confirmChange(title, message, label) {
  return new Promise(resolve => {
    $("confirm-title").textContent = title; $("confirm-message").textContent = message; $("confirm-ok").textContent = label;
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
  if (!await confirmChange("把消息送到这里？", "保存前会验证连接并创建需要的短信表。后续短信使用新连接，旧数据库中的短信不会自动搬迁。", "确认保存")) return;
  await busy(event.submitter, async () => { const value = await api("/admin/api/database", input); await load(); notice(value.message); });
});
$("public-url-form").addEventListener("submit", event => {
  event.preventDefault(); busy(event.submitter, async () => {
    const value = await api("/admin/api/public-url", { url: $("public-url").value }); config.public_url = value.url; $("public-url").value = value.url; pairing(); notice("配对码已生成，在 App 中扫码后确认保存。");
  });
});
$("show-token").addEventListener("click", () => { const show = $("api-token").type === "password"; $("api-token").type = show ? "text" : "password"; $("show-token").textContent = show ? "隐藏" : "显示"; $("show-token").setAttribute("aria-pressed", String(show)); });
$("copy-token").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(config.api_token); notice("密钥已复制，请只交给自己的客户端。"); }
  catch { $("api-token").type = "text"; $("api-token").select(); $("show-token").textContent = "隐藏"; $("show-token").setAttribute("aria-pressed", "true"); notice("浏览器不支持自动复制，已选中密钥，请手动复制。"); }
});
$("reset-token").addEventListener("click", async event => {
  const button = event.currentTarget;
  if (!await confirmChange("换一个新的暗号？", "旧访问密钥会立即失效，已连接的 App 和自建客户端需要重新配置。二维码会同步更新，管理密码不会改变。", "重置访问密钥")) return;
  await busy(button, async () => { await api("/admin/api/token/reset", {}); await load(); notice("访问密钥已重置，请让 App 重新扫码配置。"); });
});
$("logout").addEventListener("click", event => busy(event.currentTarget, async () => { await api("/admin/api/logout", {}); showLogin(); }));
$("change-password").addEventListener("click", () => {
  $("password-form").reset(); $("password-error").hidden = true; $("password-error").textContent = "";
  $("password-dialog").showModal(); $("current-password").focus();
});
$("password-cancel").addEventListener("click", closePasswordDialog);
$("password-dialog").addEventListener("cancel", event => { event.preventDefault(); if (!$("password-save").disabled) closePasswordDialog(); });
$("password-form").addEventListener("submit", async event => {
  event.preventDefault(); const button = $("password-save");
  if (button.disabled) return;
  const data = { current_password: $("current-password").value, new_password: $("new-password").value, confirm_password: $("confirm-password").value };
  $("password-error").hidden = true;
  if (data.new_password !== data.confirm_password) { $("password-error").textContent = "两次输入的新密码不一致"; $("password-error").hidden = false; return; }
  button.disabled = true; $("password-cancel").disabled = true;
  try {
    const value = await api("/admin/api/password", data); showLogin(); notice(value.message); $("admin-password").focus();
  } catch (error) {
    if (!$("password-dialog").open) notice(error.message || "请重新登录", true);
    else { $("password-error").textContent = error.message || "密码修改未完成，请重试"; $("password-error").hidden = false; }
  } finally { button.disabled = false; $("password-cancel").disabled = false; }
});
(async () => {
  try { const value = await api("/admin/api/session"); csrf = value.csrf || ""; $("setup-hint").hidden = value.configured; $("login-form").hidden = !value.configured; if (value.authenticated) await load(); }
  catch { notice("暂时无法连接服务，请稍后刷新。", true); }
})();
