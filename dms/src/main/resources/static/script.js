/* ==========================================================================
   BRISK DMS — APPLICATION SCRIPT (fully backend-driven rewrite)
   Sections:
     1. Config & API layer
     2. UI Helpers (toast, spinner, confirm dialog)
     3. Auth (register / login / logout / session / forgot / reset)
     4. App shell (sidebar, topbar, theme, router)
     5. Backend data cache (STATE) — populated only from GET responses
     6. Generic CRUD engine (Users, Distributors, Products, Categories, Shops)
     7. Dashboard (KPIs + Chart.js + widgets) — 100% backend
     8. Invoices (list / create / preview / print / pdf)
     9. Payments
    10. Reports (generate + export PDF/Excel) — sourced from STATE
    11. Settings (company, profile, password, theme, logo)
    12. Init

   ARCHITECTURE NOTE
   ------------------
   There is no local/demo data store anywhere in this file. Every table on
   every page renders directly from the last successful GET response for
   that resource (cached in STATE only so widgets that share data — e.g.
   the product dropdown inside the Invoice form — don't need to re-fetch).
   Every Create/Update/Delete calls the real REST API and, only on success,
   re-runs the corresponding GET and re-renders. If a request fails, the
   backend's error message is shown and nothing on screen changes.
   ========================================================================== */

/* ==================== 1. CONFIG & API LAYER ==================== */

// Global Chart.js responsiveness fix: the default maintainAspectRatio:true
// locks every chart to its INITIAL canvas width/height ratio, which looks
// fine on desktop but produces badly squished or oversized charts once the
// same canvas is viewed on a narrower tablet/mobile width. Setting this
// once, globally, makes every Chart.js instance in the app (sales trend,
// category doughnut, the 3 sales-breakdown bar charts) fill its container
// height responsively instead — paired with the CSS max-height rule on
// .chart-card canvas so the container itself has a sane bound.
if (typeof Chart !== "undefined") {
    Chart.defaults.responsive = true;
    Chart.defaults.maintainAspectRatio = false;
}

const API_BASE = window.location.origin + "/api/v1";

const ENDPOINTS = {
    register:      API_BASE + "/auth/register",
    login:         API_BASE + "/auth/login",
    logout:        API_BASE + "/auth/logout",
    forgotPassword: API_BASE + "/auth/forgot-password",
    resetPassword:  API_BASE + "/auth/reset-password",
    dashboard:               API_BASE + "/dashboard",
    dashboardRecentInvoices: API_BASE + "/dashboard/recent-invoices",
    dashboardLowStock:       API_BASE + "/dashboard/low-stock",
    dashboardSalesSummary:   API_BASE + "/dashboard/sales-summary",
    dashboardTopProducts:    API_BASE + "/dashboard/top-products",
    dashboardPaymentSummary: API_BASE + "/dashboard/payment-summary",
    users:         API_BASE + "/users",
    products:      API_BASE + "/products",
    productPricing: API_BASE + "/product-pricing",
    salesReturns:  API_BASE + "/sales-returns",
    purchaseReturns: API_BASE + "/purchase-returns",
    categories:    API_BASE + "/categories",
    distributors:  API_BASE + "/distributors",
    superStockists: API_BASE + "/super-stockists",
    productRequests: API_BASE + "/product-requests",
    warehouse: API_BASE + "/warehouse",
    stockTransfers: API_BASE + "/stock-transfers",
    notifications: API_BASE + "/notifications",
    notificationsUnreadCount: API_BASE + "/notifications/unread-count",
    distributorAssignments: API_BASE + "/distributor-assignments",
    shops:         API_BASE + "/shops",
    invoices:      API_BASE + "/invoices",
    payments:      API_BASE + "/payments",
    paymentProofs: API_BASE + "/payment-proofs",
    reports:       API_BASE + "/reports",
    auditLogs:     API_BASE + "/audit-logs",
    companySettings: API_BASE + "/company-settings",
};

function getToken(){ return localStorage.getItem("brisk_token"); }
function setToken(t){ localStorage.setItem("brisk_token", t); }
function clearToken(){ localStorage.removeItem("brisk_token"); localStorage.removeItem("brisk_user"); }
function getCurrentUser(){
    try{ return JSON.parse(localStorage.getItem("brisk_user")) || null; }catch(e){ return null; }
}
function setCurrentUser(u){ localStorage.setItem("brisk_user", JSON.stringify(u)); }

/**
 * apiRequest — wraps fetch() with the Authorization Bearer header and
 * unwraps the backend's ApiResponse<T> envelope ({success, message, data}).
 *
 * There is NO offline/demo fallback. If the network call fails or the
 * backend returns a non-2xx status, this throws an Error whose .message is
 * the backend's own error message (GlobalExceptionHandler always returns
 * {message, status, error, path}) so callers can show it verbatim instead
 * of silently pretending the operation succeeded.
 */
async function apiRequest(url, { method = "GET", body = null, auth = true } = {}){
    const headers = { "Content-Type": "application/json" };
    if (auth && getToken()) headers["Authorization"] = "Bearer " + getToken();

    let response;
    try{
        response = await fetch(url, {
            method,
            headers,
            body: body ? JSON.stringify(body) : undefined,
        });
    }catch(networkErr){
        const err = new Error("Could not reach the server. Please check that the backend is running and try again.");
        err.status = 0;
        throw err;
    }

    let data = null;
    try { data = await response.json(); } catch(e){ /* empty body */ }

    if (!response.ok){
        const message = (data && (data.message || data.error)) || `Request failed (${response.status})`;
        const err = new Error(message);
        err.status = response.status;
        err.data = data;
        throw err;
    }
    return data;
}

/**
 * apiRequestMultipart — same contract as apiRequest but for FormData bodies
 * (file uploads). No Content-Type header is set so the browser attaches the
 * correct multipart boundary automatically.
 */
async function apiRequestMultipart(url, formData, method = "POST"){
    const headers = {};
    if (getToken()) headers["Authorization"] = "Bearer " + getToken();
    let response;
    try{
        response = await fetch(url, { method, headers, body: formData });
    }catch(networkErr){
        const err = new Error("Could not reach the server. Please check that the backend is running and try again.");
        err.status = 0;
        throw err;
    }
    let data = null;
    try { data = await response.json(); } catch(e){ /* empty body */ }
    if (!response.ok){
        const message = (data && (data.message || data.error)) || `Request failed (${response.status})`;
        const err = new Error(message);
        err.status = response.status;
        err.data = data;
        throw err;
    }
    return data;
}

/** unwrap — pulls .data out of an ApiResponse envelope, defaulting to a fallback. */
function unwrap(res, fallback){
    return (res && Object.prototype.hasOwnProperty.call(res, "data") && res.data !== undefined && res.data !== null) ? res.data : fallback;
}

/**
 * apiDownloadFile — GET a binary export (PDF/Excel) with the auth header
 * attached and trigger a browser download. Export endpoints return the raw
 * file bytes (not an ApiResponse envelope), so this bypasses apiRequest's
 * JSON parsing entirely.
 */
async function apiDownloadFile(url, filename){
    const headers = {};
    if (getToken()) headers["Authorization"] = "Bearer " + getToken();
    let response;
    try{
        response = await fetch(url, { method: "GET", headers });
    }catch(networkErr){
        showToast("Download failed", "Could not reach the server.", "danger");
        return;
    }
    if (!response.ok){
        showToast("Download failed", `Request failed (${response.status})`, "danger");
        return;
    }
    const blob = await response.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = blobUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(blobUrl);
}

/* ==================== 2. UI HELPERS ==================== */
function showSpinner(){ document.getElementById("globalSpinner").classList.remove("d-none"); }
function hideSpinner(){ document.getElementById("globalSpinner").classList.add("d-none"); }

function showToast(title, message, type = "success"){
    const icons = { success:"fa-circle-check text-success", error:"fa-circle-xmark text-danger", warning:"fa-triangle-exclamation text-warning", info:"fa-circle-info text-info" };
    const id = "toast-" + Date.now();
    const toastHtml = `
    <div class="toast glass-card" id="${id}" role="alert">
      <div class="d-flex align-items-center p-3">
        <i class="fa-solid ${icons[type] || icons.success} me-2 fs-5"></i>
        <div class="flex-grow-1">
          <div class="fw-600" style="font-size:13.5px">${escapeHtml(title)}</div>
          <div style="font-size:12.5px; color:var(--text-muted)">${escapeHtml(message)}</div>
        </div>
        <button type="button" class="btn-close ms-2" data-bs-dismiss="toast"></button>
      </div>
    </div>`;
    document.getElementById("toastContainer").insertAdjacentHTML("beforeend", toastHtml);
    const el = document.getElementById(id);
    const toast = new bootstrap.Toast(el, { delay: 4500 });
    toast.show();
    el.addEventListener("hidden.bs.toast", () => el.remove());
}

/** showApiError — standard handler for a failed CRUD call: surfaces the
 * backend's own error message instead of silently updating the UI. */
function showApiError(err, fallbackTitle = "Request failed"){
    showToast(fallbackTitle, (err && err.message) || "Something went wrong.", "error");
}

let confirmCallback = null;
function showConfirm(title, body, onConfirm){
    document.getElementById("confirmTitle").textContent = title;
    document.getElementById("confirmBody").textContent = body;
    confirmCallback = onConfirm;
    new bootstrap.Modal(document.getElementById("confirmModal")).show();
}
document.getElementById("confirmActionBtn").addEventListener("click", () => {
    if (typeof confirmCallback === "function") confirmCallback();
    bootstrap.Modal.getInstance(document.getElementById("confirmModal"))?.hide();
});

function escapeHtml(s){
    return String(s ?? "").replace(/[&<>"']/g, c => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;" }[c]));
}
function formatCurrency(n){
    return "₹" + Number(n || 0).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}
function formatDate(d){
    if (!d) return "--";
    const date = new Date(d);
    if (isNaN(date.getTime())) return "--";
    return date.toLocaleDateString("en-IN", { day:"2-digit", month:"short", year:"numeric" });
}
function normalizeStatusLabel(status){
    // Backend enums/free-text arrive as PAID / UNPAID / PARTIALLY_PAID,
    // ACTIVE / INACTIVE, COMPLETED / PENDING etc. Normalize to the labels
    // the existing .status-* CSS classes already style, so real API data
    // renders with the correct badge color without touching any CSS.
    const s = String(status || "").trim().toUpperCase().replace(/\s+/g, "_");
    if (s === "PAID" || s === "COMPLETED" || s === "TRUE" || s === "ACTIVE") return s === "ACTIVE" ? "Active" : (s === "COMPLETED" ? "Paid" : "Paid");
    if (s === "UNPAID" || s === "PENDING" || s === "FALSE" || s === "INACTIVE") return s === "INACTIVE" ? "Inactive" : "Unpaid";
    if (s === "PARTIAL" || s === "PARTIALLY_PAID") return "Partial";
    return status || "—";
}
function statusBadge(status){
    const cls = { Active:"status-active", Inactive:"status-inactive", Paid:"status-paid", Unpaid:"status-unpaid", Partial:"status-partial" };
    const label = normalizeStatusLabel(status);
    return `<span class="status-badge ${cls[label] || "status-active"}">${label}</span>`;
}
function activeBadge(active){
    return statusBadge(active ? "Active" : "Inactive");
}
function initials(name){
    return (name || "U").split(" ").map(w=>w[0]).slice(0,2).join("").toUpperCase();
}
function avatarUrl(seed){
    return `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(seed || "U")}&backgroundColor=9CCBFF,B8E0D2,D9C9F5,F8B4B4`;
}

/* ==================== 3. AUTH ==================== */
const authWrapper = document.getElementById("authWrapper");
const appWrapper = document.getElementById("appWrapper");
const loginForm = document.getElementById("loginForm");
const registerForm = document.getElementById("registerForm");

document.getElementById("showRegister").addEventListener("click", (e) => {
    e.preventDefault();
    new bootstrap.Modal(document.getElementById("registerModal")).show();
});
document.getElementById("showLogin").addEventListener("click", (e) => {
    e.preventDefault();
    bootstrap.Modal.getInstance(document.getElementById("registerModal"))?.hide();
});

// password visibility toggles
document.querySelectorAll(".toggle-eye").forEach(eye => {
    eye.addEventListener("click", () => {
        const input = document.getElementById(eye.dataset.target);
        if (input.type === "password"){ input.type = "text"; eye.classList.replace("fa-eye","fa-eye-slash"); }
        else { input.type = "password"; eye.classList.replace("fa-eye-slash","fa-eye"); }
    });
});

// Forgot password popup — POST /auth/forgot-password
document.getElementById("showForgotPassword").addEventListener("click", (e) => {
    e.preventDefault();
    new bootstrap.Modal(document.getElementById("forgotPasswordModal")).show();
});
document.getElementById("forgotPasswordForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = document.getElementById("forgotEmail").value.trim();
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.forgotPassword, { method:"POST", auth:false, body:{ email } });
        bootstrap.Modal.getInstance(document.getElementById("forgotPasswordModal"))?.hide();
        e.target.reset();
        showToast("Reset link sent", (res && res.message) || `If an account exists for ${email}, a password reset link has been sent.`, "success");
    }catch(err){
        showApiError(err, "Could not send reset link");
    }finally{
        hideSpinner();
    }
});

// Set new password — POST /auth/reset-password. Real flow: the emailed link
// opens this page with ?token=... in the URL; that token is read on load
// (see bottom of this file) into the hidden #resetPasswordToken field.
document.getElementById("resetPasswordForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const token = document.getElementById("resetPasswordToken").value.trim();
    const newPassword = document.getElementById("resetNewPassword").value;
    const confirmPassword = document.getElementById("resetConfirmPassword").value;
    if (newPassword !== confirmPassword){
        showToast("Password mismatch", "New password and confirmation must match.", "error");
        return;
    }
    if (!token){
        showToast("Missing token", "This reset link is invalid or has expired. Please request a new one.", "error");
        return;
    }
    showSpinner();
    try{
        await apiRequest(ENDPOINTS.resetPassword, { method:"POST", auth:false, body:{ token, newPassword } });
        bootstrap.Modal.getInstance(document.getElementById("resetPasswordModal"))?.hide();
        e.target.reset();
        showToast("Password reset", "Your password has been updated. Please sign in.", "success");
    }catch(err){
        showApiError(err, "Could not reset password");
    }finally{
        hideSpinner();
    }
});

// If the page was opened from a password-reset email link, pick up the
// token and open the "set new password" modal automatically.
(function checkResetTokenInUrl(){
    const params = new URLSearchParams(window.location.search);
    const token = params.get("token");
    if (token){
        document.getElementById("resetPasswordToken").value = token;
        document.addEventListener("DOMContentLoaded", () => {
            new bootstrap.Modal(document.getElementById("resetPasswordModal")).show();
        });
    }
})();

// Remember Me — restores the last used username on page load
const rememberedUsername = localStorage.getItem("brisk_remembered_username");
if (rememberedUsername){
    document.getElementById("loginUsername").value = rememberedUsername;
    document.getElementById("rememberMe").checked = true;
}

registerForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const requestedRole = document.getElementById("regRequestedRole").value;
    const fullName = document.getElementById("regFullName").value.trim();
    const username = document.getElementById("regUsername").value.trim();
    const email = document.getElementById("regEmail").value.trim();
    const phone = document.getElementById("regPhone").value.trim();
    const password = document.getElementById("regPassword").value;
    const confirmPassword = document.getElementById("regConfirmPassword").value;

    if (password !== confirmPassword){
        showToast("Password mismatch", "Password and Confirm Password must match.", "error");
        return;
    }

    showSpinner();
    try{
        await apiRequest(ENDPOINTS.register, { method:"POST", auth:false, body:{ fullName, username, email, phone, password, requestedRole } });
        showToast("Account created", "Your account was registered successfully. An administrator must approve it before you can sign in.", "success");
        registerForm.reset();
        bootstrap.Modal.getInstance(document.getElementById("registerModal"))?.hide();
        document.getElementById("loginUsername").value = username;
    }catch(err){
        showApiError(err, "Registration failed");
    }finally{
        hideSpinner();
    }
});

/* ---- Role tabs: cosmetic front door for the two separate logins ----
   There's a single /auth/login endpoint; the tab just sets what this
   session expects (shown in the copy) and is checked against the role
   the backend actually returns, so an Admin can't end up signed into
   the Distributor portal (or vice versa) by picking the wrong tab. */
let selectedLoginRole = "ADMIN";
const ROLE_TAB_COPY = {
    ADMIN:       { title:"Admin console",      sub:"Sign in with your admin credentials",       headline:"One hub, every shop, always in sync.",        subCaption:"Stock, invoices and payments move through the same rail your distributors see in real time." },
    SUPER_STOCKIST: { title:"Super Stockist portal", sub:"Sign in to manage your assigned distributors", headline:"Your distributors. Your warehouse. One view.", subCaption:"Every Super Stockist sees only the distributors assigned to them — isolated by design." },
    DISTRIBUTOR: { title:"Distributor portal", sub:"Sign in to manage your own shops & invoices", headline:"Your shops. Your invoices. Nobody else's.", subCaption:"Every distributor sees only their own network — isolated by design, never shared." }
};
function setLoginRoleTab(role){
    selectedLoginRole = role;
    document.querySelectorAll(".role-tab").forEach(btn => {
        const active = btn.dataset.role === role;
        btn.classList.toggle("active", active);
        btn.setAttribute("aria-selected", active ? "true" : "false");
    });
    const copy = ROLE_TAB_COPY[role];
    document.getElementById("loginTitle").textContent = copy.title;
    document.getElementById("loginSub").textContent = copy.sub;
    const headlineEl = document.getElementById("sceneHeadline");
    const subEl = document.getElementById("sceneSub");
    if (headlineEl) headlineEl.textContent = copy.headline;
    if (subEl) subEl.textContent = copy.subCaption;
}
document.querySelectorAll(".role-tab").forEach(btn => {
    btn.addEventListener("click", () => setLoginRoleTab(btn.dataset.role));
});

loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const username = document.getElementById("loginUsername").value.trim();
    const password = document.getElementById("loginPassword").value;
    const rememberMe = document.getElementById("rememberMe").checked;

    if (rememberMe) localStorage.setItem("brisk_remembered_username", username);
    else localStorage.removeItem("brisk_remembered_username");

    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.login, { method:"POST", auth:false, body:{ username, password } });
        // Backend wraps the payload as ApiResponse<LoginResponse> = { success, message, data:{ userId, token, username, role, ... } }.
        const payload = unwrap(res, null);
        if (!payload || !payload.token) throw new Error((res && res.message) || "Login failed. Please check your credentials.");

        const actualRole = String(payload.role || "").toUpperCase().replace("ROLE_", "");
        if (selectedLoginRole === "ADMIN" && actualRole !== "ADMIN" && actualRole !== "SUPER_ADMIN"){
            throw new Error("This account isn't an admin account. Switch to the correct tab to sign in.");
        }
        if (selectedLoginRole === "SUPER_STOCKIST" && actualRole !== "SUPER_STOCKIST"){
            throw new Error("This is not a Super Stockist account. Switch to the correct tab to sign in.");
        }
        if (selectedLoginRole === "DISTRIBUTOR" && actualRole !== "DISTRIBUTOR"){
            throw new Error("This is not a distributor account. Switch to the correct tab to sign in.");
        }

        setToken(payload.token);
        setCurrentUser({
            id: payload.userId ?? null,
            fullName: payload.fullName || username,
            username: payload.username || username,
            email: payload.email || "",
            role: payload.role || "",
            distributorId: payload.distributorId ?? null,
            distributorName: payload.distributorName || "",
            superStockistId: payload.superStockistId ?? null,
            superStockistName: payload.superStockistName || "",
            themePreference: payload.themePreference || "light",
            fontSizePreference: payload.fontSizePreference || "medium",
            profileImage: payload.profileImage || null,
        });
        enterApp();
    }catch(err){
        showToast("Login failed", err.message || "Invalid credentials.", "error");
    }finally{
        hideSpinner();
    }
});

/**
 * Hides sidebar sections marked data-role-only="ADMIN" for anyone who
 * isn't logged in as an admin, and updates the topbar role label. This is
 * a UI convenience only — actual data access is enforced server-side
 * (SecurityUtils scoping + @PreAuthorize), so hiding a link here is never
 * the thing standing between a distributor and another distributor's data.
 */
// Single source of truth for "is this an admin role?" -- used by both the
// sidebar visibility pass and the Settings pane switch, so the two can
// never disagree about what a role string means (ROLE_ prefix, casing).
function normalizeRole(role){
    return String(role || "").toUpperCase().replace("ROLE_", "");
}
function isAdminRoleName(role){
    const n = normalizeRole(role);
    return n === "ADMIN" || n === "SUPER_ADMIN";
}

function applyRoleVisibility(role){
    const normalized = normalizeRole(role);
    const isAdminRole = isAdminRoleName(role);

    document.querySelectorAll("[data-role-only]").forEach(el => {
        const allowed = el.dataset.roleOnly.split(",").map(r => r.trim());
        const show = isAdminRole || allowed.includes(normalized);
        el.classList.toggle("d-none", !show);
    });

    // Self-service pages exclusive to one non-admin role (My Distributors,
    // Warehouse Stock, Stock Requests, etc.) never fall under Admin's
    // "sees everything" bypass above — Admin has its own management pages
    // instead, and these pages call endpoints scoped to a distributor_id/
    // super_stockist_id an Admin login doesn't have.
    document.querySelectorAll("[data-exclusive-role]").forEach(el => {
        el.classList.toggle("d-none", el.dataset.exclusiveRole !== normalized);
    });

    // A .nav-group container itself is only marked data-role-only when
    // ALL its children are admin-only (User Management, Inventory) — for
    // mixed groups like Network (Shops is also DISTRIBUTOR-visible), hide
    // the group header too if this role can't see a single item inside it,
    // instead of showing an empty dropdown.
    document.querySelectorAll(".nav-group").forEach(group => {
        if (group.classList.contains("d-none")) return; // already hidden by its own data-role-only
        const anyVisible = Array.from(group.querySelectorAll(".nav-submenu .nav-link"))
            .some(link => !link.classList.contains("d-none"));
        group.classList.toggle("d-none", !anyVisible);
    });

    const roleLabelEl = document.getElementById("topbarUserRole");
    if (roleLabelEl){
        roleLabelEl.textContent = isAdminRole ? "Administrator" : (normalized === "SUPER_STOCKIST" ? "Super Stockist" : "Distributor");
    }

    // If a distributor's stored last-active section happens to be an
    // admin-only one (e.g. after a role change), fall back to a section
    // this role can actually load — Dashboard's APIs are ADMIN-only, so
    // it's not a safe fallback for non-admins even though its nav link
    // isn't itself marked data-role-only.
    const activeLink = document.querySelector(".nav-link.active[data-section]");
    if (activeLink && activeLink.classList.contains("d-none")){
        showSection("dashboard");
    }
}

function enterApp(){
    const user = getCurrentUser();
    authWrapper.classList.add("d-none");
    appWrapper.classList.remove("d-none");
    if (user){
        document.getElementById("topbarUserName").textContent = user.fullName || user.username;
        document.getElementById("topbarAvatar").src = user.profileImage || avatarUrl(user.fullName || user.username);
        applyRoleVisibility(user.role);
        settingsApplyRole();
        applyDisplayPreferences(user.themePreference || "light", user.fontSizePreference || "medium");
    }
    loadAllModules();
    // Dashboard's APIs are entirely ADMIN-only on the backend
    // (DashboardController has a class-level @PreAuthorize("hasRole('ADMIN')")),
    // but the Dashboard nav link itself isn't marked data-role-only, so a
    // DISTRIBUTOR login was always landing on a page it can't actually load.
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";
    showSection("dashboard");
}

function doLogout(){
    // Best-effort call so the logout gets audit-logged server-side; never
    // blocks the actual client-side logout if this fails (e.g. token already expired).
    apiRequest(ENDPOINTS.logout, { method: "POST" }).catch(() => {});
    clearToken();
    appWrapper.classList.add("d-none");
    authWrapper.classList.remove("d-none");
    loginForm.reset();
    setLoginRoleTab("ADMIN");
    showToast("Signed out", "You have been logged out successfully.", "info");
}
document.getElementById("logoutBtn").addEventListener("click", (e) => { e.preventDefault(); showConfirm("Confirm Logout","Are you sure you want to sign out?", doLogout); });
document.getElementById("logoutBtn2").addEventListener("click", (e) => { e.preventDefault(); showConfirm("Confirm Logout","Are you sure you want to sign out?", doLogout); });

function checkSession(){
    if (getToken()){ enterApp(); }
}

/* ==================== 4. APP SHELL: SIDEBAR / TOPBAR / THEME / ROUTER ==================== */
const sidebar = document.getElementById("sidebar");
const mainContent = document.querySelector(".main-content");

document.getElementById("collapseBtn").addEventListener("click", () => {
    sidebar.classList.toggle("collapsed");
    mainContent.classList.toggle("expanded");
});

document.getElementById("mobileMenuBtn").addEventListener("click", () => {
    sidebar.classList.add("mobile-open");
    document.getElementById("sidebarOverlay").classList.add("show");
});
document.getElementById("sidebarOverlay").addEventListener("click", () => {
    sidebar.classList.remove("mobile-open");
    document.getElementById("sidebarOverlay").classList.remove("show");
});

// Theme (light/dark)
document.getElementById("themeToggleBtn").addEventListener("click", () => {
    document.body.classList.toggle("dark-mode");
    const icon = document.querySelector("#themeToggleBtn i");
    icon.classList.toggle("fa-moon");
    icon.classList.toggle("fa-sun");
    localStorage.setItem("brisk_dark", document.body.classList.contains("dark-mode") ? "1" : "0");
});
if (localStorage.getItem("brisk_dark") === "1"){
    document.body.classList.add("dark-mode");
    document.querySelector("#themeToggleBtn i").classList.replace("fa-moon","fa-sun");
}

// Accent theme swatches
document.querySelectorAll(".theme-swatch").forEach(sw => {
    sw.addEventListener("click", () => {
        document.querySelectorAll(".theme-swatch").forEach(s => s.classList.remove("active"));
        sw.classList.add("active");
        document.body.setAttribute("data-theme", sw.dataset.theme);
        localStorage.setItem("brisk_accent", sw.dataset.theme);
    });
});
if (localStorage.getItem("brisk_accent")){
    document.body.setAttribute("data-theme", localStorage.getItem("brisk_accent"));
    document.querySelectorAll(".theme-swatch").forEach(s => s.classList.toggle("active", s.dataset.theme === localStorage.getItem("brisk_accent")));
}

// Router
function showSection(name){
    document.querySelectorAll(".page-section").forEach(s => s.classList.add("d-none"));
    document.getElementById("section-" + name)?.classList.remove("d-none");
    document.querySelectorAll(".nav-link[data-section]").forEach(l => l.classList.toggle("active", l.dataset.section === name));
    syncNavGroupWithActiveSection(name);
    sidebar.classList.remove("mobile-open");
    document.getElementById("sidebarOverlay").classList.remove("show");

    const refreshers = {
        dashboard: renderDashboard, users: () => loadAndRenderCrud("users"),
        distributors: () => loadAndRenderCrud("distributors"), products: () => loadAndRenderCrud("products"),
        categories: () => loadAndRenderCrud("categories"), shops: () => loadAndRenderCrud("shops"),
        invoices: loadAndRenderInvoices, payments: loadAndRenderPayments, "sales-returns": loadAndRenderSalesReturns, reports: () => {}, settings: () => {},
        approvals: loadAndRenderApprovals,
        "stock-summary": loadAndRenderStockSummary,
        "product-ledger": loadAndRenderProductLedger,
        "my-stock-ledger": loadAndRenderMyStockLedger,
        "sales-analysis": loadAndRenderSalesAnalysis,
        "account-ledger": loadAndRenderAccountLedger,
        "customer-ledger": loadAndRenderCustomerLedger,
        "supplier-ledger": loadAndRenderSupplierLedger,
        "outstanding-report": loadAndRenderOutstandingReport,
        "cash-book": loadAndRenderCashBook,
        "bank-book": loadAndRenderBankBook,
        "day-book": loadAndRenderDayBook,
        auditlog: loadAndRenderAuditLog,
        superstockists: () => loadAndRenderCrud("superstockists"),
        "ss-distributors": loadAndRenderMyDistributors,
        "distributor-assignments": loadAndRenderAssignments,
        "admin-stock-requests": loadAndRenderAdminReq,
        "ss-warehouse": loadAndRenderSsWarehouse,
        "dist-warehouse": loadAndRenderDistWarehouse,
        "admin-warehouse": loadAndRenderAdminWarehouse,
        settings: () => { const u = getCurrentUser(); if (isAdminRoleName(u && u.role)) loadCompanySettingsIntoForm(); else loadPersonalSettings(); },
        "mrp-stock": loadAndRenderMrpStock,
        "dist-sales-return": loadAndRenderDistSalesReturn,
        "dist-purchase-return": loadAndRenderDistPurchaseReturn,
        "ss-sales-return": loadAndRenderSsSalesReturn,
        "ss-purchase-return": loadAndRenderSsPurchaseReturn,
        "admin-return-history": () => loadAndRenderAdminReturnHistory(false),
        "billed-to-me": loadAndRenderBilledToMe,
        "ss-distributor-requests": loadAndRenderSsDistReq,
        "ss-admin-requests": loadAndRenderSsAdminReq,
        "ss-stock-transfers": loadAndRenderSsTransfers,
        "ss-dispatch-history": loadAndRenderSsDispatch,
        "ss-notifications": () => loadAndRenderNotifications("ss"),
        "dist-stock-requests": loadAndRenderDistReq,
        "dist-assignments": loadAndRenderDistAssignments,
        "dist-notifications": () => loadAndRenderNotifications("dist"),
    };
    window.__DMS_REFRESHERS__ = refreshers;
    window.__DMS_CURRENT_SECTION__ = name;
    refreshers[name]?.();
}
document.querySelectorAll("[data-section]").forEach(el => {
    el.addEventListener("click", (e) => { e.preventDefault(); showSection(el.dataset.section); });
});

/* ---------------- Network auto refresh ----------------
   Two parts:
   1. Periodic refresh: every 45s, silently re-runs the currently visible
      section's own loader (the same function showSection() already calls
      when the user navigates there) so lists/dashboards/ledgers stay
      live without a manual reload -- skipped while the tab is hidden
      (backgrounded) or the browser reports itself offline, so it never
      fights a page the user isn't even looking at or piles up failed
      requests while disconnected.
   2. Connectivity banner: the browser's online/offline events show a
      fixed banner so the user knows why requests might be failing, and
      immediately re-run the current section's loader the moment
      connectivity returns, instead of leaving stale data on screen
      until their next manual action. */
(function setupNetworkAutoRefresh(){
    // 20s, not 45s: mutations made in THIS session already refresh
    // instantly (every create/delete calls refreshDashboard), so this poll
    // only covers changes made elsewhere -- another user approving a
    // return, a distributor raising an invoice. 20s keeps that close to
    // live without hammering the API, and it is skipped entirely while the
    // tab is backgrounded or offline.
    const AUTO_REFRESH_MS = 20000;

    const banner = document.createElement("div");
    banner.id = "networkStatusBanner";
    banner.setAttribute("role", "status");
    banner.style.cssText = "position:fixed;top:0;left:0;right:0;z-index:2000;display:none;text-align:center;"
        + "padding:8px 12px;font-size:13px;font-weight:600;color:#fff;background:#dc2626;";
    banner.textContent = "You're offline — changes won't save until your connection is back.";
    document.body.appendChild(banner);

    function runCurrentSectionRefresher(){
        const name = window.__DMS_CURRENT_SECTION__;
        const fn = name && window.__DMS_REFRESHERS__ && window.__DMS_REFRESHERS__[name];
        if (typeof fn === "function") fn();
    }

    window.addEventListener("online", () => {
        banner.style.display = "none";
        showToast("Back online", "Connection restored — refreshing this page's data.", "success");
        runCurrentSectionRefresher();
    });
    window.addEventListener("offline", () => {
        banner.style.display = "block";
    });
    if (!navigator.onLine) banner.style.display = "block";

    setInterval(() => {
        if (document.visibilityState !== "visible") return;
        if (!navigator.onLine) return;
        runCurrentSectionRefresher();
        // The bell is part of the topbar, not any one section, so it
        // refreshes on every tick regardless of which section is active
        // (only once logged in — the bell markup only exists in appWrapper).
        if (getToken() && typeof loadAndRenderNotificationBell === "function") loadAndRenderNotificationBell();
    }, AUTO_REFRESH_MS);
})();

/* ---------------- Collapsible sidebar groups (accordion) ----------------
   Only one group open at a time; the open group persists across page
   refresh via localStorage; the group containing the current page's
   active link is auto-opened and visually highlighted. */
const NAV_GROUP_STORAGE_KEY = "brisk_open_nav_group";

function setOpenNavGroup(groupName, { persist = true } = {}){
    document.querySelectorAll(".nav-group").forEach(g => {
        const isTarget = g.dataset.group === groupName;
        g.classList.toggle("open", isTarget);
        g.querySelector(".nav-group-toggle")?.setAttribute("aria-expanded", String(isTarget));
    });
    if (persist){
        if (groupName) localStorage.setItem(NAV_GROUP_STORAGE_KEY, groupName);
        else localStorage.removeItem(NAV_GROUP_STORAGE_KEY);
    }
}

document.querySelectorAll(".nav-group-toggle").forEach(btn => {
    btn.addEventListener("click", () => {
        const group = btn.closest(".nav-group");
        const isOpen = group.classList.contains("open");
        setOpenNavGroup(isOpen ? null : group.dataset.group);
    });
});

/** Opens/highlights whichever group contains the given section's nav-link, called by showSection(). */
function syncNavGroupWithActiveSection(sectionName){
    document.querySelectorAll(".nav-group").forEach(g => g.classList.remove("has-active"));
    const activeLink = document.querySelector(`.nav-link[data-section="${sectionName}"]`);
    const parentGroup = activeLink?.closest(".nav-group");
    if (parentGroup){
        parentGroup.classList.add("has-active");
        setOpenNavGroup(parentGroup.dataset.group);
    }
}

// Restore whichever group was open before the last refresh (if it still
// exists / is visible for this role — applyRoleVisibility runs separately).
const savedOpenGroup = localStorage.getItem(NAV_GROUP_STORAGE_KEY);
if (savedOpenGroup) setOpenNavGroup(savedOpenGroup, { persist: false });
document.querySelectorAll('[data-action="outstanding-details"]').forEach(el => {
    el.addEventListener("click", (e) => { e.preventDefault(); openOutstandingDetailsModal(); });
});

/** Outstanding KPI drill-down: who owes money, district, products, total pending. */
async function openOutstandingDetailsModal(){
    const modalEl = document.getElementById("outstandingDetailsModal");
    const tbody = document.getElementById("outstandingDetailsBody");
    tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;
    new bootstrap.Modal(modalEl).show();

    try {
        const res = await apiRequest(ENDPOINTS.dashboard + "/outstanding-details");
        const rows = res?.data || [];

        if (!rows.length) {
            tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">No outstanding balances 🎉</td></tr>`;
            return;
        }

        tbody.innerHTML = rows.map(r => `
            <tr>
                <td class="fw-600">${escapeHtml(r.partyName ?? "-")}</td>
                <td><span class="badge bg-secondary-subtle text-secondary-emphasis">${escapeHtml(r.partyType ?? "-")}</span></td>
                <td>${escapeHtml(r.ownerOrContact ?? "-")}<br><small class="text-muted">${escapeHtml(r.mobileNumber ?? "")}</small></td>
                <td>${escapeHtml(r.district ?? "-")}${r.state ? `, ${escapeHtml(r.state)}` : ""}</td>
                <td>${(r.products && r.products.length) ? escapeHtml(r.products.join(", ")) : "-"}</td>
                <td class="text-center">${r.pendingInvoiceCount ?? 0}</td>
                <td class="text-end fw-600 text-danger">${formatCurrency(r.totalOutstanding)}</td>
            </tr>
        `).join("");
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Failed to load outstanding details.</td></tr>`;
    }
}

/* ==================== 5. BACKEND DATA CACHE (STATE) ====================
   STATE is populated ONLY by GET responses from the backend. Nothing ever
   writes into these arrays directly after a Create/Update/Delete — every
   mutation is followed by re-fetching the relevant list from the API. This
   is what keeps every page (including the Dashboard) showing the same
   MySQL-backed data at all times. */
const STATE = {
    users: [],
    distributors: [],
    products: [],
    categories: [],
    shops: [],
    invoices: [],
    billedToMe: [],
    payments: [],
    salesReturns: [],
    superStockists: [],
};

/* ==================== 6. GENERIC CRUD ENGINE ==================== */
// Each module config describes exactly how to talk to its real REST
// endpoint (field names below match the backend's *Request / *Response DTOs
// exactly — see UserRequest, DistributorRequest, ProductRequest,
// CategoryRequest, ShopRequest in com.spartan.dms.dto).
/* ==================== DISTRIBUTOR APPROVAL ====================
   Reuses GET /api/v1/users (already loaded for the Users page) and filters
   to DISTRIBUTOR-role accounts client-side — same real data, no separate
   endpoint needed. Approve/reject call the new PATCH endpoints. */
async function loadAndRenderApprovals(){
    if (!STATE.users.length) await loadAndRenderCrud("users");
    renderApprovals();
}

function renderApprovals(){
    const term = (document.getElementById("approvalsSearch").value || "").toLowerCase();
    const statusFilter = document.getElementById("approvalsStatusFilter").value;

    const rows = STATE.users.filter(u => {
        const role = String(u.role || "").toUpperCase().replace("ROLE_", "");
        if (role !== "DISTRIBUTOR" && role !== "SUPER_STOCKIST") return false;
        const status = u.approvalStatus || "APPROVED";
        if (statusFilter && status !== statusFilter) return false;
        if (term && ![u.fullName, u.username, u.email].some(v => String(v||"").toLowerCase().includes(term))) return false;
        return true;
    });

    document.getElementById("approvalsTableBody").innerHTML = rows.map(u => {
        const role = String(u.role || "").toUpperCase().replace("ROLE_", "");
        const status = u.approvalStatus || "APPROVED";
        const badgeClass = status === "APPROVED" ? "bg-success" : status === "REJECTED" ? "bg-danger" : "bg-warning text-dark";
        const actions = status === "PENDING"
            ? `<button class="btn btn-sm btn-success me-1" data-approve="${u.id}" data-role="${role}"><i class="fa-solid fa-check"></i> Approve</button>
               <button class="btn btn-sm btn-outline-danger" data-reject="${u.id}"><i class="fa-solid fa-xmark"></i> Reject</button>`
            : status === "REJECTED"
                ? `<button class="btn btn-sm btn-success" data-approve="${u.id}" data-role="${role}"><i class="fa-solid fa-check"></i> Approve</button>`
                : `<span class="text-muted small">—</span>`;
        return `<tr data-row-id="${u.id}">
            <td>${escapeHtml(u.fullName)}</td>
            <td>${escapeHtml(u.username)}</td>
            <td>${escapeHtml(u.email)}</td>
            <td>${escapeHtml(u.mobileNumber || "—")}</td>
            <td><span class="badge bg-secondary">${role === "SUPER_STOCKIST" ? "Super Stockist" : "Distributor"}</span></td>
            <td>${formatDate(u.createdAt)}</td>
            <td><span class="badge ${badgeClass}">${status}</span></td>
            <td class="text-end">${actions}</td>
        </tr>`;
    }).join("") || `<tr><td colspan="8" class="text-center text-muted py-4">No registrations found.</td></tr>`;

    document.getElementById("approvalsTableBody").querySelectorAll("[data-approve]").forEach(btn => {
        btn.addEventListener("click", () => openApproveUserModal(Number(btn.dataset.approve), btn.dataset.role));
    });
    document.getElementById("approvalsTableBody").querySelectorAll("[data-reject]").forEach(btn => {
        btn.addEventListener("click", () => rejectDistributorUser(Number(btn.dataset.reject)));
    });
}

// Legacy accounts (created before profiles were auto-provisioned) hold a
// role but have no distributor / super stockist row, so they never appear
// on the assignment pages. This backfills them all in one pass; it is
// idempotent, so pressing it twice is harmless.
document.getElementById("repairProfilesBtn")?.addEventListener("click", () => {
    showConfirm("Repair account profiles",
        "This finds accounts that have a Distributor or Super Stockist role but no matching profile, and creates the missing profile so they can be assigned. Existing profiles are left untouched. Continue?",
        async () => {
            showSpinner();
            try{
                const res = await apiRequest(ENDPOINTS.users + "/repair-profiles", { method: "POST" });
                const fixed = unwrap(res, []);
                showToast("Repair complete",
                    fixed.length ? `${fixed.length} account(s) repaired.` : "Nothing needed repairing.",
                    "success");
                await loadAndRenderCrud("users");
                await loadAndRenderCrud("distributors");
                await loadAndRenderCrud("superstockists");
                renderApprovals();
            }catch(err){
                showApiError(err, "Could not repair profiles");
            }finally{
                hideSpinner();
            }
        });
});

document.getElementById("approvalsSearch").addEventListener("input", renderApprovals);
document.getElementById("approvalsStatusFilter").addEventListener("change", renderApprovals);

async function openApproveUserModal(userId, role){
    // The backend auto-creates the matching Distributor/Super Stockist
    // profile from the account's own details when no id is sent (see
    // UserService.approveUser -> findOrCreate*For). So "Create a new
    // profile" is the DEFAULT and always available -- approval is no
    // longer blocked when no profile exists yet, and the admin doesn't
    // have to pre-create + hand-link records just to approve someone.
    // Picking an existing profile stays available for the case where the
    // record was already created separately.
    const isSs = role === "SUPER_STOCKIST";
    const label = isSs ? "Super Stockist" : "Distributor";

    if (isSs && !STATE.superStockists.length) await loadAndRenderCrud("superstockists");
    if (!isSs && !STATE.distributors.length) await loadAndRenderCrud("distributors");

    const existing = isSs
        ? STATE.superStockists.map(s => `<option value="${s.id}">${escapeHtml(s.superStockistName)}</option>`).join("")
        : STATE.distributors.map(dd => `<option value="${dd.id}">${escapeHtml(dd.distributorName)}</option>`).join("");

    // Empty value = "create new" -> we omit the query param entirely.
    const options = `<option value="">➕ Create a new ${label} profile from this user's details</option>` + existing;

    showApproveModal(`Approve as ${label}`, isSs ? "approveSsSelect" : "approveDistSelect", options, async (selectedId) => {
        const param = selectedId
            ? (isSs ? "?superStockistId=" + selectedId : "?distributorId=" + selectedId)
            : "";
        await apiRequest(ENDPOINTS.users + "/" + userId + "/approve" + param, { method:"PATCH" });
        // Approval also CREATES the distributor / super stockist profile
        // server-side, so refresh those lists too -- otherwise the new
        // profile stays invisible (and unassignable) until a manual page
        // reload, which looks exactly like the approval silently failed.
        try{
            await loadAndRenderCrud(isSs ? "superstockists" : "distributors");
            await loadAndRenderCrud("users");
        }catch(e){ /* the approval itself already succeeded */ }
        showToast("Approved",
            `Account approved. The ${label} profile is now available for assignment.`, "success");
    });
}

function showApproveModal(title, selectId, optionsHtml, onConfirm){
    const modalHtml = `
        <div class="modal fade" id="approveDistModal" tabindex="-1">
            <div class="modal-dialog"><div class="modal-content">
                <div class="modal-header"><h5 class="modal-title">${title}</h5><button class="btn-close" data-bs-dismiss="modal"></button></div>
                <div class="modal-body">
                    <label class="form-label">${title}</label>
                    <select class="form-select" id="${selectId}">${optionsHtml}</select>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button class="btn btn-success" id="confirmApproveBtn"><i class="fa-solid fa-check me-1"></i> Approve</button>
                </div>
            </div></div>
        </div>`;
    document.getElementById("dynamicModalHost")?.remove();
    const host = document.createElement("div");
    host.id = "dynamicModalHost";
    host.innerHTML = modalHtml;
    document.body.appendChild(host);
    const modalEl = document.getElementById("approveDistModal");
    const modal = new bootstrap.Modal(modalEl);
    document.getElementById("confirmApproveBtn").addEventListener("click", async () => {
        const selectedId = document.getElementById(selectId).value;
        showSpinner();
        try{
            await onConfirm(selectedId);
            modal.hide();
            showToast("Account approved", "The account can now sign in.", "success");
            await loadAndRenderCrud("users");
            renderApprovals();
        }catch(err){
            showApiError(err, "Approval failed");
        }finally{
            hideSpinner();
        }
    });
    modalEl.addEventListener("hidden.bs.modal", () => host.remove());
    modal.show();
}

async function rejectDistributorUser(userId){
    if (!confirm("Reject this distributor registration? They will not be able to sign in.")) return;
    showSpinner();
    try{
        await apiRequest(ENDPOINTS.users + "/" + userId + "/reject", { method:"PATCH" });
        showToast("Registration rejected", "The account has been rejected.", "success");
        await loadAndRenderCrud("users");
        renderApprovals();
    }catch(err){
        showApiError(err, "Reject failed");
    }finally{
        hideSpinner();
    }
}

async function openAssignDistributorsModal(superStockistId){
    if (!STATE.distributors.length) await loadAndRenderCrud("distributors");

    const ss = STATE.superStockists.find(s => s.id == superStockistId);
    const assignedIds = new Set(STATE.distributors.filter(d => d.superStockistId == superStockistId).map(d => String(d.id)));

    const rowsHtml = STATE.distributors.map(d => `
        <div class="form-check">
            <input class="form-check-input" type="checkbox" value="${d.id}" id="assignDist${d.id}" ${assignedIds.has(String(d.id)) ? "checked" : ""}>
            <label class="form-check-label" for="assignDist${d.id}">${escapeHtml(d.distributorName)} ${d.superStockistId && d.superStockistId != superStockistId ? `<span class="text-muted small">(currently: ${escapeHtml(d.superStockistName||"—")})</span>` : ""}</label>
        </div>`).join("") || `<p class="text-muted">No distributors exist yet — create one first.</p>`;

    const modalHtml = `
        <div class="modal fade" id="assignDistModal" tabindex="-1">
            <div class="modal-dialog"><div class="modal-content">
                <div class="modal-header"><h5 class="modal-title">Assign Distributors — ${escapeHtml(ss ? ss.superStockistName : "")}</h5><button class="btn-close" data-bs-dismiss="modal"></button></div>
                <div class="modal-body" style="max-height:400px;overflow-y:auto">${rowsHtml}</div>
                <div class="modal-footer">
                    <button class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button class="btn btn-success" id="confirmAssignBtn"><i class="fa-solid fa-check me-1"></i> Save</button>
                </div>
            </div></div>
        </div>`;
    document.getElementById("dynamicModalHost")?.remove();
    const host = document.createElement("div");
    host.id = "dynamicModalHost";
    host.innerHTML = modalHtml;
    document.body.appendChild(host);
    const modalEl = document.getElementById("assignDistModal");
    const modal = new bootstrap.Modal(modalEl);
    document.getElementById("confirmAssignBtn").addEventListener("click", async () => {
        const distributorIds = Array.from(modalEl.querySelectorAll("input[type=checkbox]:checked")).map(cb => Number(cb.value));
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.superStockists + "/" + superStockistId + "/assign-distributors", { method:"POST", body:{ distributorIds } });
            modal.hide();
            showToast("Distributors assigned", "Assignment updated successfully.", "success");
            await loadAndRenderCrud("superstockists");
            await loadAndRenderCrud("distributors");
        }catch(err){
            showApiError(err, "Assignment failed");
        }finally{
            hideSpinner();
        }
    });
    modalEl.addEventListener("hidden.bs.modal", () => host.remove());
    modal.show();
}

document.getElementById("addSuperStockistBtn")?.addEventListener("click", () => openCrudModal("superstockists"));

/* ==================== SUPER STOCKIST: MY DISTRIBUTORS ====================
   Self-service view for a logged-in Super Stockist — GET /api/v1/super-stockists/me/distributors,
   scoped server-side to their own super_stockist_id via SecurityUtils. */
async function loadAndRenderMyDistributors(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.superStockists + "/me/distributors");
        const distributors = unwrap(res, []);
        document.getElementById("ssDistributorsTableBody").innerHTML = distributors.map(d => `
            <tr><td class="fw-600">${escapeHtml(d.distributorName)}</td><td>${escapeHtml(d.city || "—")}</td><td>${escapeHtml(d.mobileNumber)}</td><td>${activeBadge(d.active)}</td>
            <td class="text-end"><button class="action-btn view" data-view-dist-shops="${d.id}" data-dist-name="${escapeHtml(d.distributorName)}" title="View Shops"><i class="fa-solid fa-shop"></i></button></td></tr>
        `).join("") || `<tr><td colspan="5" class="text-center text-muted py-4">No distributors assigned to you yet.</td></tr>`;
        document.getElementById("ssDistributorsTableBody").querySelectorAll("[data-view-dist-shops]").forEach(btn => {
            btn.addEventListener("click", () => openDistributorShopsModal(Number(btn.dataset.viewDistShops), btn.dataset.distName));
        });
    }catch(err){
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        showApiError(err, "Could not load your distributors");
    }finally{
        hideSpinner();
    }
}

async function openDistributorShopsModal(distributorId, distributorName){
    document.getElementById("ssDistributorShopsTitle").textContent = `Shops — ${distributorName}`;
    const tbody = document.getElementById("ssDistributorShopsBody");
    tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted py-4">Loading...</td></tr>`;
    new bootstrap.Modal(document.getElementById("ssDistributorShopsModal")).show();
    try{
        const res = await apiRequest(ENDPOINTS.shops + "/distributor/" + distributorId);
        const shops = unwrap(res, []);
        tbody.innerHTML = shops.map(s => `
            <tr><td class="fw-600">${escapeHtml(s.shopName)}</td><td>${escapeHtml(s.city || "—")}</td><td>${escapeHtml(s.mobileNumber || "—")}</td><td>${activeBadge(s.active)}</td></tr>
        `).join("") || `<tr><td colspan="4" class="text-center text-muted py-4">No shops for this distributor.</td></tr>`;
    }catch(err){
        tbody.innerHTML = `<tr><td colspan="4" class="text-center text-danger py-4">Could not load shops.</td></tr>`;
    }
}

/* ==================== SHARED: STOCK REQUEST TABLE HELPERS ==================== */

function stockReqStatusBadge(status){
    const map = { PENDING:"bg-warning text-dark", APPROVED:"bg-info text-dark", FULFILLED:"bg-success", REJECTED:"bg-danger", CANCELLED:"bg-secondary" };
    return `<span class="badge ${map[status] || "bg-secondary"}">${escapeHtml(status || "—")}</span>`;
}

async function fetchProductRequests(params){
    const query = Object.entries(params || {}).filter(([,v]) => v !== undefined && v !== null && v !== "")
        .map(([k,v]) => `${k}=${encodeURIComponent(v)}`).join("&");
    const res = await apiRequest(ENDPOINTS.productRequests + (query ? "?" + query : ""));
    return unwrap(res, []);
}

/**
 * Renders a stock-request table. rows = ProductRequestResponse[].
 * primaryColumnFn(r) -> the leading identity column (distributor name, or
 * super stockist name, depending on which page is calling).
 * actions: {approveReject:bool, fulfill:bool, cancel:bool} — which action
 * buttons a PENDING/APPROVED row gets, gated by who's viewing.
 */
function renderProductRequestRows(tbodyId, rows, primaryColumnFn, actions){
    const tbody = document.getElementById(tbodyId);
    const colspan = primaryColumnFn ? 7 : 6;
    if (!rows.length){
        tbody.innerHTML = `<tr><td colspan="${colspan}" class="text-center text-muted py-4">No requests found.</td></tr>`;
        return;
    }
    tbody.innerHTML = rows.map(r => {
        let actionHtml = `<span class="text-muted small">—</span>`;
        if (r.status === "PENDING"){
            const btns = [];
            if (actions.approveReject){
                btns.push(`<button class="btn btn-sm btn-success me-1" data-req-approve="${r.id}"><i class="fa-solid fa-check"></i></button>`);
                btns.push(`<button class="btn btn-sm btn-outline-danger me-1" data-req-reject="${r.id}"><i class="fa-solid fa-xmark"></i></button>`);
            }
            if (actions.cancel){
                btns.push(`<button class="btn btn-sm btn-outline-secondary" data-req-cancel="${r.id}">Cancel</button>`);
            }
            if (btns.length) actionHtml = btns.join("");
        } else if (r.status === "APPROVED" && actions.fulfill){
            actionHtml = `<button class="btn btn-sm btn-primary" data-req-fulfill="${r.id}"><i class="fa-solid fa-truck me-1"></i>Fulfill / Dispatch</button>`;
        }
        return `<tr>
            ${primaryColumnFn ? `<td class="fw-600">${escapeHtml(primaryColumnFn(r))}</td>` : ""}
            <td>${escapeHtml(r.productName)}</td>
            <td>${r.requestedQuantity}</td>
            <td>${r.approvedQuantity ?? "—"}</td>
            <td>${stockReqStatusBadge(r.status)}</td>
            <td class="small text-muted">${escapeHtml(r.remarks || r.adminRemarks || "—")}</td>
            <td class="text-end">${actionHtml}</td>
        </tr>`;
    }).join("");

    tbody.querySelectorAll("[data-req-approve]").forEach(btn => btn.addEventListener("click", () => openRequestActionModal(Number(btn.dataset.reqApprove), "APPROVED")));
    tbody.querySelectorAll("[data-req-reject]").forEach(btn => btn.addEventListener("click", () => openRequestActionModal(Number(btn.dataset.reqReject), "REJECTED")));
    tbody.querySelectorAll("[data-req-fulfill]").forEach(btn => btn.addEventListener("click", () => openRequestActionModal(Number(btn.dataset.reqFulfill), "FULFILLED")));
    tbody.querySelectorAll("[data-req-cancel]").forEach(btn => btn.addEventListener("click", () => cancelStockRequest(Number(btn.dataset.reqCancel))));
}

function openRequestActionModal(id, targetStatus){
    const isApproveLike = targetStatus === "APPROVED" || targetStatus === "FULFILLED";
    const title = targetStatus === "REJECTED" ? "Reject Request" : (targetStatus === "FULFILLED" ? "Fulfill & Dispatch" : "Approve Request");
    const modalHtml = `
        <div class="modal fade" id="reqActionModal" tabindex="-1">
            <div class="modal-dialog"><div class="modal-content">
                <div class="modal-header"><h5 class="modal-title">${title}</h5><button class="btn-close" data-bs-dismiss="modal"></button></div>
                <div class="modal-body">
                    ${isApproveLike ? `<div class="mb-2"><label class="form-label">Approved Quantity (leave blank to use requested qty)</label><input type="number" class="form-control" id="reqActionQty" min="1"></div>` : ""}
                    <label class="form-label">Remarks</label>
                    <textarea class="form-control" id="reqActionRemarks" rows="2"></textarea>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button class="btn ${targetStatus === "REJECTED" ? "btn-danger" : "btn-success"}" id="confirmReqActionBtn">Confirm</button>
                </div>
            </div></div>
        </div>`;
    document.getElementById("dynamicModalHost")?.remove();
    const host = document.createElement("div");
    host.id = "dynamicModalHost";
    host.innerHTML = modalHtml;
    document.body.appendChild(host);
    const modalEl = document.getElementById("reqActionModal");
    const modal = new bootstrap.Modal(modalEl);
    document.getElementById("confirmReqActionBtn").addEventListener("click", async () => {
        const qtyVal = isApproveLike ? document.getElementById("reqActionQty").value : "";
        const remarks = document.getElementById("reqActionRemarks").value;
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.productRequests + "/" + id + "/action", { method:"PUT", body:{
                status: targetStatus,
                approvedQuantity: qtyVal ? Number(qtyVal) : null,
                adminRemarks: remarks || null,
            }});
            modal.hide();
            showToast("Updated", "Stock request updated.", "success");
            reloadCurrentSection();
        }catch(err){
            showApiError(err, "Action failed");
        }finally{
            hideSpinner();
        }
    });
    modalEl.addEventListener("hidden.bs.modal", () => host.remove());
    modal.show();
}

async function cancelStockRequest(id){
    showSpinner();
    try{
        await apiRequest(ENDPOINTS.productRequests + "/" + id + "/cancel", { method:"PUT" });
        showToast("Cancelled", "Stock request cancelled.", "success");
        reloadCurrentSection();
    }catch(err){
        showApiError(err, "Could not cancel");
    }finally{
        hideSpinner();
    }
}

function reloadCurrentSection(){
    const active = document.querySelector(".nav-link.active");
    const section = active ? active.dataset.section : null;
    if (section) window.__DMS_REFRESHERS__ && window.__DMS_REFRESHERS__[section] && window.__DMS_REFRESHERS__[section]();
}

let PRODUCT_OPTIONS_CACHE = null;
async function getProductOptions(){
    if (PRODUCT_OPTIONS_CACHE) return PRODUCT_OPTIONS_CACHE;
    const res = await apiRequest(ENDPOINTS.products);
    PRODUCT_OPTIONS_CACHE = unwrap(res, []);
    return PRODUCT_OPTIONS_CACHE;
}

async function openCreateStockRequestModal(){
    const products = await getProductOptions();
    const options = products.map(p => `<option value="${p.id}">${escapeHtml(p.productName)} (${escapeHtml(p.productCode||"")})</option>`).join("");
    const modalHtml = `
        <div class="modal fade" id="createReqModal" tabindex="-1">
            <div class="modal-dialog"><div class="modal-content">
                <div class="modal-header"><h5 class="modal-title">Request Stock</h5><button class="btn-close" data-bs-dismiss="modal"></button></div>
                <div class="modal-body">
                    <div class="mb-2"><label class="form-label">Product</label><select class="form-select" id="createReqProduct">${options}</select></div>
                    <div class="mb-2"><label class="form-label">Quantity</label><input type="number" class="form-control" id="createReqQty" min="1" value="1"></div>
                    <div class="mb-2"><label class="form-label">Remarks</label><textarea class="form-control" id="createReqRemarks" rows="2"></textarea></div>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button class="btn btn-gradient" id="confirmCreateReqBtn">Submit Request</button>
                </div>
            </div></div>
        </div>`;
    document.getElementById("dynamicModalHost")?.remove();
    const host = document.createElement("div");
    host.id = "dynamicModalHost";
    host.innerHTML = modalHtml;
    document.body.appendChild(host);
    const modalEl = document.getElementById("createReqModal");
    const modal = new bootstrap.Modal(modalEl);
    document.getElementById("confirmCreateReqBtn").addEventListener("click", async () => {
        const productId = document.getElementById("createReqProduct").value;
        const requestedQuantity = Number(document.getElementById("createReqQty").value);
        const remarks = document.getElementById("createReqRemarks").value;
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.productRequests, { method:"POST", body:{ productId: Number(productId), requestedQuantity, remarks } });
            modal.hide();
            showToast("Submitted", "Stock request submitted.", "success");
            reloadCurrentSection();
        }catch(err){
            showApiError(err, "Could not submit request");
        }finally{
            hideSpinner();
        }
    });
    modalEl.addEventListener("hidden.bs.modal", () => host.remove());
    modal.show();
}

document.getElementById("addSsRequestBtn")?.addEventListener("click", openCreateStockRequestModal);
document.getElementById("addDistRequestBtn")?.addEventListener("click", openCreateStockRequestModal);

/* ---- Super Stockist: Distributor Stock Requests (SS approves/rejects/fulfills) ---- */
async function loadAndRenderSsDistReq(){
    showSpinner();
    try{
        const status = document.getElementById("ssDistReqStatusFilter").value;
        const rows = await fetchProductRequests({ level: "DISTRIBUTOR_TO_SUPER_STOCKIST", status: status || undefined });
        renderProductRequestRows("ssDistReqTableBody", rows, r => r.distributorName, { approveReject:true, fulfill:true });
    }catch(err){
        showApiError(err, "Could not load requests");
    }finally{
        hideSpinner();
    }
}
document.getElementById("ssDistReqStatusFilter")?.addEventListener("change", loadAndRenderSsDistReq);

/* ---- Super Stockist: Admin Stock Requests (SS's own requests to Admin) ---- */
async function loadAndRenderSsAdminReq(){
    showSpinner();
    try{
        const rows = await fetchProductRequests({ level: "SUPER_STOCKIST_TO_COMPANY" });
        renderProductRequestRows("ssAdminReqTableBody", rows, null, { cancel:true });
    }catch(err){
        showApiError(err, "Could not load requests");
    }finally{
        hideSpinner();
    }
}

/* ---- Admin: Stock Requests from Super Stockists ---- */
async function loadAndRenderAdminReq(){
    showSpinner();
    try{
        const status = document.getElementById("adminReqStatusFilter").value;
        const rows = await fetchProductRequests({ level: "SUPER_STOCKIST_TO_COMPANY", status: status || undefined });
        renderProductRequestRows("adminReqTableBody", rows, r => r.superStockistName, { approveReject:true, fulfill:true });
    }catch(err){
        showApiError(err, "Could not load requests");
    }finally{
        hideSpinner();
    }
}
document.getElementById("adminReqStatusFilter")?.addEventListener("change", loadAndRenderAdminReq);

/* ---- Distributor: Stock Requests (their own, to their Super Stockist) ---- */
async function loadAndRenderDistReq(){
    showSpinner();
    try{
        const rows = await fetchProductRequests({});
        renderProductRequestRows("distReqTableBody", rows, null, { cancel:true });
    }catch(err){
        showApiError(err, "Could not load requests");
    }finally{
        hideSpinner();
    }
}

/* ==================== SUPER STOCKIST: WAREHOUSE STOCK ==================== */
async function loadAndRenderSsWarehouse(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.warehouse + "/me");
        const rows = unwrap(res, []);
        document.getElementById("ssWarehouseTableBody").innerHTML = rows.map(w => `
            <tr><td class="fw-600">${escapeHtml(w.productName)}</td><td>${escapeHtml(w.productCode||"—")}</td><td>${w.quantity}</td>
            <td>${w.mrp != null ? formatCurrency(w.mrp) : "—"}</td><td>${w.unitPrice != null ? formatCurrency(w.unitPrice) : "—"}</td></tr>
        `).join("") || `<tr><td colspan="5" class="text-center text-muted py-4">No stock on hand yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load warehouse stock");
    }finally{
        hideSpinner();
    }
}

/* ==================== DISTRIBUTOR: WAREHOUSE STOCK ==================== */
async function loadAndRenderDistWarehouse(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.warehouse + "/me");
        const rows = unwrap(res, []);
        document.getElementById("distWarehouseTableBody").innerHTML = rows.map(w => `
            <tr><td class="fw-600">${escapeHtml(w.productName)}</td><td>${escapeHtml(w.productCode||"—")}</td><td>${w.quantity}</td>
            <td>${w.mrp != null ? formatCurrency(w.mrp) : "—"}</td><td>${w.unitPrice != null ? formatCurrency(w.unitPrice) : "—"}</td></tr>
        `).join("") || `<tr><td colspan="5" class="text-center text-muted py-4">No stock on hand yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load warehouse stock");
    }finally{
        hideSpinner();
    }
}

/* ==================== PDF EXPORTS ====================
   Every export is scoped server-side to the caller's own visible rows
   (each service method delegates to its matching read method), so these
   buttons need no role gating of their own -- an admin, Super Stockist
   and Distributor each get their own view of the same endpoint.
   apiDownloadFile attaches the Bearer token, which a plain <a href> or
   window.open could not do. */
document.getElementById("paymentsPdfBtn")?.addEventListener("click", () =>
    apiDownloadFile(`${ENDPOINTS.payments}/export/pdf`, "payments.pdf"));

document.getElementById("salesReturnsPdfBtn")?.addEventListener("click", () =>
    apiDownloadFile(`${ENDPOINTS.salesReturns}/export/pdf`, "sales-returns.pdf"));

document.getElementById("distPurchaseReturnPdfBtn")?.addEventListener("click", () =>
    apiDownloadFile(`${ENDPOINTS.purchaseReturns}/export/pdf?scope=distributor`, "purchase-returns.pdf"));

document.getElementById("ssPurchaseReturnPdfBtn")?.addEventListener("click", () =>
    apiDownloadFile(`${ENDPOINTS.purchaseReturns}/export/pdf?scope=super-stockist`, "purchase-returns.pdf"));

document.getElementById("ssSalesReturnPdfBtn")?.addEventListener("click", () =>
    apiDownloadFile(`${ENDPOINTS.purchaseReturns}/export/pdf?scope=received`, "sales-returns-received.pdf"));

document.getElementById("adminReturnHistoryPdfBtn")?.addEventListener("click", () =>
    apiDownloadFile(`${ENDPOINTS.purchaseReturns}/export/pdf?scope=history`, "return-history.pdf"));

/* ==================== BILLS TO ME ====================
   GET /invoices is deliberately "invoices I raised" (creator-scoped), so
   a Super Stockist's own COMPANY_TO_SUPER_STOCKIST bill and a
   Distributor's SUPER_STOCKIST_TO_DISTRIBUTOR bill do NOT appear there --
   those are purchases, not sales. Without this page they'd be invisible
   and unpayable, even though PaymentService explicitly allows paying
   them. Admin has no bills: Company is the top of the chain. */
async function loadAndRenderBilledToMe(){
    const tbody = document.getElementById("billedToMeBody");
    tbody.innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4">Loading...</td></tr>`;
    try{
        const rows = unwrap(await apiRequest(ENDPOINTS.invoices + "/billed-to-me"), []);
        STATE.billedToMe = rows;
        tbody.innerHTML = rows.map(i => `<tr>
            <td class="fw-600">${escapeHtml(i.invoiceNumber || "\u2014")}</td>
            <td>${formatDate(i.invoiceDate)}</td>
            <td>${escapeHtml(i.superStockistName || "Company (Admin)")}</td>
            <td>${formatCurrency(i.totalAmount)}</td>
            <td>${formatCurrency(i.paidAmount)}</td>
            <td>${formatCurrency(i.balanceAmount)}</td>
            <td>${statusBadge(normalizeStatusLabel(i.paymentStatus))}</td>
            <td class="text-end">${Number(i.balanceAmount) > 0
                ? `<button class="action-btn edit" data-pay-billed="${i.id}" title="Record Payment"><i class="fa-solid fa-money-bill-wave"></i></button>`
                : ""}</td>
        </tr>`).join("") || `<tr><td colspan="8" class="text-center text-muted py-4">No bills raised against you.</td></tr>`;

        tbody.querySelectorAll("[data-pay-billed]").forEach(btn => {
            btn.addEventListener("click", () => {
                const invoice = STATE.billedToMe.find(i => i.id == btn.dataset.payBilled);
                if (invoice) openRecordPaymentModal(invoice);
            });
        });
    }catch(err){
        showApiError(err, "Could not load your bills");
        tbody.innerHTML = `<tr><td colspan="8" class="text-center text-danger py-4">Could not load your bills.</td></tr>`;
    }
}

/* ==================== MRP-WISE STOCK ====================
   Backed by GET /products/mrp-wise-stock, which scopes itself to the
   caller: an admin sees company root stock, a Super Stockist or
   Distributor sees only their own warehouse. Grouped by MRP price point.

   NOTE: this function is referenced by the section router
   ("mrp-stock": loadAndRenderMrpStock). If it goes missing, script.js
   throws "loadAndRenderMrpStock is not defined" while BUILDING the
   refreshers object -- which happens during login -- so the whole app
   fails to start, not just this one page. */
async function loadAndRenderMrpStock(){
    const container = document.getElementById("mrpStockGroups");
    if (!container) return;
    container.innerHTML = `<p class="text-center text-muted py-4">Loading...</p>`;
    try{
        const res = await apiRequest(ENDPOINTS.products + "/mrp-wise-stock");
        const data = unwrap(res, { groups: [] });
        const groups = (data && data.groups) || [];
        if (!groups.length){
            container.innerHTML = `<p class="text-center text-muted py-4">No stock on hand yet.</p>`;
            return;
        }
        container.innerHTML = groups.map(g => `
            <div class="glass-card table-card mb-3">
                <div class="d-flex justify-content-between align-items-center px-3 pt-3 flex-wrap gap-2">
                    <h6 class="fw-600 mb-0">MRP ${formatCurrency(g.mrp)}</h6>
                    <span class="badge bg-primary-subtle text-primary">Total: ${g.totalQuantity ?? 0}</span>
                </div>
                <div class="table-responsive mt-2">
                    <table class="table brisk-table mb-0">
                        <thead><tr><th>Product</th><th>Code</th><th class="text-end">Quantity</th></tr></thead>
                        <tbody>${(g.products || []).map(pr => `
                            <tr>
                                <td class="fw-600">${escapeHtml(pr.productName || "—")}</td>
                                <td>${escapeHtml(pr.productCode || "—")}</td>
                                <td class="text-end">${pr.quantity ?? 0}</td>
                            </tr>`).join("")}</tbody>
                    </table>
                </div>
            </div>
        `).join("");
    }catch(err){
        showApiError(err, "Could not load MRP-wise stock");
        container.innerHTML = `<p class="text-center text-danger py-4">Could not load MRP-wise stock.</p>`;
    }
}

/* ==================== RETURN MANAGEMENT ====================
   One PurchaseReturn row on the backend is BOTH a purchase return (the
   party sending goods back up) and a sales return received (the party
   receiving them) -- see ReturnLevel.java. So the Super Stockist's
   "Sales Return" page and their distributors' "Purchase Return" pages
   read the exact same records from opposite directions; nothing is
   duplicated, and stock can never be double-counted.

   Shop -> Distributor is the one exception: a Shop isn't a stock-holding
   party and has no login, so that leg stays on the existing SalesReturn
   API (which also settles the shop's invoice balance). */

// Which tier the logged-in user is returning TO, used by the shared modal.
let currentPurchaseReturnMode = null; // "distributor" | "superStockist"

async function loadAndRenderDistSalesReturn(){
    const tbody = document.getElementById("distSalesReturnBody");
    tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;
    try{
        const res = await apiRequest(ENDPOINTS.salesReturns);
        const rows = unwrap(res, []);
        STATE.salesReturns = rows;
        tbody.innerHTML = rows.map(r => `<tr>
            <td>${formatDate(r.returnDate)}</td>
            <td>${escapeHtml(r.invoiceNumber || "—")}</td>
            <td>${escapeHtml(r.shopName || "—")}</td>
            <td class="fw-600">${escapeHtml(r.productName || "—")}</td>
            <td>${r.quantity}</td>
            <td>${formatCurrency(r.returnAmount)}</td>
            <td class="cell-clamp" title="${escapeHtml(r.reason || "")}">${escapeHtml(r.reason || "—")}</td>
        </tr>`).join("") || `<tr><td colspan="7" class="text-center text-muted py-4">No sales returns recorded yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load sales returns");
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Could not load sales returns.</td></tr>`;
    }
}

// `deletable` is false for the Super Stockist's "Sales Return (Received)"
// view: those rows belong to the distributor who filed them, and the
// backend refuses a delete from the receiving side anyway (see
// assertCanDelete) -- so we don't show a button that can only ever 403.
// Approving is what actually moves stock; rejecting moves nothing.
async function decideReturn(id, approve, reload){
    let reason = null;
    if (approve){
        if (!window.confirm("Approve this return? Stock will be transferred immediately.")) return;
    }else{
        reason = window.prompt("Why is this return being rejected? The submitter will see this.");
        if (reason === null) return;                 // cancelled
        if (!reason.trim()){
            showToast("Reason required", "Give a reason so the submitter knows why.", "error");
            return;
        }
    }
    showSpinner();
    try{
        await apiRequest(`${ENDPOINTS.purchaseReturns}/${id}/${approve ? "approve" : "reject"}`,
            { method: "POST", body: approve ? {} : { reason: reason.trim() } });
        showToast(approve ? "Approved" : "Rejected",
            approve ? "Stock transferred and ledgers updated." : "Return rejected — no stock moved.", "success");
        if (typeof reload === "function") await reload();
        refreshDashboard();
    }catch(err){
        showApiError(err, approve ? "Could not approve return" : "Could not reject return");
    }finally{
        hideSpinner();
    }
}

// Stock only moves on APPROVED, so status is the most important column on
// every return table -- never render a return row without it.
function returnStatusBadge(status){
    const s = (status || "PENDING").toUpperCase();
    if (s === "APPROVED") return `<span class="badge bg-success-subtle text-success">Approved</span>`;
    if (s === "REJECTED") return `<span class="badge bg-danger-subtle text-danger">Rejected</span>`;
    return `<span class="badge bg-warning-subtle text-warning">Pending</span>`;
}

function renderPurchaseReturnRows(tbodyId, rows, partyLabel, partyFn, colspan, deletable = false, reload = null, approvable = false){
    const tbody = document.getElementById(tbodyId);
    tbody.innerHTML = rows.map(r => {
        const pending = (r.status || "PENDING").toUpperCase() === "PENDING";
        const note = r.rejectionReason ? ` <span class="text-danger small">(${escapeHtml(r.rejectionReason)})</span>` : "";
        // Approve/reject and delete share one trailing actions cell so the
        // column count stays stable whichever flags a caller passes.
        let actionCell = "";
        if (approvable || deletable){
            const bits = [];
            if (approvable && pending){
                bits.push(`<button class="action-btn approve" data-approve-return="${r.id}" title="Approve"><i class="fa-solid fa-check"></i></button>`);
                bits.push(`<button class="action-btn delete" data-reject-return="${r.id}" title="Reject"><i class="fa-solid fa-xmark"></i></button>`);
            }
            if (deletable){
                bits.push(`<button class="action-btn delete" data-del-return="${r.id}" title="Delete & reverse stock"><i class="fa-solid fa-trash"></i></button>`);
            }
            actionCell = `<td class="text-end text-nowrap">${bits.join(" ") || `<span class="text-muted small">${escapeHtml(r.approvedBy || "—")}</span>`}</td>`;
        }
        return `<tr>
        <td class="fw-600">${escapeHtml(r.returnNumber || "—")}</td>
        <td>${formatDate(r.returnDate)}</td>
        <td>${escapeHtml(partyFn(r) || "—")}</td>
        <td>${escapeHtml(r.productName || "—")}</td>
        <td class="text-end">${r.quantity}</td>
        <td class="text-end">${formatCurrency(r.returnAmount)}</td>
        <td>${returnStatusBadge(r.status)}${note}</td>
        <td class="cell-clamp" title="${escapeHtml(r.reason || "")}">${escapeHtml(r.reason || "—")}</td>
        ${actionCell}
    </tr>`;
    }).join("") || `<tr><td colspan="${colspan}" class="text-center text-muted py-4">No returns recorded yet.</td></tr>`;

    if (approvable){
        tbody.querySelectorAll("[data-approve-return]").forEach(btn => {
            btn.addEventListener("click", () => decideReturn(Number(btn.dataset.approveReturn), true, reload));
        });
        tbody.querySelectorAll("[data-reject-return]").forEach(btn => {
            btn.addEventListener("click", () => decideReturn(Number(btn.dataset.rejectReturn), false, reload));
        });
    }

    if (deletable){
        tbody.querySelectorAll("[data-del-return]").forEach(btn => {
            btn.addEventListener("click", () => deletePurchaseReturn(Number(btn.dataset.delReturn), reload));
        });
    }
}

function deletePurchaseReturn(id, reload){
    showConfirm("Delete Return",
        "This deletes the return and moves the stock back to where it was. Continue?",
        async () => {
            showSpinner();
            try{
                await apiRequest(ENDPOINTS.purchaseReturns + "/" + id, { method: "DELETE" });
                showToast("Deleted", "Return reversed and stock restored.", "success");
                if (typeof reload === "function") await reload();
                refreshDashboard();
            }catch(err){
                showApiError(err, "Could not delete return");
            }finally{
                hideSpinner();
            }
        });
}

async function loadAndRenderDistPurchaseReturn(){
    const tbody = document.getElementById("distPurchaseReturnBody");
    tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;
    try{
        const res = await apiRequest(ENDPOINTS.purchaseReturns + "/distributor/me");
        renderPurchaseReturnRows("distPurchaseReturnBody", unwrap(res, []), "To", r => r.toParty, 9, true, loadAndRenderDistPurchaseReturn);
    }catch(err){
        showApiError(err, "Could not load purchase returns");
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Could not load purchase returns.</td></tr>`;
    }
}

async function loadAndRenderSsSalesReturn(){
    const tbody = document.getElementById("ssSalesReturnBody");
    tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;
    try{
        const res = await apiRequest(ENDPOINTS.purchaseReturns + "/super-stockist/me/received");
        renderPurchaseReturnRows("ssSalesReturnBody", unwrap(res, []), "From", r => r.fromParty, 9, false, loadAndRenderSsSalesReturn, true);
    }catch(err){
        showApiError(err, "Could not load sales returns");
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Could not load sales returns.</td></tr>`;
    }
}

async function loadAndRenderSsPurchaseReturn(){
    const tbody = document.getElementById("ssPurchaseReturnBody");
    tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;
    try{
        const res = await apiRequest(ENDPOINTS.purchaseReturns + "/super-stockist/me");
        renderPurchaseReturnRows("ssPurchaseReturnBody", unwrap(res, []), "To", r => r.toParty, 9, true, loadAndRenderSsPurchaseReturn);
    }catch(err){
        showApiError(err, "Could not load purchase returns");
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Could not load purchase returns.</td></tr>`;
    }
}

async function loadAndRenderAdminReturnHistory(inbound = false){
    const tbody = document.getElementById("adminReturnHistoryBody");
    tbody.innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4">Loading...</td></tr>`;
    try{
        const url = ENDPOINTS.purchaseReturns + (inbound ? "/company/received" : "/history");
        const rows = unwrap(await apiRequest(url), []);
        tbody.innerHTML = rows.map(r => `<tr>
            <td class="fw-600">${escapeHtml(r.returnNumber || "—")}</td>
            <td>${formatDate(r.returnDate)}</td>
            <td>${escapeHtml(r.fromParty || "—")}</td>
            <td>${escapeHtml(r.toParty || "—")}</td>
            <td>${escapeHtml(r.productName || "—")}</td>
            <td>${r.quantity}</td>
            <td>${formatCurrency(r.returnAmount)}</td>
            <td>${escapeHtml(r.createdBy || "—")}</td>
        </tr>`).join("") || `<tr><td colspan="8" class="text-center text-muted py-4">No returns recorded yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load return history");
        tbody.innerHTML = `<tr><td colspan="8" class="text-center text-danger py-4">Could not load return history.</td></tr>`;
    }
}

document.getElementById("retHistAllBtn")?.addEventListener("click", (e) => {
    document.getElementById("retHistInboundBtn").classList.remove("active");
    e.currentTarget.classList.add("active");
    loadAndRenderAdminReturnHistory(false);
});
document.getElementById("retHistInboundBtn")?.addEventListener("click", (e) => {
    document.getElementById("retHistAllBtn").classList.remove("active");
    e.currentTarget.classList.add("active");
    loadAndRenderAdminReturnHistory(true);
});

/* ---------- Shared "New Purchase Return" modal ---------- */

// Products are sourced from the caller's OWN warehouse (/warehouse/me),
// not the full catalog, so you can only ever pick something you actually
// hold -- and every figure shown (code, MRP, your rate, on-hand qty) comes
// from that same backend row rather than being typed or hardcoded. The
// backend re-validates all of it (see PurchaseReturnService).
let prStockRows = [];

async function openPurchaseReturnModal(mode){
    currentPurchaseReturnMode = mode;
    document.getElementById("prReturnToHint").textContent = mode === "distributor"
        ? "These goods will be sent back to your Super Stockist and removed from your stock."
        : "These goods will be sent back to the Company and removed from your stock.";
    // A Super Stockist's own purchase rate is the SS Price, not DP Price.
    const priceLabel = mode === "distributor" ? "DP Price" : "SS Price";
    document.getElementById("prUnitPriceLabel").textContent = priceLabel;
    document.getElementById("prTotalFormula").textContent = `Quantity × ${priceLabel}, applied automatically from your product pricing.`;

    ["prQuantity"].forEach(id => document.getElementById(id).value = 1);
    ["prReasonNote","prCode","prMrp","prUnitPriceView","prAvailable","prTotal"].forEach(id => document.getElementById(id).value = "");
    document.getElementById("prReasonCode").value = "";
    document.getElementById("prReasonNoteWrap").classList.add("d-none");
    prClearErrors();

    const select = document.getElementById("prProduct");
    select.innerHTML = `<option value="">Loading…</option>`;
    new bootstrap.Modal(document.getElementById("purchaseReturnModal")).show();

    try{
        prStockRows = unwrap(await apiRequest(ENDPOINTS.warehouse + "/me"), [])
            .filter(w => (w.quantity ?? 0) > 0);
        select.innerHTML = `<option value="">Select a product…</option>` + prStockRows.map(w =>
            `<option value="${w.productId}">${escapeHtml(w.productName)} — ${w.quantity} on hand</option>`
        ).join("");
        if (!prStockRows.length){
            select.innerHTML = `<option value="">No stock on hand to return</option>`;
        }
    }catch(err){
        select.innerHTML = `<option value="">Could not load your stock</option>`;
        showApiError(err, "Could not load your stock");
    }
}

function prSelectedRow(){
    const id = document.getElementById("prProduct").value;
    return id ? prStockRows.find(w => String(w.productId) === String(id)) : null;
}

function prClearErrors(){
    ["prProductError","prQuantityError","prReasonError"].forEach(id => {
        const el = document.getElementById(id); if (el) el.textContent = "";
    });
}

// Fills the read-only facts from the chosen stock row and seeds unit price
// with the owner's own rate. Runs on product change only -- it must not
// clobber a unit price the user has deliberately overridden.
function prApplyProduct(){
    const row = prSelectedRow();
    const set = (id, v) => document.getElementById(id).value = v;
    if (!row){
        ["prCode","prMrp","prUnitPriceView","prAvailable"].forEach(id => set(id, ""));
        prRecalcTotal();
        return;
    }
    set("prCode", row.productCode || "—");
    set("prMrp", row.mrp != null ? formatCurrency(row.mrp) : "—");
    set("prUnitPriceView", row.unitPrice != null ? formatCurrency(row.unitPrice) : "—");
    set("prAvailable", row.quantity);
    document.getElementById("prQuantity").max = row.quantity;
    prRecalcTotal();
}

// Total = Quantity x the ROLE price for the selected product:
//   Distributor login -> DP Price,  Super Stockist login -> SS Price.
// The rate comes from the backend stock row (warehouse.unitPrice), which
// already resolves any per-party custom rate, so this display always
// matches what PurchaseReturnService will actually credit. There is no
// user-entered unit price to diverge from.
function prRecalcTotal(){
    const row = prSelectedRow();
    const qty = Number(document.getElementById("prQuantity").value);
    const price = row && row.unitPrice != null ? Number(row.unitPrice) : NaN;
    const valid = Number.isFinite(qty) && Number.isFinite(price) && qty > 0 && price >= 0;
    document.getElementById("prTotal").value = valid ? formatCurrency(qty * price) : "—";
}

document.getElementById("prProduct")?.addEventListener("change", prApplyProduct);
document.getElementById("prQuantity")?.addEventListener("input", prRecalcTotal);

document.getElementById("prReasonCode")?.addEventListener("change", (e) => {
    const isOthers = e.target.value === "OTHERS";
    document.getElementById("prReasonNoteWrap").classList.toggle("d-none", !isOthers);
    // Switching away from Others discards the custom text so a stale note
    // can't be submitted against a structured reason.
    if (!isOthers) document.getElementById("prReasonNote").value = "";
});

document.getElementById("distPurchaseReturnAddBtn")?.addEventListener("click", () => openPurchaseReturnModal("distributor"));
document.getElementById("ssPurchaseReturnAddBtn")?.addEventListener("click", () => openPurchaseReturnModal("superStockist"));

// The distributor's Sales Return page reuses the existing shop-return
// modal, since that leg is still handled by the SalesReturn API.
document.getElementById("distSalesReturnAddBtn")?.addEventListener("click", () => {
    document.getElementById("addSalesReturnBtn")?.click();
});

document.getElementById("prSubmitBtn")?.addEventListener("click", async () => {
    prClearErrors();
    const btn = document.getElementById("prSubmitBtn");
    const row = prSelectedRow();
    const quantity = Number(document.getElementById("prQuantity").value);
    const reasonCode = document.getElementById("prReasonCode").value;
    const reasonNote = document.getElementById("prReasonNote").value.trim();

    let bad = false;
    if (!row){ document.getElementById("prProductError").textContent = "Choose a product to return."; bad = true; }
    if (!Number.isFinite(quantity) || quantity < 1){
        document.getElementById("prQuantityError").textContent = "Quantity must be at least 1."; bad = true;
    }else if (row && quantity > row.quantity){
        document.getElementById("prQuantityError").textContent = `You only have ${row.quantity} on hand.`; bad = true;
    }
    if (!reasonCode){ document.getElementById("prReasonError").textContent = "Select a reason."; bad = true; }
    if (reasonCode === "OTHERS" && !reasonNote){
        document.getElementById("prReasonError").textContent = "Describe the reason for 'Others'."; bad = true;
    }
    if (row && row.unitPrice == null){
        document.getElementById("prProductError").textContent = "No rate is set for this product at your level — ask an admin to set it before returning it.";
        bad = true;
    }
    if (bad) return;

    const payload = {
        productId: Number(row.productId),
        quantity,
        reasonCode,
        reasonNote: reasonNote || null,
    };
    const url = ENDPOINTS.purchaseReturns + (currentPurchaseReturnMode === "distributor" ? "/distributor" : "/super-stockist");

    // Disabled for the whole round-trip so a double-click can't file the
    // same return twice (each submit moves real stock).
    btn.disabled = true;
    showSpinner();
    try{
        await apiRequest(url, { method: "POST", body: payload });
        showToast("Return recorded", "Purchase return saved and stock updated.", "success");
        bootstrap.Modal.getInstance(document.getElementById("purchaseReturnModal"))?.hide();
        if (currentPurchaseReturnMode === "distributor") await loadAndRenderDistPurchaseReturn();
        else await loadAndRenderSsPurchaseReturn();
        refreshDashboard();
    }catch(err){
        showApiError(err, "Could not save purchase return");
    }finally{
        btn.disabled = false;
        hideSpinner();
    }
});

/* ==================== ADMIN: WAREHOUSE STOCK (COMPANY-WIDE) ==================== */
async function loadAndRenderAdminWarehouse(){
    showSpinner();
    try{
        // Bug fix: this page's own heading says "stock across every Super
        // Stockist and Distributor", but it was calling /warehouse/company
        // — which returns the COMPANY's own root stock (Product.stockQuantity),
        // not any SS/Distributor's Warehouse rows. Every row therefore had
        // ownerType "COMPANY" (never "SUPER_STOCKIST"), so the table always
        // rendered the "Dist" badge, and distributorName/superStockistName
        // were never set for these rows, so the name always fell back to
        // "—". That's what made a distributor showing 999 here look like
        // they had stock when invoicing then failed with "available 0" —
        // the 999 was the Company's central stock, not that distributor's
        // own warehouse. /warehouse/me is what actually returns every real
        // Warehouse row (every SS's and every distributor's own on-hand
        // stock) for an admin caller, correctly labeled.
        const res = await apiRequest(ENDPOINTS.warehouse + "/me");
        const rows = unwrap(res, []);
        document.getElementById("adminWarehouseTableBody").innerHTML = rows.map(w => {
            const owner = w.ownerType === "SUPER_STOCKIST"
                ? `<span class="badge bg-primary-subtle text-primary">SS</span> ${escapeHtml(w.superStockistName || "—")}`
                : `<span class="badge bg-success-subtle text-success">Dist</span> ${escapeHtml(w.distributorName || "—")}`;
            return `<tr><td>${owner}</td><td class="fw-600">${escapeHtml(w.productName)}</td><td>${escapeHtml(w.productCode||"—")}</td><td>${w.quantity}</td></tr>`;
        }).join("") || `<tr><td colspan="4" class="text-center text-muted py-4">No warehouse stock recorded yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load warehouse stock");
    }finally{
        hideSpinner();
    }
}

/* ==================== SUPER STOCKIST: STOCK TRANSFERS / DISPATCH HISTORY ==================== */
async function loadAndRenderSsTransfers(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.stockTransfers + "/me/incoming");
        const rows = unwrap(res, []);
        document.getElementById("ssTransfersTableBody").innerHTML = rows.map(t => `
            <tr><td class="fw-600">${escapeHtml(t.productName)}</td><td>${t.quantity}</td><td>${formatDate(t.transferDate)}</td><td>${escapeHtml(t.transferredBy||"—")}</td><td><span class="badge bg-success">${escapeHtml(t.status||"")}</span></td><td class="small text-muted">${escapeHtml(t.remarks||"—")}</td></tr>
        `).join("") || `<tr><td colspan="6" class="text-center text-muted py-4">No transfers received yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load transfers");
    }finally{
        hideSpinner();
    }
}

async function loadAndRenderSsDispatch(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.stockTransfers + "/me/dispatch-history");
        const rows = unwrap(res, []);
        document.getElementById("ssDispatchTableBody").innerHTML = rows.map(t => `
            <tr><td class="fw-600">${escapeHtml(t.toDistributorName||"—")}</td><td>${escapeHtml(t.productName)}</td><td>${t.quantity}</td><td>${formatDate(t.transferDate)}</td><td>${escapeHtml(t.transferredBy||"—")}</td><td class="small text-muted">${escapeHtml(t.remarks||"—")}</td></tr>
        `).join("") || `<tr><td colspan="6" class="text-center text-muted py-4">No dispatches yet.</td></tr>`;
    }catch(err){
        showApiError(err, "Could not load dispatch history");
    }finally{
        hideSpinner();
    }
}


/* ---------------- Notification deep-linking ----------------
   The backend already stamps every notification with a notificationType
   and a referenceId (see AuthService.register / PurchaseReturnService),
   but the frontend was rendering them as dead text -- so a new
   registration told you it existed and then made you go find it.
   This maps the type onto the section that actually handles it, so one
   click lands on the right screen with the row highlighted. */
function notificationTarget(n){
    const type = String(n.notificationType || "").toUpperCase();
    const isAdmin = isAdminRoleName((getCurrentUser() || {}).role);

    if (type === "DISTRIBUTOR" || type === "SUPER_STOCKIST"){
        // A pending registration is actioned on the Approvals screen; once
        // approved the same person lives in the Distributors / Super
        // Stockists master list, so send admins to Approvals first.
        if (isAdmin) return { section: "approvals", label: "registration approval", focusId: n.referenceId };
        return null;
    }
    if (type === "RETURN"){
        if (isAdmin) return { section: "admin-return-history", label: "return history", focusId: n.referenceId };
        return { section: "ss-sales-return", label: "sales return", focusId: n.referenceId };
    }
    if (type === "STOCK_REQUEST" || type === "PRODUCT_REQUEST"){
        return { section: isAdmin ? "admin-stock-requests" : "ss-distributor-requests", label: "stock request", focusId: n.referenceId };
    }
    if (type === "INVOICE") return { section: "invoices", label: "invoice", focusId: n.referenceId };
    if (type === "PAYMENT") return { section: "payments", label: "payment", focusId: n.referenceId };
    return null;
}

function wireNotificationClicks(listEl){
    listEl.querySelectorAll("[data-notif-section]").forEach(el => {
        el.addEventListener("click", async () => {
            const section = el.dataset.notifSection;
            const focusId = el.dataset.notifFocus;
            const notifId = el.dataset.notifId;
            // Mark read first so the badge count is right when we land.
            if (notifId){
                try{ await apiRequest(ENDPOINTS.notifications + "/" + notifId + "/read", { method: "PATCH" }); }catch(e){ /* non-fatal */ }
            }
            showSection(section);
            if (focusId) highlightRow(focusId);
        });
    });
}

/* Flash the row the notification pointed at. The section's own loader is
   async, so poll briefly for the row instead of assuming it has rendered. */
function highlightRow(id, attempt = 0){
    const row = document.querySelector(`[data-row-id="${id}"], [data-approve="${id}"], [data-notif-row="${id}"]`);
    if (!row){
        if (attempt < 12) setTimeout(() => highlightRow(id, attempt + 1), 150);
        return;
    }
    const tr = row.closest("tr") || row;
    tr.classList.add("row-flash");
    tr.scrollIntoView({ behavior: "smooth", block: "center" });
    setTimeout(() => tr.classList.remove("row-flash"), 2600);
}

/* ==================== NOTIFICATIONS (Super Stockist / Distributor) ==================== */
async function loadAndRenderNotifications(scope){
    const listId = scope === "ss" ? "ssNotificationsList" : "distNotificationsList";
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.notifications);
        const items = unwrap(res, []);
        const listEl = document.getElementById(listId);
        listEl.innerHTML = items.map(n => {
            const target = notificationTarget(n);
            return `
            <div class="mini-list-item notif-item ${n.isRead ? "" : "fw-600"} ${target ? "notif-clickable" : ""}"
                 ${target ? `data-notif-id="${n.id}" data-notif-section="${target.section}" ${target.focusId ? `data-notif-focus="${target.focusId}"` : ""}` : ""}
                 style="padding:10px 0;border-bottom:1px solid var(--border-soft, #eee)">
                <div class="d-flex justify-content-between align-items-start gap-2">
                    <span>${escapeHtml(n.title)}</span>
                    <span class="text-muted small text-nowrap">${formatDate(n.createdAt)}</span>
                </div>
                <div class="text-muted small">${escapeHtml(n.message)}</div>
                ${target ? `<div class="small mt-1" style="color:var(--accent-dark, #b1566d)">Open ${escapeHtml(target.label)} <i class="fa-solid fa-arrow-right ms-1"></i></div>` : ""}
            </div>`;
        }).join("") || `<p class="text-muted text-center py-4">No notifications yet.</p>`;

        wireNotificationClicks(listEl);
    }catch(err){
        showApiError(err, "Could not load notifications");
    }finally{
        hideSpinner();
    }
}

/* ---------------- Topbar notification bell ----------------
   The bell button/dropdown is visible on every page for every role, so it
   is populated independently of loadAndRenderNotifications("ss"/"dist")
   above (which only render the two dedicated, role-exclusive full-page
   Notifications sections). Same two backend endpoints, same response
   field names (title/message/createdAt/isRead) as that working code. */
async function loadAndRenderNotificationBell(){
    const dot = document.getElementById("notifDot");
    const badge = document.getElementById("bellNotifBadge");
    try{
        const countRes = await apiRequest(ENDPOINTS.notificationsUnreadCount);
        const raw = unwrap(countRes, 0);
        const count = (raw && typeof raw === "object") ? Number(raw.count ?? raw.unreadCount ?? 0) : Number(raw) || 0;
        if (dot){
            dot.textContent = String(count);
            dot.classList.toggle("d-none", count === 0);
        }
        if (badge) badge.textContent = `${count} New`;
    }catch(err){
        // Non-fatal — leave the bell as-is if the count call fails.
    }

    const listEl = document.getElementById("bellNotifList");
    if (!listEl) return;
    try{
        const res = await apiRequest(ENDPOINTS.notifications);
        const items = unwrap(res, []).slice(0, 5);
        listEl.innerHTML = items.map(n => `
            <div class="notif-item ${n.isRead ? "" : "fw-600"}">
                <i class="fa-solid fa-bell"></i>
                <div><b>${escapeHtml(n.title)}</b><p>${escapeHtml(n.message)}</p></div>
            </div>`).join("") || `<p class="text-muted text-center py-3 mb-0">No notifications yet.</p>`;
    }catch(err){
        listEl.innerHTML = `<p class="text-muted text-center py-3 mb-0">Could not load notifications.</p>`;
    }

    const link = document.getElementById("bellViewAllLink");
    if (link){
        const role = normalizeRole((getCurrentUser() || {}).role);
        const target = role === "SUPER_STOCKIST" ? "ss-notifications" : (role === "DISTRIBUTOR" ? "dist-notifications" : null);
        link.classList.toggle("d-none", !target);
        if (target) link.onclick = (e) => { e.preventDefault(); showSection(target); };
    }
}
document.getElementById("bellBtn")?.addEventListener("click", () => loadAndRenderNotificationBell());

document.getElementById("ssMarkAllReadBtn")?.addEventListener("click", async () => {
    try{ await apiRequest(ENDPOINTS.notifications + "/read-all", { method:"PATCH" }); loadAndRenderNotifications("ss"); }catch(err){ showApiError(err, "Failed"); }
});
document.getElementById("distMarkAllReadBtn")?.addEventListener("click", async () => {
    try{ await apiRequest(ENDPOINTS.notifications + "/read-all", { method:"PATCH" }); loadAndRenderNotifications("dist"); }catch(err){ showApiError(err, "Failed"); }
});

/* ==================== ADMIN: DISTRIBUTOR ASSIGNMENTS ==================== */
async function loadAndRenderAssignments(){
    showSpinner();
    try{
        const status = document.getElementById("assignmentsStatusFilter").value;
        const res = await apiRequest(ENDPOINTS.distributorAssignments + (status ? "?status=" + status : ""));
        const rows = unwrap(res, []);
        document.getElementById("assignmentsTableBody").innerHTML = rows.map(a => {
            const distResponse = a.status === "MODIFIED" ? `Proposed ${a.modifiedQuantity}` : (a.status === "PENDING" ? "Awaiting response" : a.status);
            const badgeClass = { PENDING:"bg-warning text-dark", APPROVED:"bg-success", MODIFIED:"bg-info text-dark", REJECTED:"bg-danger" }[a.status] || "bg-secondary";
            return `<tr>
                <td class="fw-600">${escapeHtml(a.distributorName)}</td>
                <td>${escapeHtml(a.shopName)}</td>
                <td>${escapeHtml(a.productName)}</td>
                <td>${a.quantity}</td>
                <td>${escapeHtml(distResponse)}</td>
                <td><span class="badge ${badgeClass}">${escapeHtml(a.status)}</span></td>
                <td class="small text-muted">${escapeHtml(a.distributorRemarks || a.adminRemarks || "—")}</td>
                <td class="text-end">${a.status === "PENDING" ? `<button class="btn btn-sm btn-outline-danger" data-del-assignment="${a.id}"><i class="fa-solid fa-trash"></i></button>` : `<span class="text-muted small">—</span>`}</td>
            </tr>`;
        }).join("") || `<tr><td colspan="8" class="text-center text-muted py-4">No assignments yet.</td></tr>`;

        document.getElementById("assignmentsTableBody").querySelectorAll("[data-del-assignment]").forEach(btn => {
            btn.addEventListener("click", async () => {
                showSpinner();
                try{
                    await apiRequest(ENDPOINTS.distributorAssignments + "/" + btn.dataset.delAssignment, { method:"DELETE" });
                    showToast("Deleted", "Assignment removed.", "success");
                    loadAndRenderAssignments();
                }catch(err){ showApiError(err, "Could not delete"); }
                finally{ hideSpinner(); }
            });
        });
    }catch(err){
        showApiError(err, "Could not load assignments");
    }finally{
        hideSpinner();
    }
}
document.getElementById("assignmentsStatusFilter")?.addEventListener("change", loadAndRenderAssignments);

document.getElementById("addAssignmentBtn")?.addEventListener("click", async () => {
    if (!STATE.distributors.length) await loadAndRenderCrud("distributors");
    const products = await getProductOptions();
    const distOptions = STATE.distributors.map(d => `<option value="${d.id}">${escapeHtml(d.distributorName)}</option>`).join("");
    const productOptions = products.map(p => `<option value="${p.id}">${escapeHtml(p.productName)}</option>`).join("");

    const modalHtml = `
        <div class="modal fade" id="createAssignmentModal" tabindex="-1">
            <div class="modal-dialog"><div class="modal-content">
                <div class="modal-header"><h5 class="modal-title">New Distributor Assignment</h5><button class="btn-close" data-bs-dismiss="modal"></button></div>
                <div class="modal-body">
                    <div class="mb-2"><label class="form-label">Distributor</label><select class="form-select" id="assignDistributorSelect">${distOptions}</select></div>
                    <div class="mb-2"><label class="form-label">Shop</label><select class="form-select" id="assignShopSelect"><option value="">Loading shops...</option></select></div>
                    <div class="mb-2"><label class="form-label">Product</label><select class="form-select" id="assignProductSelect">${productOptions}</select></div>
                    <div class="mb-2"><label class="form-label">Quantity</label><input type="number" class="form-control" id="assignQty" min="1" value="1"></div>
                    <div class="mb-2"><label class="form-label">Remarks</label><textarea class="form-control" id="assignRemarks" rows="2"></textarea></div>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button class="btn btn-gradient" id="confirmCreateAssignmentBtn">Create</button>
                </div>
            </div></div>
        </div>`;
    document.getElementById("dynamicModalHost")?.remove();
    const host = document.createElement("div");
    host.id = "dynamicModalHost";
    host.innerHTML = modalHtml;
    document.body.appendChild(host);
    const modalEl = document.getElementById("createAssignmentModal");
    const modal = new bootstrap.Modal(modalEl);

    async function refreshShopsForDistributor(){
        const distId = document.getElementById("assignDistributorSelect").value;
        const shopSelect = document.getElementById("assignShopSelect");
        shopSelect.innerHTML = `<option value="">Loading...</option>`;
        try{
            const res = await apiRequest(ENDPOINTS.shops + "?distributorId=" + distId);
            const shops = unwrap(res, []).filter(s => String(s.distributorId) === String(distId));
            shopSelect.innerHTML = shops.map(s => `<option value="${s.id}">${escapeHtml(s.shopName)}</option>`).join("") || `<option value="">No shops for this distributor</option>`;
        }catch(err){
            shopSelect.innerHTML = `<option value="">Could not load shops</option>`;
        }
    }
    document.getElementById("assignDistributorSelect").addEventListener("change", refreshShopsForDistributor);
    refreshShopsForDistributor();

    document.getElementById("confirmCreateAssignmentBtn").addEventListener("click", async () => {
        const distributorId = Number(document.getElementById("assignDistributorSelect").value);
        const shopId = Number(document.getElementById("assignShopSelect").value);
        const productId = Number(document.getElementById("assignProductSelect").value);
        const quantity = Number(document.getElementById("assignQty").value);
        const remarks = document.getElementById("assignRemarks").value;
        if (!shopId){ showToast("Select a shop", "This distributor has no shops to assign.", "warning"); return; }
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.distributorAssignments, { method:"POST", body:{ distributorId, shopId, productId, quantity, remarks } });
            modal.hide();
            showToast("Created", "Assignment created and sent to the distributor.", "success");
            loadAndRenderAssignments();
        }catch(err){
            showApiError(err, "Could not create assignment");
        }finally{
            hideSpinner();
        }
    });
    modalEl.addEventListener("hidden.bs.modal", () => host.remove());
    modal.show();
});

/* ==================== DISTRIBUTOR: MY ASSIGNMENTS ==================== */
async function loadAndRenderDistAssignments(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.distributorAssignments);
        const rows = unwrap(res, []);
        document.getElementById("distAssignmentsTableBody").innerHTML = rows.map(a => {
            const badgeClass = { PENDING:"bg-warning text-dark", APPROVED:"bg-success", MODIFIED:"bg-info text-dark", REJECTED:"bg-danger" }[a.status] || "bg-secondary";
            const action = a.status === "PENDING"
                ? `<button class="btn btn-sm btn-success me-1" data-assign-respond="${a.id}" data-assign-action="APPROVED">Approve</button>
                   <button class="btn btn-sm btn-outline-primary me-1" data-assign-respond="${a.id}" data-assign-action="MODIFIED">Modify</button>
                   <button class="btn btn-sm btn-outline-danger" data-assign-respond="${a.id}" data-assign-action="REJECTED">Reject</button>`
                : `<span class="text-muted small">—</span>`;
            return `<tr>
                <td class="fw-600">${escapeHtml(a.shopName)}</td>
                <td>${escapeHtml(a.productName)}</td>
                <td>${a.quantity}${a.modifiedQuantity ? ` → ${a.modifiedQuantity}` : ""}</td>
                <td><span class="badge ${badgeClass}">${escapeHtml(a.status)}</span></td>
                <td class="small text-muted">${escapeHtml(a.adminRemarks || "—")}</td>
                <td class="text-end">${action}</td>
            </tr>`;
        }).join("") || `<tr><td colspan="6" class="text-center text-muted py-4">No assignments yet.</td></tr>`;

        document.getElementById("distAssignmentsTableBody").querySelectorAll("[data-assign-respond]").forEach(btn => {
            btn.addEventListener("click", () => openAssignmentRespondModal(Number(btn.dataset.assignRespond), btn.dataset.assignAction));
        });
    }catch(err){
        showApiError(err, "Could not load assignments");
    }finally{
        hideSpinner();
    }
}

function openAssignmentRespondModal(id, status){
    const title = status === "APPROVED" ? "Approve Assignment" : status === "MODIFIED" ? "Propose Different Quantity" : "Reject Assignment";
    const modalHtml = `
        <div class="modal fade" id="respondAssignmentModal" tabindex="-1">
            <div class="modal-dialog"><div class="modal-content">
                <div class="modal-header"><h5 class="modal-title">${title}</h5><button class="btn-close" data-bs-dismiss="modal"></button></div>
                <div class="modal-body">
                    ${status === "MODIFIED" ? `<div class="mb-2"><label class="form-label">Proposed Quantity</label><input type="number" class="form-control" id="respondQty" min="1" value="1"></div>` : ""}
                    <label class="form-label">Remarks</label>
                    <textarea class="form-control" id="respondRemarks" rows="2"></textarea>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button class="btn ${status === "REJECTED" ? "btn-danger" : "btn-success"}" id="confirmRespondBtn">Confirm</button>
                </div>
            </div></div>
        </div>`;
    document.getElementById("dynamicModalHost")?.remove();
    const host = document.createElement("div");
    host.id = "dynamicModalHost";
    host.innerHTML = modalHtml;
    document.body.appendChild(host);
    const modalEl = document.getElementById("respondAssignmentModal");
    const modal = new bootstrap.Modal(modalEl);
    document.getElementById("confirmRespondBtn").addEventListener("click", async () => {
        const modifiedQuantity = status === "MODIFIED" ? Number(document.getElementById("respondQty").value) : null;
        const distributorRemarks = document.getElementById("respondRemarks").value;
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.distributorAssignments + "/" + id + "/respond", { method:"PUT", body:{ status, modifiedQuantity, distributorRemarks } });
            modal.hide();
            showToast("Response sent", "Admin will see your response.", "success");
            loadAndRenderDistAssignments();
        }catch(err){
            showApiError(err, "Could not respond");
        }finally{
            hideSpinner();
        }
    });
    modalEl.addEventListener("hidden.bs.modal", () => host.remove());
    modal.show();
}

/* ==================== AUDIT LOG ====================
   Admin-only view of AuditLogController's GET /api/v1/audit-logs. */
let AUDIT_LOG_CACHE = [];

async function loadAndRenderAuditLog(){
    showSpinner();
    try{
        const entityType = document.getElementById("auditEntityFilter").value;
        const url = ENDPOINTS.auditLogs + (entityType ? "?entityType=" + entityType : "");
        const res = await apiRequest(url);
        AUDIT_LOG_CACHE = unwrap(res, []);
        renderAuditLog();
    }catch(err){
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        document.getElementById("auditLogTableBody").innerHTML = `<tr><td colspan="6" class="text-center text-muted py-4">Could not load audit log from the server.</td></tr>`;
        showApiError(err, "Could not load audit log");
    }finally{
        hideSpinner();
    }
}

function renderAuditLog(){
    const term = (document.getElementById("auditSearch").value || "").toLowerCase();
    const rows = AUDIT_LOG_CACHE.filter(a => {
        if (!term) return true;
        return [a.performedBy, a.action, a.entityType, a.details].some(v => String(v||"").toLowerCase().includes(term));
    });
    document.getElementById("auditLogTableBody").innerHTML = rows.map(a => `
        <tr>
            <td>${formatDate(a.createdAt)} ${a.createdAt ? new Date(a.createdAt).toLocaleTimeString("en-IN",{hour:"2-digit",minute:"2-digit"}) : ""}</td>
            <td>${escapeHtml(a.performedBy || "—")}</td>
            <td>${escapeHtml(a.performedByRole || "—")}</td>
            <td><span class="badge bg-secondary">${escapeHtml(a.action || "—")}</span></td>
            <td>${escapeHtml(a.entityType || "—")}</td>
            <td class="text-muted small">${escapeHtml(a.details || "")}</td>
        </tr>`).join("") || `<tr><td colspan="6" class="text-center text-muted py-4">No activity recorded yet.</td></tr>`;
}

document.getElementById("auditSearch").addEventListener("input", renderAuditLog);
document.getElementById("auditEntityFilter").addEventListener("change", loadAndRenderAuditLog);

const CRUD_CONFIG = {
    users: {
        endpoint: ENDPOINTS.users,
        stateKey: "users",
        tableBody: "usersTableBody",
        searchInput: "usersSearch",
        emptyColspan: 6,
        searchFields: (u, term) => [u.fullName, u.email, u.username].some(v => String(v||"").toLowerCase().includes(term)),
        statusFilterId: "usersStatusFilter",
        statusFilterMatch: (u, val) => !val || (val === "Active" ? u.active : !u.active),
        fields: [
            { key:"fullName", label:"Full Name", type:"text", required:true },
            { key:"username", label:"Username", type:"text", required:true },
            { key:"email", label:"Email", type:"email", required:true },
            { key:"mobileNumber", label:"Mobile Number", type:"text", required:true, placeholder:"10-digit number starting 6-9" },
            { key:"password", label:"Password", type:"password", required:true, editHint:"Leave blank to keep the current password" },
            { key:"roleId", label:"Role ID", type:"number", required:true, editHint:"Numeric ID from the roles table (the backend has no endpoint to list roles by name)" },
            { key:"distributorId", label:"Distributor ID", type:"number", editHint:"Optional. Leave blank for a DISTRIBUTOR user and the matching Distributor profile is created automatically from the details above. Fill it in only to link this login to an existing Distributor record." },
            { key:"superStockistId", label:"Super Stockist ID", type:"number", editHint:"Optional. Leave blank for a SUPER_STOCKIST user and the matching Super Stockist profile is created automatically from the details above. Fill it in only to link this login to an existing record." },
            { key:"active", label:"Status", type:"select-bool", options:["Active","Inactive"] },
        ],
        toRequest(values, isEdit){
            const payload = {
                fullName: values.fullName,
                username: values.username,
                email: values.email,
                mobileNumber: values.mobileNumber,
                roleId: values.roleId ? Number(values.roleId) : null,
                distributorId: values.distributorId ? Number(values.distributorId) : null,
                superStockistId: values.superStockistId ? Number(values.superStockistId) : null,
                active: values.active,
            };
            if (!isEdit || (values.password && values.password.trim())) payload.password = values.password;
            return payload;
        },
        row(u){
            return `<tr>
        <td><div class="avatar-name"><img src="${avatarUrl(u.fullName)}"><div><div class="fw-600" style="font-size:13.5px">${escapeHtml(u.fullName)}</div><div style="font-size:11.5px;color:var(--text-muted)">@${escapeHtml(u.username)}</div></div></div></td>
        <td>${escapeHtml(u.email)}</td><td>${escapeHtml(u.mobileNumber)}</td><td>${escapeHtml(u.role || "—")}</td>
        <td>${activeBadge(u.active)}</td>
        <td class="text-end">
          <button class="action-btn view" data-action="profile" data-id="${u.id}" title="View Profile"><i class="fa-solid fa-eye"></i></button>
          <button class="action-btn edit" data-action="edit" data-id="${u.id}" title="Edit"><i class="fa-solid fa-pen"></i></button>
          <button class="action-btn delete" data-action="delete" data-id="${u.id}" title="Delete"><i class="fa-solid fa-trash"></i></button>
        </td></tr>`;
        }
    },
    distributors: {
        endpoint: ENDPOINTS.distributors,
        stateKey: "distributors",
        tableBody: "distributorsTableBody",
        searchInput: "distributorsSearch",
        emptyColspan: 6,
        searchFields: (d, term) => [d.distributorName, d.city, d.mobileNumber].some(v => String(v||"").toLowerCase().includes(term)),
        areaFilterId: "distributorsAreaFilter",
        statusFilterId: "distributorsStatusFilter",
        statusFilterMatch: (d, val) => !val || (val === "Active" ? d.active : !d.active),
        districtFilterId: "distributorsDistrictFilter",
        fields: [
            { key:"distributorName", label:"Distributor Name", type:"text", required:true },
            { key:"contactPerson", label:"Contact Person", type:"text", required:true },
            { key:"mobileNumber", label:"Mobile Number", type:"text", required:true, placeholder:"10-digit number starting 6-9" },
            { key:"email", label:"Email", type:"email" },
            { key:"address", label:"Address", type:"textarea" },
            { key:"city", label:"City / Area", type:"text" },
            { key:"state", label:"State", type:"text" },
            { key:"district", label:"District", type:"text" },
            { key:"pincode", label:"Pincode", type:"text" },
            { key:"gstNumber", label:"GST Number", type:"text" },
            { key:"licenseNumber", label:"License Number", type:"text" },
            { key:"active", label:"Status", type:"select-bool", options:["Active","Inactive"] },
        ],
        toRequest(values){
            return {
                distributorName: values.distributorName, contactPerson: values.contactPerson,
                mobileNumber: values.mobileNumber, email: values.email || null,
                address: values.address || null, city: values.city || null, state: values.state || null,
                district: values.district || null, pincode: values.pincode || null, gstNumber: values.gstNumber || null,
                licenseNumber: values.licenseNumber || null, active: values.active,
            };
        },
        row(d){
            return `<tr><td class="fw-600">${escapeHtml(d.distributorName)}</td><td>${escapeHtml(d.city || "—")}</td><td>${escapeHtml(d.mobileNumber)}</td><td>${escapeHtml(d.superStockistName || "Unassigned")}</td><td>${activeBadge(d.active)}</td>
        <td class="text-end">
          <button class="action-btn edit" data-action="edit" data-id="${d.id}"><i class="fa-solid fa-pen"></i></button>
          <button class="action-btn delete" data-action="delete" data-id="${d.id}"><i class="fa-solid fa-trash"></i></button>
        </td></tr>`;
        }
    },
    superstockists: {
        endpoint: ENDPOINTS.superStockists,
        stateKey: "superStockists",
        tableBody: "superStockistsTableBody",
        searchInput: "superStockistsSearch",
        emptyColspan: 6,
        searchFields: (s, term) => [s.superStockistName, s.district, s.mobileNumber].some(v => String(v||"").toLowerCase().includes(term)),
        statusFilterId: "superStockistsStatusFilter",
        statusFilterMatch: (s, val) => !val || (val === "Active" ? s.active : !s.active),
        districtFilterId: "superStockistsDistrictFilter",
        fields: [
            { key:"superStockistName", label:"Super Stockist Name", type:"text", required:true },
            { key:"contactPerson", label:"Contact Person", type:"text", required:true },
            { key:"mobileNumber", label:"Mobile Number", type:"text", required:true, placeholder:"10-digit number starting 6-9" },
            { key:"email", label:"Email", type:"email" },
            { key:"address", label:"Address", type:"textarea" },
            { key:"city", label:"City", type:"text" },
            { key:"state", label:"State", type:"text" },
            { key:"district", label:"District", type:"text" },
            { key:"pincode", label:"Pincode", type:"text" },
            { key:"gstNumber", label:"GST Number", type:"text" },
            { key:"licenseNumber", label:"License Number", type:"text" },
            { key:"active", label:"Status", type:"select-bool", options:["Active","Inactive"] },
        ],
        toRequest(values){
            return {
                superStockistName: values.superStockistName, contactPerson: values.contactPerson,
                mobileNumber: values.mobileNumber, email: values.email || null,
                address: values.address || null, city: values.city || null, state: values.state || null,
                district: values.district || null, pincode: values.pincode || null,
                gstNumber: values.gstNumber || null, licenseNumber: values.licenseNumber || null,
                active: values.active,
            };
        },
        row(s){
            return `<tr><td class="fw-600">${escapeHtml(s.superStockistName)}</td><td>${escapeHtml(s.district || "—")}</td><td>${escapeHtml(s.mobileNumber)}</td>
        <td>${s.assignedDistributorCount ?? 0}</td><td>${activeBadge(s.active)}</td>
        <td class="text-end">
          <button class="action-btn view" data-action="assign" data-id="${s.id}" title="Assign Distributors"><i class="fa-solid fa-diagram-project"></i></button>
          <button class="action-btn edit" data-action="edit" data-id="${s.id}"><i class="fa-solid fa-pen"></i></button>
          <button class="action-btn delete" data-action="delete" data-id="${s.id}"><i class="fa-solid fa-trash"></i></button>
        </td></tr>`;
        }
    },
    products: {
        endpoint: ENDPOINTS.products,
        stateKey: "products",
        tableBody: "productsTableBody",
        searchInput: "productsSearch",
        emptyColspan: 11,
        searchFields: (p, term) => [p.productName, p.barcode, p.productCode, p.batchNumber, p.hsnSacCode].some(v => String(v||"").toLowerCase().includes(term)),
        categoryFilterId: "productsCategoryFilter",
        fields: [
            { key:"productName", label:"Product Name", type:"text", required:true },
            { key:"productCode", label:"Product Code", type:"text", required:true, editHint:"Must be unique" },
            { key:"batchNumber", label:"Batch No.", type:"text" },
            { key:"hsnSacCode", label:"HSN/SAC Code", type:"text" },
            { key:"barcode", label:"Barcode", type:"text" },
            { key:"categoryId", label:"Category", type:"category-select", required:true },
            { key:"brandName", label:"Brand", type:"text" },
            { key:"unit", label:"Unit", type:"select", options:["PIECE","BOX","PACK","CARTON","BOTTLE","STRIP","DOZEN","KG","GRASS","LITRE","ML"] },
            { key:"stockQuantity", label:"Stock Quantity", type:"number", required:true, editHint:"Sets only the starting stock when creating a product. Editing an existing product ignores this field — use the Stock In / Adjust Stock action to change stock, since only that path records it in the Product Ledger." },
            { key:"minimumStock", label:"Minimum Stock (low-stock alert)", type:"number" },
            { key:"purchasePrice", label:"Purchase Price (₹)", type:"number" },
            { key:"ssPrice", label:"SS Price (₹) — Company → Super Stockist rate", type:"number" },
            { key:"distributorPrice", label:"Distributor Price / DP (₹) — Super Stockist → Distributor rate", type:"number" },
            { key:"sellingPrice", label:"Selling Price / SP (₹) — Distributor → Shop rate", type:"number", required:true },
            { key:"mrp", label:"MRP (₹)", type:"number" },
            { key:"gstPercentage", label:"GST (%)", type:"number", required:true },
            { key:"discountPercent", label:"Discount (%) — auto: MRP vs Selling Price", type:"readonly-computed", computeFrom:["mrp","sellingPrice"],
              compute:(vals) => {
                  const mrp = Number(vals.mrp) || 0;
                  const sp = Number(vals.sellingPrice) || 0;
                  if (mrp <= 0) return "";
                  const pct = Math.max(0, ((mrp - sp) / mrp) * 100);
                  return pct > 0 ? pct.toFixed(1) + "%" : "0%";
              } },
            { key:"description", label:"Description", type:"textarea" },
            { key:"active", label:"Status", type:"select-bool", options:["Active","Inactive"] },
        ],
        toRequest(values){
            return {
                productName: values.productName, productCode: values.productCode, barcode: values.barcode || null,
                batchNumber: values.batchNumber || null, hsnSacCode: values.hsnSacCode || null,
                categoryId: values.categoryId ? Number(values.categoryId) : null,
                brandName: values.brandName || null, unit: values.unit || null,
                stockQuantity: values.stockQuantity !== "" ? Number(values.stockQuantity) : 0,
                minimumStock: values.minimumStock !== "" ? Number(values.minimumStock) : null,
                purchasePrice: values.purchasePrice !== "" ? Number(values.purchasePrice) : null,
                ssPrice: values.ssPrice !== "" ? Number(values.ssPrice) : null,
                distributorPrice: values.distributorPrice !== "" ? Number(values.distributorPrice) : null,
                sellingPrice: values.sellingPrice !== "" ? Number(values.sellingPrice) : null,
                mrp: values.mrp !== "" ? Number(values.mrp) : null,
                gstPercentage: values.gstPercentage !== "" ? Number(values.gstPercentage) : null,
                description: values.description || null,
                active: values.active,
            };
        },
        row(p, i){
            const low = p.minimumStock != null ? Number(p.stockQuantity) <= Number(p.minimumStock) : Number(p.stockQuantity) <= 10;
            // Discount shown here is derived (not a stored field): how much the
            // selling price is off the product's MRP, for a quick at-a-glance view.
            const mrpNum = Number(p.mrp) || 0;
            const spNum = Number(p.sellingPrice) || 0;
            const discPct = mrpNum > 0 ? Math.max(0, ((mrpNum - spNum) / mrpNum) * 100) : 0;
            return `<tr>
        <td>${(i ?? 0) + 1}</td>
        <td>${escapeHtml(p.batchNumber || "—")}</td>
        <td>${escapeHtml(p.hsnSacCode || "—")}</td>
        <td><div class="avatar-name"><img style="border-radius:10px" src="${p.productImage || avatarUrl(p.productName)}"><span class="fw-600" style="font-size:13.5px">${escapeHtml(p.productName)}</span></div></td>
        <td>${escapeHtml(p.categoryName || "—")}</td><td>${escapeHtml(p.barcode || "—")}</td>
        <td>${p.stockQuantity ?? 0} ${low ? '<span class="status-badge status-unpaid ms-1">Low</span>' : ""}</td>
        <td>${formatCurrency(p.sellingPrice)}</td><td>${p.gstPercentage ?? 0}%</td>
        <td>${discPct > 0 ? discPct.toFixed(1) + "%" : "—"}</td>
        <td class="text-end">
          <button class="action-btn edit" data-action="edit" data-id="${p.id}"><i class="fa-solid fa-pen"></i></button>
          <button class="action-btn" data-action="pricing" data-id="${p.id}" data-name="${escapeHtml(p.productName)}" title="Per-partner pricing"><i class="fa-solid fa-tags"></i></button>
          <button class="action-btn delete" data-action="delete" data-id="${p.id}"><i class="fa-solid fa-trash"></i></button>
        </td></tr>`;
        }
    },
    categories: {
        endpoint: ENDPOINTS.categories,
        stateKey: "categories",
        tableBody: "categoriesTableBody",
        searchInput: "categoriesSearch",
        emptyColspan: 5,
        searchFields: (c, term) => String(c.categoryName||"").toLowerCase().includes(term),
        statusFilterId: "categoriesStatusFilter",
        statusFilterMatch: (c, val) => !val || (val === "Active" ? c.active : !c.active),
        fields: [
            { key:"categoryName", label:"Category Name", type:"text", required:true },
            { key:"description", label:"Description", type:"textarea" },
            { key:"active", label:"Status", type:"select-bool", options:["Active","Inactive"] },
        ],
        toRequest(values){
            return { categoryName: values.categoryName, description: values.description || null, active: values.active };
        },
        row(c){
            // Backend's CategoryResponse doesn't return a product count, so this is
            // derived client-side (display only) from the already-loaded products list.
            const count = STATE.products.filter(p => p.categoryName === c.categoryName).length;
            return `<tr><td class="fw-600">${escapeHtml(c.categoryName)}</td><td>${escapeHtml(c.description || "—")}</td><td>${count}</td><td>${activeBadge(c.active)}</td>
        <td class="text-end">
          <button class="action-btn edit" data-action="edit" data-id="${c.id}"><i class="fa-solid fa-pen"></i></button>
          <button class="action-btn delete" data-action="delete" data-id="${c.id}"><i class="fa-solid fa-trash"></i></button>
        </td></tr>`;
        }
    },
    shops: {
        endpoint: ENDPOINTS.shops,
        stateKey: "shops",
        tableBody: "shopsTableBody",
        searchInput: "shopsSearch",
        emptyColspan: 7,
        searchFields: (s, term) => [s.shopName, s.gstNumber, s.city, s.district].some(v => String(v||"").toLowerCase().includes(term)),
        statusFilterId: "shopsStatusFilter",
        statusFilterMatch: (s, val) => !val || (val === "Active" ? s.active : !s.active),
        districtFilterId: "shopsDistrictFilter",
        distributorFilterId: "shopsDistributorFilter",
        fields: [
            { key:"distributorId", label:"Distributor", type:"distributor-select", required:true },
            { key:"shopName", label:"Shop Name", type:"text", required:true },
            { key:"ownerName", label:"Owner Name", type:"text", required:true },
            { key:"mobileNumber", label:"Mobile Number", type:"text", required:true, placeholder:"10-digit number starting 6-9" },
            { key:"email", label:"Email", type:"email" },
            { key:"address", label:"Address", type:"textarea" },
            { key:"city", label:"City", type:"text" },
            { key:"state", label:"State", type:"text" },
            { key:"district", label:"District", type:"text" },
            { key:"pincode", label:"Pincode", type:"text" },
            { key:"gstNumber", label:"GST Number", type:"text" },
            { key:"remarks", label:"Remarks", type:"textarea" },
            { key:"active", label:"Status", type:"select-bool", options:["Active","Inactive"] },
        ],
        toRequest(values){
            return {
                distributorId: values.distributorId ? Number(values.distributorId) : null,
                shopName: values.shopName, ownerName: values.ownerName, mobileNumber: values.mobileNumber,
                email: values.email || null, address: values.address || null, city: values.city || null,
                state: values.state || null, district: values.district || null, pincode: values.pincode || null, gstNumber: values.gstNumber || null,
                remarks: values.remarks || null, active: values.active,
            };
        },
        row(s){
            return `<tr><td class="fw-600">${escapeHtml(s.shopName)}</td><td>${escapeHtml(s.address || "—")}</td><td>${escapeHtml(s.gstNumber || "—")}</td><td>${escapeHtml(s.mobileNumber)}</td><td>${escapeHtml(s.distributorName || "—")}</td><td>${activeBadge(s.active)}</td>
        <td class="text-end">
          <button class="action-btn edit" data-action="edit" data-id="${s.id}"><i class="fa-solid fa-pen"></i></button>
          <button class="action-btn delete" data-action="delete" data-id="${s.id}"><i class="fa-solid fa-trash"></i></button>
        </td></tr>`;
        }
    },
};

let usersPage = 1;
const PAGE_SIZE = 5;

// Products/Distributors tables have real #productsPagination /
// #distributorsPagination containers already sitting in the DOM (see
// index.html) but nothing ever populated them — same client-side
// pagination approach as the Users table above, just generalized to any
// module name instead of hardcoding "users".
const PAGINATED_MODULES = {
    users:        { containerId: "usersPagination",        pageSize: PAGE_SIZE },
    products:     { containerId: "productsPagination",     pageSize: PAGE_SIZE },
    distributors: { containerId: "distributorsPagination", pageSize: PAGE_SIZE },
};
const crudModulePage = { users: 1, products: 1, distributors: 1 };

function categoryOptionsHtml(selected){
    return STATE.categories.map(c => `<option value="${c.id}" ${c.id==selected?"selected":""}>${escapeHtml(c.categoryName)}</option>`).join("");
}
function distributorOptionsHtml(selected){
    return STATE.distributors.map(d => `<option value="${d.id}" ${d.id==selected?"selected":""}>${escapeHtml(d.distributorName)}</option>`).join("");
}

/** loadAndRenderCrud — GET the module's list from the backend, cache it in
 * STATE, then render the table from that response only. Called on page
 * load and after every successful Create/Update/Delete for that module. */
async function loadAndRenderCrud(moduleName, page){
    const cfg = CRUD_CONFIG[moduleName];
    const tbody = document.getElementById(cfg.tableBody);
    try{
        const res = await apiRequest(cfg.endpoint);
        STATE[cfg.stateKey] = unwrap(res, []);
    }catch(err){
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        if (tbody) tbody.innerHTML = `<tr><td colspan="${cfg.emptyColspan}" class="text-center text-muted py-4">Could not load data from the server.</td></tr>`;
        showApiError(err, "Could not load " + moduleName);
        return;
    }
    // "categories" belongs here even though no filter lives on the
    // Categories page itself: the PRODUCTS page has a category filter
    // built from STATE.categories, so renaming or deleting a category
    // left that dropdown showing stale options until an unrelated
    // products reload happened to refresh it.
    if (["products","shops","distributors","superstockists","categories"].includes(moduleName)) populateFilterDropdowns();
    renderCrudTable(moduleName, page || 1);
}

function renderCrudTable(moduleName, page = 1){
    const cfg = CRUD_CONFIG[moduleName];
    const searchEl = document.getElementById(cfg.searchInput);
    const term = (searchEl?.value || "").toLowerCase();

    let rows = STATE[cfg.stateKey].filter(item => !term || cfg.searchFields(item, term));

    if (cfg.statusFilterId){
        const val = document.getElementById(cfg.statusFilterId)?.value;
        if (val) rows = rows.filter(item => cfg.statusFilterMatch(item, val));
    }
    if (cfg.categoryFilterId){
        const val = document.getElementById(cfg.categoryFilterId)?.value;
        if (val) rows = rows.filter(item => String(item.categoryId ?? "") === val);
    }
    if (cfg.areaFilterId){
        const val = document.getElementById(cfg.areaFilterId)?.value;
        if (val) rows = rows.filter(item => String(item.city||"") === val);
    }
    if (cfg.districtFilterId){
        const val = document.getElementById(cfg.districtFilterId)?.value;
        if (val) rows = rows.filter(item => String(item.district||"") === val);
    }
    if (cfg.distributorFilterId){
        const val = document.getElementById(cfg.distributorFilterId)?.value;
        if (val) rows = rows.filter(item => String(item.distributorId ?? "") === val);
    }

    const tbody = document.getElementById(cfg.tableBody);
    if (!tbody) return;

    const pagination = PAGINATED_MODULES[moduleName];
    if (pagination){
        const totalPages = Math.max(1, Math.ceil(rows.length / pagination.pageSize));
        const currentPage = Math.min(page, totalPages);
        crudModulePage[moduleName] = currentPage;
        usersPage = crudModulePage.users; // kept in sync — some older code may still read this global.
        const start = (currentPage - 1) * pagination.pageSize;
        const pageRows = rows.slice(start, start + pagination.pageSize);
        tbody.innerHTML = pageRows.map((item, i) => cfg.row(item, start + i)).join("") || `<tr><td colspan="${cfg.emptyColspan}" class="text-center text-muted py-4">No records found.</td></tr>`;
        renderPagination(pagination.containerId, totalPages, currentPage, (p) => renderCrudTable(moduleName, p));
    } else {
        tbody.innerHTML = rows.map((item, i) => cfg.row(item, i)).join("") || `<tr><td colspan="${cfg.emptyColspan}" class="text-center text-muted py-4">No records found.</td></tr>`;
    }

    tbody.querySelectorAll("[data-action]").forEach(btn => {
        btn.addEventListener("click", () => {
            const id = btn.dataset.id;
            const action = btn.dataset.action;
            if (action === "edit") openCrudModal(moduleName, id);
            else if (action === "delete") deleteCrudItem(moduleName, id);
            else if (action === "profile") openProfileModal(id);
            else if (action === "assign") openAssignDistributorsModal(id);
            else if (action === "pricing") openProductPricingModal(id, btn.dataset.name);
        });
    });
}

function renderPagination(containerId, totalPages, current, onPage){
    const el = document.getElementById(containerId);
    if (!el) return;
    let html = "";
    for (let i=1;i<=totalPages;i++){
        html += `<button class="page-btn ${i===current?"active":""}" data-page="${i}">${i}</button>`;
    }
    el.innerHTML = html;
    el.querySelectorAll("[data-page]").forEach(b => b.addEventListener("click", () => onPage(Number(b.dataset.page))));
}

function openProfileModal(id){
    const u = STATE.users.find(x => x.id == id);
    if (!u) return;
    document.getElementById("profileModalBody").innerHTML = `
    <img src="${avatarUrl(u.fullName)}" style="width:90px;height:90px;border-radius:50%" class="mb-3">
    <h5 class="mb-0">${escapeHtml(u.fullName)}</h5>
    <p class="mb-3">@${escapeHtml(u.username)}</p>
    <div class="text-start px-2">
      <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Email</span><b>${escapeHtml(u.email)}</b></div>
      <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Phone</span><b>${escapeHtml(u.mobileNumber)}</b></div>
      <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Role</span><b>${escapeHtml(u.role || "—")}</b></div>
      <div class="d-flex justify-content-between py-2"><span class="text-muted">Status</span>${activeBadge(u.active)}</div>
    </div>`;
    new bootstrap.Modal(document.getElementById("profileModal")).show();
}

async function openProductPricingModal(productId, productName){
    document.getElementById("productPricingModalName").textContent = productName || "";
    const modalEl = document.getElementById("productPricingModal");
    const modal = new bootstrap.Modal(modalEl);
    modal.show();
    await loadAndRenderPricingTab(productId, "ss");
    await loadAndRenderPricingTab(productId, "dist");
}

async function loadAndRenderPricingTab(productId, kind){
    const isSs = kind === "ss";
    const listUrl = ENDPOINTS.productPricing + "/products/" + productId + (isSs ? "/ss-prices" : "/distributor-prices");
    const bodyId = isSs ? "pricingSsBody" : "pricingDistBody";
    const tbody = document.getElementById(bodyId);
    tbody.innerHTML = `<tr><td colspan="3" class="text-center text-muted py-3">Loading...</td></tr>`;
    try{
        const res = await apiRequest(listUrl);
        const rows = unwrap(res, []);
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td>${escapeHtml(r.partnerName)}</td>
                <td>
                    <input type="number" class="form-control form-control-sm pricing-input"
                           data-product="${productId}" data-partner="${r.partnerId}" data-kind="${kind}"
                           value="${r.price ?? ""}" style="max-width:140px;${r.overridden ? "" : "color:#9aa0a6"}">
                </td>
                <td>
                    <button class="btn btn-sm btn-gradient pricing-save-btn" data-product="${productId}" data-partner="${r.partnerId}" data-kind="${kind}">Save</button>
                    ${r.overridden ? `<button class="btn btn-sm btn-outline-secondary ms-1 pricing-reset-btn" data-product="${productId}" data-partner="${r.partnerId}" data-kind="${kind}">Reset</button>` : ""}
                </td>
            </tr>`).join("") || `<tr><td colspan="3" class="text-center text-muted py-4">No ${isSs ? "Super Stockists" : "Distributors"} found.</td></tr>`;

        tbody.querySelectorAll(".pricing-save-btn").forEach(btn => {
            btn.addEventListener("click", async () => {
                const row = btn.closest("tr");
                const input = row.querySelector(".pricing-input");
                const price = Number(input.value);
                if (!input.value || isNaN(price) || price < 0){
                    showToast("Invalid price", "Enter a valid non-negative price.", "error");
                    return;
                }
                const url = ENDPOINTS.productPricing + "/products/" + btn.dataset.product
                    + (btn.dataset.kind === "ss" ? "/ss-prices/" : "/distributor-prices/") + btn.dataset.partner;
                try{
                    await apiRequest(url, { method: "PUT", body: { price } });
                    showToast("Saved", "Custom price updated.", "success");
                    loadAndRenderPricingTab(btn.dataset.product, btn.dataset.kind);
                }catch(err){ showApiError(err, "Could not save price"); }
            });
        });
        tbody.querySelectorAll(".pricing-reset-btn").forEach(btn => {
            btn.addEventListener("click", async () => {
                const url = ENDPOINTS.productPricing + "/products/" + btn.dataset.product
                    + (btn.dataset.kind === "ss" ? "/ss-prices/" : "/distributor-prices/") + btn.dataset.partner;
                try{
                    await apiRequest(url, { method: "DELETE" });
                    showToast("Reset", "Reverted to the default price.", "success");
                    loadAndRenderPricingTab(btn.dataset.product, btn.dataset.kind);
                }catch(err){ showApiError(err, "Could not reset price"); }
            });
        });
    }catch(err){
        tbody.innerHTML = `<tr><td colspan="3" class="text-center text-danger py-3">Could not load pricing.</td></tr>`;
        showApiError(err, "Could not load pricing");
    }
}

function fieldControl(f, value){
    const v = value ?? "";
    if (f.type === "select"){
        return `<select class="form-select" data-field="${f.key}">${f.options.map(o=>`<option value="${o}" ${o===v?"selected":""}>${o}</option>`).join("")}</select>`;
    }
    if (f.type === "select-bool"){
        const isActive = value === undefined ? true : !!value;
        return `<select class="form-select" data-field="${f.key}" data-type="bool">
          <option value="true" ${isActive?"selected":""}>Active</option>
          <option value="false" ${!isActive?"selected":""}>Inactive</option>
        </select>`;
    }
    if (f.type === "category-select"){
        return `<select class="form-select" data-field="${f.key}"><option value="">Select category…</option>${categoryOptionsHtml(v)}</select>`;
    }
    if (f.type === "distributor-select"){
        const user = getCurrentUser();
        const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
        const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";
        // Spec: a distributor never picks a distributor from a dropdown —
        // they're auto-assigned to themselves (e.g. creating their own shop).
        if (!isAdminRole && user && user.distributorId){
            return `<select class="form-select" data-field="${f.key}" disabled><option value="${user.distributorId}" selected>${escapeHtml(user.distributorName || "My Distributor Account")}</option></select>
              <input type="hidden" data-field="${f.key}" value="${user.distributorId}">`;
        }
        return `<select class="form-select" data-field="${f.key}"><option value="">Select distributor…</option>${distributorOptionsHtml(v)}</select>`;
    }
    if (f.type === "textarea"){
        return `<textarea class="form-control" rows="2" data-field="${f.key}">${escapeHtml(v)}</textarea>`;
    }
    if (f.type === "readonly-computed"){
        return `<input type="text" class="form-control" data-field="${f.key}" data-computed="1" value="${escapeHtml(v)}" disabled>`;
    }
    // Every numeric field rendered by this generic engine (IDs, stock
    // quantities, prices, GST%) is a non-negative value in this domain —
    // min="0" gives an instant browser-level guard instead of only finding
    // out a negative value is invalid after a round-trip to the backend.
    const numAttrs = f.type === "number" ? ' min="0"' : "";
    return `<input type="${f.type}" class="form-control" data-field="${f.key}" value="${escapeHtml(v)}" placeholder="${escapeHtml(f.placeholder||"")}" ${f.required?"required":""}${numAttrs}>`;
}

function setupProductImageSection(moduleName, id, item){
    const section = document.getElementById("crudProductImageSection");
    const input = document.getElementById("crudProductImageInput");
    const preview = document.getElementById("crudProductImagePreview");
    const uploadBtn = document.getElementById("crudProductImageUploadBtn");

    if (moduleName !== "products" || !id){
        section.classList.add("d-none");
        return;
    }

    section.classList.remove("d-none");
    input.value = "";
    if (item && item.productImage){
        preview.src = item.productImage;
        preview.style.display = "";
    } else {
        preview.style.display = "none";
    }

    uploadBtn.onclick = async () => {
        const file = input.files && input.files[0];
        if (!file){ showToast("No file selected", "Choose an image to upload first.", "warning"); return; }

        const formData = new FormData();
        formData.append("file", file);

        uploadBtn.disabled = true;
        showSpinner();
        try{
            const res = await apiRequestMultipart(ENDPOINTS.products + "/" + id + "/image", formData);
            const updated = unwrap(res, null);
            if (updated && updated.productImage){
                preview.src = updated.productImage;
                preview.style.display = "";
            }
            showToast("Image uploaded", "Product image updated successfully.", "success");
            await loadAndRenderCrud(moduleName);
        }catch(err){
            showApiError(err, "Image upload failed");
        }finally{
            uploadBtn.disabled = false;
            hideSpinner();
        }
    };
}

function openCrudModal(moduleName, id = null){
    const cfg = CRUD_CONFIG[moduleName];
    const item = id ? STATE[cfg.stateKey].find(x => x.id == id) : {};
    document.getElementById("crudModalTitle").textContent = (id ? "Edit " : "Add ") + moduleName.slice(0,1).toUpperCase() + moduleName.slice(1,-1);

    const fieldsHtml = cfg.fields.map(f => {
        // categoryId/distributorId aren't present on *Response DTOs for edit
        // pre-fill in every case (ProductResponse only has categoryName) —
        // best-effort match back to STATE by display name so edit forms still
        // pre-select the right option when possible.
        let value = item[f.key];
        if (f.type === "category-select" && value === undefined && item.categoryName){
            const match = STATE.categories.find(c => c.categoryName === item.categoryName);
            value = match ? match.id : "";
        }
        if (f.type === "password") value = ""; // never pre-fill password hashes
        if (f.type === "readonly-computed" && typeof f.compute === "function"){
            value = f.compute(item || {});
        }
        const colClass = (f.type === "textarea") ? "col-12" : "col-md-6";
        const hint = id && f.editHint ? `<div class="form-text" style="font-size:11px">${escapeHtml(f.editHint)}</div>` : (!id && f.editHint && f.type !== "password" ? "" : "");
        return `<div class="${colClass}"><label class="form-label-soft">${f.label}</label>${fieldControl(f, value)}${hint}</div>`;
    }).join("");

    document.getElementById("crudFormFields").innerHTML = fieldsHtml;

    // Wire up any "readonly-computed" fields (e.g. Products' Discount %)
    // so they recompute live as the fields they depend on change, instead
    // of only reflecting whatever was true when the modal opened.
    const formEl = document.getElementById("crudFormFields");
    cfg.fields.filter(f => f.type === "readonly-computed" && typeof f.compute === "function").forEach(f => {
        const targetEl = formEl.querySelector(`[data-field="${f.key}"]`);
        if (!targetEl) return;
        const recompute = () => {
            const vals = {};
            (f.computeFrom || []).forEach(depKey => {
                const depEl = formEl.querySelector(`[data-field="${depKey}"]`);
                vals[depKey] = depEl ? depEl.value : "";
            });
            targetEl.value = f.compute(vals);
        };
        (f.computeFrom || []).forEach(depKey => {
            const depEl = formEl.querySelector(`[data-field="${depKey}"]`);
            depEl?.addEventListener("input", recompute);
        });
    });

    // BUG-H15 fix: product image upload. Files don't belong in the JSON
    // create/update payload, so this is a separate control that uploads
    // immediately via the dedicated /products/{id}/image endpoint — same
    // pattern as PaymentProof, which uploads against an already-existing
    // Payment id rather than embedding the file in that entity's own
    // create/update body. A brand-new product has no id yet, so the
    // section is only shown once editing an existing one (i.e. right
    // after creation, the admin re-opens Edit to attach an image).
    setupProductImageSection(moduleName, id, item);

    const saveBtn = document.getElementById("crudSaveBtn");
    const modal = new bootstrap.Modal(document.getElementById("crudModal"));

    saveBtn.onclick = async () => {
        const form = document.getElementById("crudFormFields");
        const values = {};
        let valid = true;
        form.querySelectorAll("[data-field]").forEach(el => {
            let val = el.value;
            if (el.dataset.type === "bool") val = (val === "true");
            if (el.required && !String(val).trim() && el.dataset.type !== "bool") valid = false;
            values[el.dataset.field] = val;
        });
        if (!valid){ showToast("Missing fields", "Please fill all required fields.", "error"); return; }

        const payload = cfg.toRequest(values, !!id);

        saveBtn.disabled = true;
        showSpinner();
        try{
            if (id){
                await apiRequest(cfg.endpoint + "/" + id, { method:"PUT", body: payload });
            } else {
                await apiRequest(cfg.endpoint, { method:"POST", body: payload });
            }
            modal.hide();
            showToast(id ? "Updated" : "Created", `${moduleName.slice(0,1).toUpperCase()+moduleName.slice(1,-1)} saved successfully.`, "success");
            // Reload straight from the backend — never mutate STATE locally.
            await loadAndRenderCrud(moduleName);
            refreshDashboard();
        }catch(err){
            // Do NOT close the modal or touch the table — show the backend's
            // own error message so the user knows exactly why it failed.
            showApiError(err, id ? "Update failed" : "Create failed");
        }finally{
            saveBtn.disabled = false;
            hideSpinner();
        }
    };

    modal.show();
}

function deleteCrudItem(moduleName, id){
    showConfirm("Delete Record", "This action cannot be undone. Continue?", async () => {
        showSpinner();
        try{
            await apiRequest(CRUD_CONFIG[moduleName].endpoint + "/" + id, { method:"DELETE" });
            showToast("Deleted", "Record removed successfully.", "success");
            await loadAndRenderCrud(moduleName);
            refreshDashboard();
        }catch(err){
            showApiError(err, "Delete failed");
        }finally{
            hideSpinner();
        }
    });
}

// Add-buttons wiring
document.getElementById("addUserBtn").addEventListener("click", () => openCrudModal("users"));
document.getElementById("addDistributorBtn").addEventListener("click", () => openCrudModal("distributors"));
document.getElementById("addProductBtn").addEventListener("click", () => {
    if (!STATE.categories.length){ showToast("No categories", "Create a category first so products can be assigned to one.", "warning"); return; }
    openCrudModal("products");
});
document.getElementById("addCategoryBtn").addEventListener("click", () => openCrudModal("categories"));
document.getElementById("addShopBtn").addEventListener("click", () => {
    if (!STATE.distributors.length){ showToast("No distributors", "Create a distributor first so shops can be assigned to one.", "warning"); return; }
    openCrudModal("shops");
});

// search/filter wiring
["usersSearch","distributorsSearch","productsSearch","categoriesSearch","shopsSearch","superStockistsSearch"].forEach(id => {
    document.getElementById(id).addEventListener("input", () => {
        const moduleName = id.replace("Search","").toLowerCase();
        renderCrudTable(moduleName, 1);
    });
});
document.getElementById("usersStatusFilter").addEventListener("change", () => renderCrudTable("users", 1));
document.getElementById("productsCategoryFilter").addEventListener("change", () => renderCrudTable("products"));
document.getElementById("distributorsAreaFilter").addEventListener("change", () => renderCrudTable("distributors"));
document.getElementById("distributorsDistrictFilter").addEventListener("change", () => renderCrudTable("distributors"));
document.getElementById("distributorsStatusFilter").addEventListener("change", () => renderCrudTable("distributors"));
document.getElementById("superStockistsStatusFilter").addEventListener("change", () => renderCrudTable("superstockists"));
document.getElementById("superStockistsDistrictFilter").addEventListener("change", () => renderCrudTable("superstockists"));
document.getElementById("shopsStatusFilter").addEventListener("change", () => renderCrudTable("shops"));
document.getElementById("shopsDistrictFilter").addEventListener("change", () => renderCrudTable("shops"));
document.getElementById("shopsDistributorFilter").addEventListener("change", () => renderCrudTable("shops"));
document.getElementById("categoriesStatusFilter").addEventListener("change", () => renderCrudTable("categories"));

/**
 * fillSelectPreserving — rebuild a <select>'s options from current data
 * while keeping the user's current choice if it still exists.
 *
 * Several filters used to be populated behind an `options.length <= 1`
 * guard, i.e. exactly once per page load and never again. Adding,
 * renaming or deleting a distributor/shop then left those dropdowns
 * showing a stale list (a new distributor was unselectable, a renamed one
 * kept its old label) until a full browser reload. Always rebuilding, and
 * only retaining the selection when it still matches a real option,
 * keeps the control honest about what it's filtering on.
 */
let OUTSTANDING_DISTRICTS_SEEN = new Set();

function fillSelectPreserving(selectId, placeholderHtml, items, valueFn, labelFn){
    const select = document.getElementById(selectId);
    if (!select) return;
    const prev = select.value;
    select.innerHTML = placeholderHtml +
        (items || []).map(i => `<option value="${valueFn(i)}">${escapeHtml(labelFn(i))}</option>`).join("");
    select.value = prev;
    // The previous choice no longer exists (deleted/renamed): fall back to
    // the placeholder so the table and the control agree.
    if (select.value !== prev) select.value = "";
}

function populateFilterDropdowns(){
    // Keyed by category ID, not name: a name-keyed filter silently breaks
    // the moment a category is renamed (the retained selection no longer
    // matches any option, so the dropdown resets to "All" or filters on a
    // string nothing has anymore). renderCrudTable matches on categoryId
    // to suit.
    const catSelect = document.getElementById("productsCategoryFilter");
    const prevCat = catSelect.value;
    catSelect.innerHTML = `<option value="">All Categories</option>` + STATE.categories.map(c=>`<option value="${c.id}">${escapeHtml(c.categoryName)}</option>`).join("");
    catSelect.value = prevCat;
    // A category that no longer exists (deleted, or renamed out from
    // under a stale selection) would leave the select showing blank while
    // still filtering -- reset to "All" so the table matches the control.
    if (catSelect.value !== prevCat) catSelect.value = "";

    const stockCatSelect = document.getElementById("stockSummaryCategoryFilter");
    if (stockCatSelect){
        const prevStockCat = stockCatSelect.value;
        stockCatSelect.innerHTML = `<option value="">All Categories</option>` + STATE.categories.map(c=>`<option value="${c.id}">${escapeHtml(c.categoryName)}</option>`).join("");
        stockCatSelect.value = prevStockCat;
    }

    const areaSelect = document.getElementById("distributorsAreaFilter");
    const prevArea = areaSelect.value;
    const areas = [...new Set(STATE.distributors.map(d => d.city).filter(Boolean))];
    areaSelect.innerHTML = `<option value="">All Areas</option>` + areas.map(a=>`<option value="${escapeHtml(a)}">${escapeHtml(a)}</option>`).join("");
    areaSelect.value = prevArea;

    fillDistrictFilter("distributorsDistrictFilter", STATE.distributors);
    fillDistrictFilter("superStockistsDistrictFilter", STATE.superStockists);
    fillDistrictFilter("shopsDistrictFilter", STATE.shops);

    const shopDistSelect = document.getElementById("shopsDistributorFilter");
    const prevShopDist = shopDistSelect.value;
    shopDistSelect.innerHTML = `<option value="">All Distributors</option>` +
        STATE.distributors.map(d=>`<option value="${d.id}">${escapeHtml(d.distributorName)}</option>`).join("");
    shopDistSelect.value = prevShopDist;
}

function fillDistrictFilter(selectId, items){
    const select = document.getElementById(selectId);
    if (!select) return;
    const prev = select.value;
    const districts = [...new Set((items || []).map(i => i.district).filter(Boolean))].sort();
    select.innerHTML = `<option value="">All Districts</option>` + districts.map(d=>`<option value="${escapeHtml(d)}">${escapeHtml(d)}</option>`).join("");
    select.value = prev;
}

/* ==================== 7. DASHBOARD ==================== */
let salesChartInstance, categoryChartInstance;

/**
 * handleSessionExpired — called when any authenticated fetch comes back
 * 401/403 (missing, invalid or expired JWT). Clears the stale token and
 * drops the user back to the login screen instead of silently showing
 * empty/zeroed widgets forever.
 */
function handleSessionExpired(){
    clearToken();
    appWrapper.classList.add("d-none");
    authWrapper.classList.remove("d-none");
    showToast("Session expired", "Please sign in again to continue.", "warning");
}

function setKpiTrend(valueElId, percent){
    const valueEl = document.getElementById(valueElId);
    const card = valueEl && valueEl.closest(".kpi-card");
    const trendEl = card && card.querySelector(".kpi-trend");
    if (!trendEl) return;
    const p = Number(percent) || 0;
    const isUp = p >= 0;
    trendEl.classList.toggle("up", isUp);
    trendEl.classList.toggle("down", !isUp);
    trendEl.innerHTML = `<i class="fa-solid fa-arrow-${isUp ? "up" : "down"}"></i> ${Math.abs(p).toFixed(1)}%`;
}

/** Smoothly swaps a KPI card's text via a brief fade, instead of an instant jump. */
function animateKpiText(elId, text){
    const el = document.getElementById(elId);
    if (!el) return;
    el.classList.add("kpi-updating");
    setTimeout(() => {
        el.textContent = text;
        el.classList.remove("kpi-updating");
    }, 120);
}

/**
 * renderDashboard — role dispatcher. Admin gets the full analytics
 * dashboard (backend, admin-only APIs). A distributor gets their own
 * dashboard built entirely from data they already have legitimate access
 * to (their own invoices/payments/shops) — DashboardController is
 * admin-only end to end, so this never calls it for a distributor login.
 */
async function renderDashboard(){
    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";
    const isSuperStockistRole = role === "SUPER_STOCKIST";

    document.getElementById("adminDashboardBlock").classList.toggle("d-none", !isAdminRole);
    document.getElementById("superStockistDashboardBlock").classList.toggle("d-none", !isSuperStockistRole);
    document.getElementById("distributorDashboardBlock").classList.toggle("d-none", isAdminRole || isSuperStockistRole);
    document.getElementById("dashboardHeading").textContent = isAdminRole ? "Dashboard" : "My Dashboard";
    document.getElementById("dashboardSubheading").textContent = isAdminRole
        ? "Welcome back, here's what's happening with your distribution network."
        : `Welcome back, ${escapeHtml(user && user.fullName || "")} — here's your distribution activity.`;

    if (isAdminRole){
        await renderAdminDashboard();
    } else if (isSuperStockistRole){
        await renderSuperStockistDashboard();
    } else {
        await renderDistributorDashboard();
    }
}

/* Super Stockist dashboard — built only from endpoints that exist today
   (me/profile, me/distributors). Sales/stock KPIs for a Super Stockist's
   full distributor network land in a later phase once the stock-request
   workflow is in place; this deliberately doesn't fake numbers it can't
   back with a real endpoint yet. */
async function renderSuperStockistDashboard(){
    showSpinner();
    try{
        const [profileRes, distributorsRes, requestsRes, transfersRes] = await Promise.all([
            apiRequest(ENDPOINTS.superStockists + "/me/profile"),
            apiRequest(ENDPOINTS.superStockists + "/me/distributors"),
            apiRequest(ENDPOINTS.productRequests).catch(() => ({ data: [] })),
            apiRequest(ENDPOINTS.stockTransfers + "/me/incoming").catch(() => ({ data: [] })),
        ]);
        const profile = unwrap(profileRes, {});
        const distributors = unwrap(distributorsRes, []);
        const requests = unwrap(requestsRes, []);
        const transfers = unwrap(transfersRes, []);

        document.getElementById("ssKpiAssignedDistributors").textContent = distributors.length;
        document.getElementById("ssKpiActiveDistributors").textContent = distributors.filter(d => d.active).length;

        const pendingRequests = requests.filter(r => String(r.status || "").toUpperCase() === "PENDING");
        document.getElementById("ssKpiPendingRequests").textContent = pendingRequests.length;
        document.getElementById("ssKpiIncomingTransfers").textContent = transfers.length;

        const recent = [...distributors].sort((a,b) => new Date(b.createdAt||0) - new Date(a.createdAt||0)).slice(0,8);
        document.getElementById("ssRecentDistributorsBody").innerHTML = recent.map(d => `
            <tr>
                <td>${escapeHtml(d.distributorName)}</td>
                <td>${escapeHtml(d.city || "—")}</td>
                <td>${escapeHtml(d.mobileNumber)}</td>
                <td>${activeBadge(d.active)}</td>
            </tr>`).join("") || `<tr><td colspan="4" class="text-center text-muted py-4">No distributors assigned to you yet.</td></tr>`;

        const recentRequests = [...pendingRequests].sort((a,b) => new Date(b.createdAt||0) - new Date(a.createdAt||0)).slice(0,6);
        // ProductRequestResponse exposes requestedQuantity / approvedQuantity --
        // there is no plain `quantity` field, so reading r.quantity here
        // silently produced undefined and rendered an em-dash in every row.
        document.getElementById("ssRecentRequestsBody").innerHTML = recentRequests.map(r => `
            <tr>
                <td>${escapeHtml(r.productName || "—")}</td>
                <td class="text-end">${r.approvedQuantity ?? r.requestedQuantity ?? "—"}</td>
                <td>${escapeHtml(r.status || "—")}</td>
            </tr>`).join("") || `<tr><td colspan="3" class="text-center text-muted py-4">No pending requests.</td></tr>`;

        const recentTransfers = [...transfers].sort((a,b) => new Date(b.transferDate||0) - new Date(a.transferDate||0)).slice(0,6);
        document.getElementById("ssRecentTransfersBody").innerHTML = recentTransfers.map(t => `
            <tr>
                <td>${escapeHtml(t.productName || "—")}</td>
                <td>${escapeHtml(t.quantity ?? "—")}</td>
                <td>${escapeHtml(formatDate(t.transferDate) || "—")}</td>
            </tr>`).join("") || `<tr><td colspan="3" class="text-center text-muted py-4">No incoming transfers yet.</td></tr>`;
    }catch(err){
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        showApiError(err, "Could not load your dashboard");
    }finally{
        hideSpinner();
    }
}

async function renderDistributorDashboard(){
    showSpinner();
    try{
        if (!STATE.invoices.length) await loadAndRenderInvoices();
        if (!STATE.payments.length) await loadAndRenderPayments();
    } finally {
        hideSpinner();
    }

    const invoices = STATE.invoices;
    const payments = STATE.payments;

    const totalSales = invoices.reduce((sum,i) => sum + (Number(i.totalAmount)||0), 0);
    const pendingInvoices = invoices.filter(i => (Number(i.balanceAmount)||0) > 0).length;
    const completeInvoices = invoices.filter(i => (Number(i.balanceAmount)||0) <= 0).length;
    const totalRevenue = payments.reduce((sum,p) => sum + (Number(p.amount)||0), 0);
    const pendingAmount = invoices.reduce((sum,i) => sum + (Number(i.balanceAmount)||0), 0);

    document.getElementById("distKpiTotalSales").textContent = formatCurrency(totalSales);
    document.getElementById("distKpiPendingOrders").textContent = pendingInvoices;
    document.getElementById("distKpiCompleteOrders").textContent = completeInvoices;
    document.getElementById("distKpiRevenue").textContent = formatCurrency(totalRevenue);
    document.getElementById("distKpiPendingAmount").textContent = formatCurrency(pendingAmount);

    // Recent invoices (own, newest first)
    const recent = [...invoices].sort((a,b) => new Date(b.invoiceDate||0) - new Date(a.invoiceDate||0)).slice(0,8);
    document.getElementById("distRecentInvoicesBody").innerHTML = recent.map(inv => `
        <tr>
            <td>${escapeHtml(inv.invoiceNumber)}</td>
            <td>${escapeHtml(inv.shopName || "—")}</td>
            <td>${formatDate(inv.invoiceDate)}</td>
            <td>${formatCurrency(inv.totalAmount)}</td>
            <td>${normalizeStatusLabel(inv.paymentStatus)}</td>
        </tr>`).join("") || `<tr><td colspan="5" class="text-center text-muted py-4">No invoices yet.</td></tr>`;

    // Pending payment list — invoices still carrying a balance
    const pendingList = invoices.filter(i => (Number(i.balanceAmount)||0) > 0)
        .sort((a,b) => (Number(b.balanceAmount)||0) - (Number(a.balanceAmount)||0)).slice(0,8);
    document.getElementById("distPendingPaymentsList").innerHTML = pendingList.map(inv => `
        <div class="mini-list-item">
            <div>
                <strong>${escapeHtml(inv.shopName || "—")}</strong>
                <div class="text-muted small">${escapeHtml(inv.invoiceNumber)}</div>
            </div>
            <span class="text-danger fw-semibold">${formatCurrency(inv.balanceAmount)}</span>
        </div>`).join("") || `<div class="text-center text-muted py-3">Nothing pending.</div>`;

    // Sales chart — own monthly totals over the trailing 6 months
    const months = [];
    const now = new Date();
    for (let i = 5; i >= 0; i--){
        const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
        months.push({ key: `${d.getFullYear()}-${d.getMonth()}`, label: d.toLocaleString("en-US",{month:"short"}), total: 0 });
    }
    invoices.forEach(inv => {
        if (!inv.invoiceDate) return;
        const d = new Date(inv.invoiceDate);
        const key = `${d.getFullYear()}-${d.getMonth()}`;
        const bucket = months.find(m => m.key === key);
        if (bucket) bucket.total += Number(inv.totalAmount) || 0;
    });

    const ctx = document.getElementById("distSalesChart");
    if (ctx && window.Chart){
        if (ctx._chartInstance) ctx._chartInstance.destroy();
        ctx._chartInstance = new Chart(ctx, {
            type: "bar",
            data: { labels: months.map(m=>m.label), datasets: [{ label: "Sales", data: months.map(m=>m.total), backgroundColor: "#e8a37e" }] },
            options: { responsive:true, plugins:{ legend:{ display:false } }, scales:{ y:{ beginAtZero:true } } }
        });
    }
}

/**
 * renderAdminDashboard — pulls every Dashboard widget straight from the live
 * Spring Boot APIs (no demo/local array fallback, ever). Widgets are
 * fetched in parallel and each renders independently so one failing
 * endpoint doesn't blank out the rest of the page.
 */
async function renderAdminDashboard(){
    const monthsParam = document.getElementById("salesRangeSelect").value === "Last 12 months" ? 12 : 6;

    showSpinner();
    const [dashRes, recentRes, lowStockRes, salesRes, topProductsRes] = await Promise.allSettled([
        apiRequest(ENDPOINTS.dashboard),
        apiRequest(ENDPOINTS.dashboardRecentInvoices),
        apiRequest(ENDPOINTS.dashboardLowStock),
        apiRequest(ENDPOINTS.dashboardSalesSummary + "?months=" + monthsParam),
        apiRequest(ENDPOINTS.dashboardTopProducts + "?limit=4"),
    ]);
    hideSpinner();

    const results = [dashRes, recentRes, lowStockRes, salesRes, topProductsRes];
    const sessionExpired = results.some(r => r.status === "rejected" && (r.reason.status === 401 || r.reason.status === 403));
    if (sessionExpired){ handleSessionExpired(); return; }

    /* ---- KPI cards ---- */
    if (dashRes.status === "fulfilled"){
        const d = unwrap(dashRes.value, {});
        document.getElementById("kpiRevenue").textContent = formatCurrency(d.totalSales);
        document.getElementById("kpiInvoices").textContent = d.totalInvoices ?? 0;
        document.getElementById("kpiDistributors").textContent = d.totalDistributors ?? 0;
        document.getElementById("kpiPending").textContent = formatCurrency(d.totalPendingAmount);
        setKpiTrend("kpiRevenue", d.revenueTrendPercent);
        setKpiTrend("kpiInvoices", d.invoicesTrendPercent);
        setKpiTrend("kpiDistributors", d.distributorsTrendPercent);
        setKpiTrend("kpiPending", d.pendingTrendPercent);

        animateKpiText("kpiTodaySales", formatCurrency(d.todaySales));
        animateKpiText("kpiMonthlySales", formatCurrency(d.monthlySales));
        animateKpiText("kpiYearlySales", formatCurrency(d.yearlySales));
        animateKpiText("kpiPaidAmount", formatCurrency(d.totalPaidAmount));
        animateKpiText("kpiOutstanding", formatCurrency(d.totalPendingAmount));
        animateKpiText("kpiTotalPayments", d.totalPayments ?? 0);
        animateKpiText("kpiTotalProducts", d.totalProducts ?? 0);
        animateKpiText("kpiTotalShops", d.totalShops ?? 0);
        animateKpiText("kpiTotalSS", d.totalSuperStockists ?? 0);
        animateKpiText("kpiLowStock", d.lowStockProducts ?? 0);
        animateKpiText("kpiOutOfStock", d.outOfStockProducts ?? 0);
        animateKpiText("kpiTotalUsers", d.totalUsers ?? 0);
    } else {
        showToast("Dashboard", "Could not load KPI summary from the server.", "error");
    }

    loadSalesBreakdownFilterOptions();
    loadAndRenderSalesBreakdown();

    /* ---- Recent invoices + Pending payments (derived from the same live list) ---- */
    const recentBody = document.getElementById("recentInvoicesBody");
    const pendingList = document.getElementById("pendingPaymentsList");
    const recentActivityList = document.getElementById("recentActivityList");
    if (recentRes.status === "fulfilled"){
        const invoices = unwrap(recentRes.value, []);

        recentBody.innerHTML = invoices.slice(0, 6).map(inv => `<tr>
        <td class="fw-600">${escapeHtml(inv.invoiceNumber || "—")}</td>
        <td>${escapeHtml(inv.shopName || "—")}</td>
        <td>${formatCurrency(inv.totalAmount)}</td>
        <td>${statusBadge(inv.paymentStatus)}</td>
      </tr>`).join("") || `<tr><td colspan="4" class="text-center text-muted py-4">No invoices yet.</td></tr>`;

        const pendingInvoices = invoices.filter(inv => Number(inv.balanceAmount) > 0);
        pendingList.innerHTML = pendingInvoices.length ? pendingInvoices.map(inv => `
      <div class="mini-item">
        <div class="mini-item-icon bg-lavender"><i class="fa-solid fa-hourglass-half"></i></div>
        <div><div class="mini-item-title">${escapeHtml(inv.shopName || "—")}</div><div class="mini-item-sub">${escapeHtml(inv.invoiceNumber || "—")}</div></div>
        <div class="mini-item-value">${formatCurrency(inv.balanceAmount)}</div>
      </div>`).join("") : `<p class="text-center py-4">No pending payments.</p>`;

        // Recent Activity reuses this SAME already-fetched recent-invoices
        // response (no extra request) — the most natural, low-risk read of
        // "recent activity" next to the adjacent Recent Invoices widget.
        if (recentActivityList){
            const activity = [...invoices]
                .sort((a,b) => new Date(b.invoiceDate||0) - new Date(a.invoiceDate||0))
                .slice(0, 5);
            recentActivityList.innerHTML = activity.length ? activity.map(inv => `
          <div class="mini-list-item">
            <div>
                <strong>Invoice ${escapeHtml(inv.invoiceNumber || "—")} created</strong> — ${formatCurrency(inv.totalAmount)}
                <div class="text-muted small">${escapeHtml(inv.shopName || inv.distributorName || inv.superStockistName || "—")}</div>
            </div>
            <span class="text-muted small text-nowrap">${formatDate(inv.invoiceDate)}</span>
          </div>`).join("") : `<p class="text-center text-muted py-4">No recent activity yet.</p>`;
        }
    } else {
        recentBody.innerHTML = `<tr><td colspan="4" class="text-center text-muted py-4">Could not load recent invoices.</td></tr>`;
        pendingList.innerHTML = `<p class="text-center py-4">Could not load pending payments.</p>`;
        if (recentActivityList) recentActivityList.innerHTML = `<p class="text-center text-muted py-4">Could not load recent activity.</p>`;
    }

    /* ---- Low stock ---- */
    const lowStockList = document.getElementById("lowStockList");
    if (lowStockRes.status === "fulfilled"){
        const items = unwrap(lowStockRes.value, []);
        lowStockList.innerHTML = items.length ? items.map(p => `
      <div class="mini-item">
        <div class="mini-item-icon bg-rose"><i class="fa-solid fa-box-open"></i></div>
        <div><div class="mini-item-title">${escapeHtml(p.productName || "—")}</div><div class="mini-item-sub">${p.currentStock ?? 0} units left</div></div>
        <div class="mini-item-value text-danger">Low</div>
      </div>`).join("") : `<p class="text-center py-4">All products are well stocked.</p>`;
    } else {
        lowStockList.innerHTML = `<p class="text-center py-4">Could not load stock levels.</p>`;
    }

    /* ---- Top products ---- */
    const topProductsList = document.getElementById("topProductsList");
    if (topProductsRes.status === "fulfilled"){
        const items = unwrap(topProductsRes.value, []);
        topProductsList.innerHTML = items.length ? items.map(t => `
      <div class="mini-item">
        <div class="mini-item-icon bg-mint"><i class="fa-solid fa-star"></i></div>
        <div><div class="mini-item-title">${escapeHtml(t.productName || "—")}</div><div class="mini-item-sub">${t.quantitySold ?? 0} units sold</div></div>
      </div>`).join("") : `<p class="text-center py-4">No sales recorded yet.</p>`;
    } else {
        topProductsList.innerHTML = `<p class="text-center py-4">Could not load top products.</p>`;
    }

    /* ---- Charts ---- */
    if (salesRes.status === "fulfilled"){
        renderCharts(unwrap(salesRes.value, { monthlySales:[], categorySales:[] }));
    } else {
        showToast("Dashboard", "Could not load the sales charts from the server.", "error");
    }

}

function renderCharts(summary){
    const salesCtx = document.getElementById("salesChart");
    const catCtx = document.getElementById("categoryChart");

    const monthly = summary.monthlySales || [];
    const months = monthly.map(m => m.month);
    const salesData = monthly.map(m => Number(m.totalSales) || 0);

    if (salesChartInstance) salesChartInstance.destroy();
    salesChartInstance = new Chart(salesCtx, {
        type:"line",
        data:{ labels:months, datasets:[{
                label:"Sales", data:salesData, fill:true,
                backgroundColor:"rgba(156,203,255,0.22)", borderColor:"#74AFEF",
                tension:0.4, pointBackgroundColor:"#74AFEF", pointRadius:4, borderWidth:3
            }]},
        options:{ plugins:{ legend:{ display:false } },
            scales:{ y:{ grid:{ color:"rgba(17,24,39,0.05)" }, ticks:{ callback:v=>"₹"+(v/1000)+"k" } }, x:{ grid:{ display:false } } } }
    });

    const categories = summary.categorySales || [];
    if (categoryChartInstance) categoryChartInstance.destroy();
    categoryChartInstance = new Chart(catCtx, {
        type:"doughnut",
        data:{ labels: categories.map(c=>c.categoryName || "Uncategorized"), datasets:[{
                data: categories.map(c=>Number(c.totalSales)||0),
                backgroundColor:["#9CCBFF","#B8E0D2","#FFD8A8","#F8B4B4","#D9C9F5","#FFB3C6","#C9E4DE"], borderWidth:0
            }]},
        options:{ plugins:{ legend:{ position:"bottom", labels:{ boxWidth:10, font:{ size:11 } } } }, cutout:"68%" }
    });
}

document.getElementById("salesRangeSelect").addEventListener("change", renderDashboard);

/* ---- Sales Breakdown: District / Distributor / Super Stockist bar charts ---- */
let districtChartInstance, distributorChartInstance, superStockistChartInstance;
const breakdownFilters = { district: "", state: "", distributorId: "", superStockistId: "", month: "", year: "" };
const BREAKDOWN_BAR_COLORS = ["#74AFEF","#8FD3B6","#FFC98B","#F29AA0","#B7A6E8","#FF9FC0","#8CE0D0","#FFD98E"];

async function loadSalesBreakdownFilterOptions(){
    const distSel = document.getElementById("bdDistributor");
    const ssSel = document.getElementById("bdSuperStockist");
    const yearSel = document.getElementById("bdYear");
    // Rebuilt on every section load (not once ever): a distributor or
    // super stockist added or renamed after first load was previously
    // missing from these filters until a full browser reload.
    try{
        const list = unwrap(await apiRequest(ENDPOINTS.distributors), []);
        fillSelectPreserving("bdDistributor", `<option value="">All Distributors</option>`,
            list, d => d.id, d => d.distributorName);
    }catch(err){ /* leave whatever is already there */ }
    try{
        const list = unwrap(await apiRequest(ENDPOINTS.superStockists), []);
        fillSelectPreserving("bdSuperStockist", `<option value="">All Super Stockists</option>`,
            list, s => s.id, s => s.businessName || s.superStockistName || s.name);
    }catch(err){ /* leave whatever is already there */ }
    if (yearSel.options.length <= 1){
        const thisYear = new Date().getFullYear();
        let opts = `<option value="">All Years</option>`;
        for (let y = thisYear; y >= thisYear - 4; y--) opts += `<option value="${y}">${y}</option>`;
        yearSel.innerHTML = opts;
    }
}

function renderBreakdownChips(){
    const wrap = document.getElementById("breakdownActiveFilters");
    const chips = [];
    if (breakdownFilters.district) chips.push(["district", "District: " + breakdownFilters.district]);
    if (breakdownFilters.distributorId) chips.push(["distributorId", "Distributor: " + (document.querySelector(`#bdDistributor option[value="${breakdownFilters.distributorId}"]`)?.textContent || "")]);
    if (breakdownFilters.superStockistId) chips.push(["superStockistId", "Super Stockist: " + (document.querySelector(`#bdSuperStockist option[value="${breakdownFilters.superStockistId}"]`)?.textContent || "")]);
    wrap.innerHTML = chips.map(([key,label]) => `
        <span class="filter-chip">${escapeHtml(label)}<button data-clear="${key}">&times;</button></span>`).join("");
    wrap.querySelectorAll("[data-clear]").forEach(btn => {
        btn.addEventListener("click", () => {
            breakdownFilters[btn.dataset.clear] = "";
            if (btn.dataset.clear === "district") document.getElementById("bdDistrict").value = "";
            if (btn.dataset.clear === "distributorId") document.getElementById("bdDistributor").value = "";
            if (btn.dataset.clear === "superStockistId") document.getElementById("bdSuperStockist").value = "";
            loadAndRenderSalesBreakdown();
        });
    });
}

async function fetchGroupedSales(groupBy){
    const params = new URLSearchParams({ groupBy });
    if (breakdownFilters.district) params.set("district", breakdownFilters.district);
    if (breakdownFilters.state) params.set("state", breakdownFilters.state);
    if (breakdownFilters.distributorId) params.set("distributorId", breakdownFilters.distributorId);
    if (breakdownFilters.superStockistId) params.set("superStockistId", breakdownFilters.superStockistId);
    if (breakdownFilters.month) params.set("month", breakdownFilters.month);
    if (breakdownFilters.year) params.set("year", breakdownFilters.year);
    const res = await apiRequest(ENDPOINTS.dashboard + "/sales-grouped?" + params.toString());
    return unwrap(res, []);
}

function buildBreakdownBarChart(canvasId, existingInstance, rows, onBarClick){
    const ctx = document.getElementById(canvasId);
    if (existingInstance) existingInstance.destroy();
    return new Chart(ctx, {
        type: "bar",
        data: {
            labels: rows.map(r => r.label),
            datasets: [{
                data: rows.map(r => Number(r.totalSales) || 0),
                backgroundColor: rows.map((_, i) => BREAKDOWN_BAR_COLORS[i % BREAKDOWN_BAR_COLORS.length]),
                borderRadius: 6, maxBarThickness: 36,
            }],
        },
        options: {
            plugins: { legend: { display: false } },
            scales: { y: { grid: { color: "rgba(17,24,39,0.05)" }, ticks: { callback: v => "₹" + (v/1000) + "k" } }, x: { grid: { display: false } } },
            onClick: (evt, elements) => {
                if (!elements.length) return;
                const idx = elements[0].index;
                onBarClick(rows[idx]);
            },
            animation: { duration: 500, easing: "easeOutQuart" },
        },
    });
}

async function loadAndRenderSalesBreakdown(){
    renderBreakdownChips();
    try{
        const [districtRows, distributorRows, ssRows] = await Promise.all([
            fetchGroupedSales("district"),
            fetchGroupedSales("distributor"),
            fetchGroupedSales("superStockist"),
        ]);

        districtChartInstance = buildBreakdownBarChart("districtChart", districtChartInstance, districtRows, (row) => {
            breakdownFilters.district = breakdownFilters.district === row.label ? "" : row.label;
            document.getElementById("bdDistrict").value = breakdownFilters.district;
            loadAndRenderSalesBreakdown();
        });
        distributorChartInstance = buildBreakdownBarChart("distributorChart", distributorChartInstance, distributorRows, (row) => {
            breakdownFilters.distributorId = (row.groupId && String(breakdownFilters.distributorId) === String(row.groupId)) ? "" : row.groupId;
            document.getElementById("bdDistributor").value = breakdownFilters.distributorId || "";
            loadAndRenderSalesBreakdown();
        });
        superStockistChartInstance = buildBreakdownBarChart("superStockistChart", superStockistChartInstance, ssRows, (row) => {
            breakdownFilters.superStockistId = (row.groupId && String(breakdownFilters.superStockistId) === String(row.groupId)) ? "" : row.groupId;
            document.getElementById("bdSuperStockist").value = breakdownFilters.superStockistId || "";
            loadAndRenderSalesBreakdown();
        });
    }catch(err){
        showApiError(err, "Could not load sales breakdown");
    }
}

["bdDistrict","bdState"].forEach(id => {
    document.getElementById(id).addEventListener("change", (e) => {
        breakdownFilters[id === "bdDistrict" ? "district" : "state"] = e.target.value.trim();
        loadAndRenderSalesBreakdown();
    });
});
["bdDistributor","bdSuperStockist","bdMonth","bdYear"].forEach(id => {
    document.getElementById(id).addEventListener("change", (e) => {
        const key = { bdDistributor:"distributorId", bdSuperStockist:"superStockistId", bdMonth:"month", bdYear:"year" }[id];
        breakdownFilters[key] = e.target.value;
        loadAndRenderSalesBreakdown();
    });
});

/**
 * refreshDashboard — call after any CRUD mutation that could affect KPI
 * numbers, charts or widgets. Dashboard elements exist in the DOM even
 * while the section is hidden (d-none), so this keeps data fresh for the
 * moment the user next opens the Dashboard tab.
 */
function refreshDashboard(){
    // IMPORTANT: don't rebuild Chart.js instances while the Dashboard tab
    // itself is hidden (d-none, width 0). Chart.js reads the canvas's
    // parent width when it (re)initializes; if that width is 0 because the
    // section is display:none, it corrupts the canvas's internal backing
    // size, which then shows up as the chart zooming/growing/jittering the
    // next time the Dashboard tab is opened. Skipping here is safe because
    // showSection("dashboard") always calls renderDashboard() fresh the
    // moment the user actually opens the tab.
    const dashboardSection = document.getElementById("section-dashboard");
    if (dashboardSection && dashboardSection.classList.contains("d-none")) return;
    renderDashboard().catch(() => { /* individual widgets already surface their own errors */ });
}


/* ==================== 8. INVOICES ==================== */
let currentInvoiceItems = [];   // UI-only line items used to compute totals before save
let editingInvoicePreview = null;

/**
 * loadAndRenderInvoices — GET /invoices, cache in STATE, render list.
 * Also makes sure shops/distributors/products/categories are loaded so the
 * "Create Invoice" form's dropdowns and item builder have real data.
 */
async function loadAndRenderInvoices(){
    try{
        const [invRes] = await Promise.all([
            apiRequest(ENDPOINTS.invoices),
            STATE.shops.length ? Promise.resolve() : loadAndRenderCrud("shops"),
            STATE.distributors.length ? Promise.resolve() : loadDistributorsForCurrentRole(),
            STATE.products.length ? Promise.resolve() : loadAndRenderCrud("products"),
        ]);
        STATE.invoices = unwrap(invRes, []);

        fillSelectPreserving("invoicesDistributorFilter", `<option value="">All Distributors</option>`,
            STATE.distributors, d => d.id, d => d.distributorName);
    }catch(err){
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        document.getElementById("invoicesTableBody").innerHTML = `<tr><td colspan="6" class="text-center text-muted py-4">Could not load invoices from the server.</td></tr>`;
        showApiError(err, "Could not load invoices");
        return;
    }
    renderInvoicesList();
}

// GET /distributors (list-all) is admin-only. A Super Stockist login uses
// its own scoped endpoint instead — used anywhere a page needs
// STATE.distributors populated regardless of which role is viewing it
// (Invoices, Reports, etc).
async function loadDistributorsForCurrentRole(){
    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    if (role === "SUPER_STOCKIST"){
        try{
            const res = await apiRequest(ENDPOINTS.superStockists + "/me/distributors");
            STATE.distributors = unwrap(res, []);
        }catch(err){
            STATE.distributors = [];
        }
        return;
    }
    return loadAndRenderCrud("distributors");
}

function renderInvoicesList(){
    const term = (document.getElementById("invoicesSearch").value || "").toLowerCase();
    const statusFilter = document.getElementById("invoicesStatusFilter").value;
    const distributorFilter = document.getElementById("invoicesDistributorFilter").value;
    const dateFrom = document.getElementById("invoicesDateFrom").value;
    const dateTo = document.getElementById("invoicesDateTo").value;
    const rows = STATE.invoices.filter(inv => {
        const matchesTerm = !term || String(inv.invoiceNumber||"").toLowerCase().includes(term) || String(inv.shopName||"").toLowerCase().includes(term);
        const matchesStatus = !statusFilter || normalizeStatusLabel(inv.paymentStatus) === statusFilter;
        const matchesDistributor = !distributorFilter || String(inv.distributorId ?? "") === distributorFilter;
        const invDate = inv.invoiceDate ? String(inv.invoiceDate).slice(0,10) : null;
        const matchesFrom = !dateFrom || (invDate && invDate >= dateFrom);
        const matchesTo = !dateTo || (invDate && invDate <= dateTo);
        return matchesTerm && matchesStatus && matchesDistributor && matchesFrom && matchesTo;
    });

    document.getElementById("invoicesTableBody").innerHTML = rows.map(inv => `<tr>
      <td class="fw-600">${escapeHtml(inv.invoiceNumber)}</td><td>${escapeHtml(inv.shopName || "—")}</td><td>${formatDate(inv.invoiceDate)}</td>
      <td>${formatCurrency(inv.totalAmount)}</td><td>${statusBadge(inv.paymentStatus)}</td>
      <td class="text-end">
        <button class="action-btn view" data-action="view" data-id="${inv.id}" title="View"><i class="fa-solid fa-eye"></i></button>
        <button class="action-btn" data-action="download" data-id="${inv.id}" title="Download PDF"><i class="fa-solid fa-download"></i></button>
        <button class="action-btn delete" data-action="delete" data-id="${inv.id}" title="Delete"><i class="fa-solid fa-trash"></i></button>
      </td></tr>`).join("") || `<tr><td colspan="6" class="text-center text-muted py-4">No invoices found.</td></tr>`;

    document.getElementById("invoicesTableBody").querySelectorAll("[data-action]").forEach(btn => {
        btn.addEventListener("click", async () => {
            const inv = STATE.invoices.find(i => i.id == btn.dataset.id);
            if (!inv) return;
            if (btn.dataset.action === "view") openInvoicePreview(inv);
            else if (btn.dataset.action === "download"){
                // Reuse the same preview DOM the "view" button populates —
                // html2canvas needs the invoice actually rendered on screen
                // to capture it, so we open the preview then immediately
                // trigger the PDF save, no extra click needed from the user.
                // Bug fix: openInvoicePreview() is now async (it awaits the
                // company-settings fetch before filling in the DOM) -- it
                // must be awaited here too, otherwise downloadInvoicePdf()
                // used to fire before the preview had actually been
                // populated, capturing a blank/stale PDF.
                await openInvoicePreview(inv);
                downloadInvoicePdf(inv);
            }
            else showConfirm("Delete Invoice", "This invoice will be permanently removed.", async () => {
                showSpinner();
                try{
                    await apiRequest(ENDPOINTS.invoices + "/" + inv.id, { method:"DELETE" });
                    showToast("Deleted", "Invoice removed successfully.", "success");
                    await loadAndRenderInvoices();
                    refreshDashboard();
                }catch(err){
                    showApiError(err, "Delete failed");
                }finally{
                    hideSpinner();
                }
            });
        });
    });
}
document.getElementById("invoicesSearch").addEventListener("input", renderInvoicesList);
document.getElementById("invoicesStatusFilter").addEventListener("change", renderInvoicesList);
document.getElementById("invoicesDistributorFilter").addEventListener("change", renderInvoicesList);
document.getElementById("invoicesDateFrom").addEventListener("change", renderInvoicesList);
document.getElementById("invoicesDateTo").addEventListener("change", renderInvoicesList);

function showInvoiceView(view){
    ["invoiceListView","invoiceFormView","invoicePreviewView"].forEach(v => document.getElementById(v).classList.add("d-none"));
    document.getElementById(view).classList.remove("d-none");
    const isPreview = view === "invoicePreviewView";
    document.getElementById("createInvoiceBtn").classList.toggle("d-none", isPreview || view === "invoiceFormView");
    document.getElementById("backToInvoicesBtn").classList.toggle("d-none", !isPreview);
    document.getElementById("printInvoiceBtn").classList.toggle("d-none", !isPreview);
    document.getElementById("downloadInvoiceBtn").classList.toggle("d-none", !isPreview);
}

document.getElementById("createInvoiceBtn").addEventListener("click", async () => {
    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";
    const isSuperStockist = role === "SUPER_STOCKIST";

    if (!STATE.products.length){
        showToast("Missing setup", "You need at least one product before creating an invoice.", "warning");
        return;
    }
    if (!isSuperStockist && (!STATE.shops.length || (isAdminRole && !STATE.distributors.length))){
        showToast("Missing setup", "You need at least one shop and one product before creating an invoice.", "warning");
        return;
    }
    currentInvoiceItems = [];

    // Invoice Type selector: a plain Distributor only ever does
    // Distributor -> Shop, so hide the choice entirely for them. Admin sees
    // all 3 levels; a Super Stockist can only issue Super Stockist -> Distributor.
    const levelSelect = document.getElementById("invLevel");
    const levelWrap = document.getElementById("invLevelWrap");
    if (isAdminRole){
        levelWrap.classList.remove("d-none");
        levelSelect.innerHTML = `
            <option value="DISTRIBUTOR_TO_SHOP">Distributor → Shop</option>
            <option value="SUPER_STOCKIST_TO_DISTRIBUTOR">Super Stockist → Distributor</option>
            <option value="COMPANY_TO_SUPER_STOCKIST">Company → Super Stockist</option>`;
        levelSelect.value = "DISTRIBUTOR_TO_SHOP";
        levelSelect.disabled = false;
    } else if (isSuperStockist){
        levelWrap.classList.remove("d-none");
        levelSelect.innerHTML = `<option value="SUPER_STOCKIST_TO_DISTRIBUTOR">Super Stockist → Distributor</option>`;
        levelSelect.value = "SUPER_STOCKIST_TO_DISTRIBUTOR";
        levelSelect.disabled = true;
    } else {
        levelWrap.classList.add("d-none");
        levelSelect.innerHTML = `<option value="DISTRIBUTOR_TO_SHOP">Distributor → Shop</option>`;
        levelSelect.value = "DISTRIBUTOR_TO_SHOP";
    }

    // Shop dropdown must only ever show shops belonging to the currently
    // selected distributor (never the full company-wide shop list). It's
    // populated by applyInvoiceLevelFields() below once the distributor
    // dropdown itself has been populated, and re-populated live via the
    // "change" listener whenever the distributor selection changes.

    // Spec: a distributor must never pick a distributor from a dropdown —
    // the logged-in distributor is auto-assigned. Their id/name come from
    // the login response (STATE.distributors is admin-only, not loaded here).
    const invDistributorEl = document.getElementById("invDistributor");
    if (isAdminRole){
        invDistributorEl.disabled = false;
        invDistributorEl.innerHTML = STATE.distributors.map(d=>`<option value="${d.id}">${escapeHtml(d.distributorName)}</option>`).join("");
    } else if (!isSuperStockist){
        invDistributorEl.disabled = true;
        invDistributorEl.innerHTML = `<option value="${user.distributorId}" selected>${escapeHtml(user.distributorName || "My Distributor Account")}</option>`;
    }

    // Super Stockist selector: Admin picks any Super Stockist; a Super
    // Stockist login is auto-locked to themselves.
    const invSsEl = document.getElementById("invSuperStockist");
    if (isSuperStockist){
        invSsEl.disabled = true;
        invSsEl.innerHTML = `<option value="${user.superStockistId}" selected>${escapeHtml(user.superStockistName || "My Super Stockist Account")}</option>`;
    } else if (isAdminRole){
        invSsEl.disabled = false;
        try{
            const res = await apiRequest(ENDPOINTS.superStockists);
            const list = unwrap(res, []);
            invSsEl.innerHTML = list.map(s=>`<option value="${s.id}">${escapeHtml(s.businessName || s.superStockistName || s.name)}</option>`).join("");
        }catch(err){
            invSsEl.innerHTML = "";
        }
    }

    document.getElementById("invDate").value = new Date().toISOString().slice(0,10);
    document.getElementById("invDiscount").value = 0;
    applyInvoiceLevelFields();
    renderInvoiceItemsTable();
    showInvoiceView("invoiceFormView");
});

// Toggles which of Shop / Distributor / Super Stockist fields apply for the
// selected invoice level, and (for admin) refilters the distributor list to
// only those belonging to the chosen Super Stockist on the SS->Distributor leg.
function applyInvoiceLevelFields(){
    const level = document.getElementById("invLevel").value;
    const shopWrap = document.getElementById("invShopWrap");
    const distWrap = document.getElementById("invDistributorWrap");
    const ssWrap = document.getElementById("invSuperStockistWrap");

    if (level === "COMPANY_TO_SUPER_STOCKIST"){
        shopWrap.classList.add("d-none");
        distWrap.classList.add("d-none");
        ssWrap.classList.remove("d-none");
    } else if (level === "SUPER_STOCKIST_TO_DISTRIBUTOR"){
        shopWrap.classList.add("d-none");
        distWrap.classList.remove("d-none");
        ssWrap.classList.remove("d-none");
        refilterDistributorsForSelectedSuperStockist();
    } else {
        shopWrap.classList.remove("d-none");
        distWrap.classList.remove("d-none");
        ssWrap.classList.add("d-none");
        refilterShopsForSelectedDistributor();
    }

    if (typeof currentInvoiceItems !== "undefined" && currentInvoiceItems.length){
        currentInvoiceItems.forEach(item => {
            const product = STATE.products.find(p => p.id == item.productId);
            if (product) item.price = tierPriceForCurrentLevel(product);
        });
        renderInvoiceItemsTable();
        recalcInvoiceTotals();
    }
}

async function refilterDistributorsForSelectedSuperStockist(){
    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";
    const invDistributorEl = document.getElementById("invDistributor");
    const ssId = document.getElementById("invSuperStockist").value;
    if (!ssId) return;
    invDistributorEl.disabled = false;
    invDistributorEl.innerHTML = `<option value="">Loading...</option>`;
    try{
        const res = isAdminRole
            ? await apiRequest(ENDPOINTS.superStockists + "/" + ssId + "/distributors")
            : await apiRequest(ENDPOINTS.superStockists + "/me/distributors");
        const list = unwrap(res, []);
        invDistributorEl.innerHTML = list.map(d=>`<option value="${d.id}">${escapeHtml(d.distributorName)}</option>`).join("") || `<option value="">No distributors assigned</option>`;
    }catch(err){
        invDistributorEl.innerHTML = `<option value="">Could not load distributors</option>`;
    }
}

async function refilterShopsForSelectedDistributor(){
    const invShopEl = document.getElementById("invShop");
    const distributorId = document.getElementById("invDistributor").value;
    if (!distributorId){
        invShopEl.innerHTML = `<option value="">Select a distributor first</option>`;
        return;
    }
    invShopEl.innerHTML = `<option value="">Loading...</option>`;
    try{
        const res = await apiRequest(ENDPOINTS.shops + "/distributor/" + distributorId);
        const list = unwrap(res, []);
        invShopEl.innerHTML = list.map(s=>`<option value="${s.id}">${escapeHtml(s.shopName)}</option>`).join("")
            || `<option value="">No shops for this distributor</option>`;
    }catch(err){
        invShopEl.innerHTML = `<option value="">Could not load shops</option>`;
    }
}

document.getElementById("invLevel").addEventListener("change", applyInvoiceLevelFields);
document.getElementById("invDistributor").addEventListener("change", () => {
    if (document.getElementById("invLevel").value === "DISTRIBUTOR_TO_SHOP"){
        refilterShopsForSelectedDistributor();
    }
});
document.getElementById("invSuperStockist").addEventListener("change", () => {
    if (document.getElementById("invLevel").value === "SUPER_STOCKIST_TO_DISTRIBUTOR"){
        refilterDistributorsForSelectedSuperStockist();
    }
});
document.getElementById("cancelInvoiceBtn").addEventListener("click", () => showInvoiceView("invoiceListView"));

function tierPriceForCurrentLevel(product){
    if (!product) return 0;
    const level = document.getElementById("invLevel").value;
    if (level === "COMPANY_TO_SUPER_STOCKIST") return Number(product.ssPrice) || 0;
    if (level === "SUPER_STOCKIST_TO_DISTRIBUTOR") return Number(product.distributorPrice) || 0;
    return Number(product.sellingPrice) || 0; // DISTRIBUTOR_TO_SHOP and default
}

function renderInvoiceItemsTable(){
    const body = document.getElementById("invoiceItemsBody");
    body.innerHTML = currentInvoiceItems.map((item, idx) => {
        const discPct = item.discPct || 0;
        const lineBase = item.qty * item.price * (1 - discPct/100);
        const total = lineBase * (1 + item.tax/100);
        return `<tr>
      <td>
        <select class="form-select form-select-sm" data-idx="${idx}" data-field="productId">
          ${STATE.products.map(p=>`<option value="${p.id}" ${p.id==item.productId?"selected":""}>${escapeHtml(p.productName)}</option>`).join("")}
        </select>
      </td>
      <td><input type="number" min="1" class="form-control form-control-sm" data-idx="${idx}" data-field="qty" value="${item.qty}"></td>
      <td><input type="number" min="0" class="form-control form-control-sm" data-idx="${idx}" data-field="shippedQty" value="${item.shippedQty ?? item.qty}" title="Quantity actually dispatched — informational, printed on the invoice, doesn't affect pricing"></td>
      <td><input type="number" min="0" class="form-control form-control-sm" data-idx="${idx}" data-field="price" value="${item.price}"></td>
      <td><input type="number" min="0" max="100" step="0.01" class="form-control form-control-sm" data-idx="${idx}" data-field="discPct" value="${discPct}"></td>
      <td><input type="number" min="0" class="form-control form-control-sm" data-idx="${idx}" data-field="tax" value="${item.tax}"></td>
      <td class="fw-600">${formatCurrency(total)}</td>
      <td><button class="action-btn delete" data-remove="${idx}"><i class="fa-solid fa-xmark"></i></button></td>
    </tr>`;
    }).join("") || `<tr><td colspan="8" class="text-center text-muted py-3">No items added yet.</td></tr>`;

    body.querySelectorAll("[data-field]").forEach(el => {
        el.addEventListener("change", () => {
            const idx = Number(el.dataset.idx);
            const field = el.dataset.field;
            if (field === "productId"){
                const product = STATE.products.find(p=>p.id==el.value);
                currentInvoiceItems[idx].productId = Number(el.value);
                currentInvoiceItems[idx].price = tierPriceForCurrentLevel(product);
                currentInvoiceItems[idx].tax = Number(product.gstPercentage) || 0;
            } else {
                currentInvoiceItems[idx][field] = Number(el.value);
            }
            renderInvoiceItemsTable();
            recalcInvoiceTotals();
        });
    });
    body.querySelectorAll("[data-remove]").forEach(btn => {
        btn.addEventListener("click", () => {
            currentInvoiceItems.splice(Number(btn.dataset.remove), 1);
            renderInvoiceItemsTable();
            recalcInvoiceTotals();
        });
    });
    recalcInvoiceTotals();
}

document.getElementById("addInvoiceItemBtn").addEventListener("click", () => {
    if (!STATE.products.length) return;
    // Bug fix: this used to always push STATE.products[0], so every
    // "Add Item" click after the first added ANOTHER row for whichever
    // product was already first in the list instead of a fresh one —
    // pick the first product not already on the invoice, falling back
    // to the first product overall only once every product is in use.
    const usedIds = new Set(currentInvoiceItems.map(i => i.productId));
    const nextProduct = STATE.products.find(p => !usedIds.has(p.id)) || STATE.products[0];
    currentInvoiceItems.push({ productId: nextProduct.id, qty:1, shippedQty:1, discPct:0, price: tierPriceForCurrentLevel(nextProduct), tax: Number(nextProduct.gstPercentage)||0 });
    renderInvoiceItemsTable();
});
// "Bulk Discount (%)" — a convenience that stamps this % onto every
// current line's own Disc % (which is what's actually saved/printed).
// It does NOT recompute a separate header-level discount on its own.
document.getElementById("invDiscount").addEventListener("input", () => {
    const pct = Number(document.getElementById("invDiscount").value || 0);
    currentInvoiceItems.forEach(i => i.discPct = pct);
    renderInvoiceItemsTable();
});

function computeInvoiceTotals(){
    const sub = currentInvoiceItems.reduce((s,i)=>s+i.qty*i.price,0);
    const discountAmt = currentInvoiceItems.reduce((s,i)=>s+(i.qty*i.price*(i.discPct||0)/100),0);
    // GST is charged on the post-discount line value, matching how the
    // backend (InvoiceService.saveInvoiceItems) actually computes it.
    const gst = currentInvoiceItems.reduce((s,i)=>{
        const lineBase = i.qty*i.price - (i.qty*i.price*(i.discPct||0)/100);
        return s + (lineBase * i.tax/100);
    },0);
    const grand = sub - discountAmt + gst;
    return { sub, gst, discountAmt, grand };
}

function recalcInvoiceTotals(){
    const t = computeInvoiceTotals();
    document.getElementById("invSubtotal").textContent = formatCurrency(t.sub);
    document.getElementById("invGst").textContent = formatCurrency(t.gst);
    document.getElementById("invGrandTotal").textContent = formatCurrency(t.grand);
}

/** nextInvoiceNumber — derives the next INV-#### from the highest number
 * currently loaded from the backend (not a local counter). */
function nextInvoiceNumber(){
    let max = 1042;
    STATE.invoices.forEach(inv => {
        const m = /INV-(\d+)/.exec(inv.invoiceNumber || "");
        if (m) max = Math.max(max, Number(m[1]));
    });
    return "INV-" + (max + 1);
}

document.getElementById("saveInvoiceBtn").addEventListener("click", async () => {
    if (!currentInvoiceItems.length){ showToast("No items", "Add at least one invoice item.", "error"); return; }

    // Bug fix: a line item whose price never resolved (no tier price set
    // for that product) used to save silently as a ₹0 line — the backend
    // now rejects this too, but catching it here gives an immediate,
    // specific message instead of a round trip to find out.
    const zeroPriceItem = currentInvoiceItems.find(i => !(Number(i.price) > 0));
    if (zeroPriceItem) {
        const product = STATE.products.find(p => p.id === zeroPriceItem.productId);
        const name = product ? product.productName : "this product";
        showToast("Missing price", `"${name}" has no price set for this invoice level — set it in Products before invoicing.`, "error");
        return;
    }

    const totals = computeInvoiceTotals();
    const paymentStatus = document.getElementById("invPaymentStatus").value;
    const invoiceDate = document.getElementById("invDate").value;

    // Defensive: derive the level from the CURRENT role rather than trusting
    // whatever the dropdown happens to hold. A plain Distributor can only
    // ever create DISTRIBUTOR_TO_SHOP and a Super Stockist can only ever
    // create SUPER_STOCKIST_TO_DISTRIBUTOR -- Admin is the only role that
    // actually gets to pick from the dropdown.
    const saveUser = getCurrentUser();
    const saveRole = String((saveUser && saveUser.role) || "").toUpperCase().replace("ROLE_", "");
    const saveIsAdmin = saveRole === "ADMIN" || saveRole === "SUPER_ADMIN";
    const saveIsSuperStockist = saveRole === "SUPER_STOCKIST";
    let level;
    if (saveIsAdmin){
        level = document.getElementById("invLevel").value;
    } else if (saveIsSuperStockist){
        level = "SUPER_STOCKIST_TO_DISTRIBUTOR";
    } else {
        level = "DISTRIBUTOR_TO_SHOP";
    }

    // Real backend shape (InvoiceRequest). Note: the backend has no
    // endpoint/table to persist individual line items — only header-level
    // totals are stored in MySQL, so only these fields are sent.
    //
    // Each item now carries its own Disc % (and Shipped Qty) set directly
    // in the item row above — that's what's actually saved, not a single
    // header-level % spread evenly. "Bulk Discount (%)" is just a
    // convenience that stamped this value onto every row when typed.
    const apiPayload = {
        invoiceLevel: level,
        invoiceDate,
        subTotal: totals.sub,
        discountAmount: totals.discountAmt,
        taxAmount: totals.gst,
        totalAmount: totals.grand,
        paidAmount: paymentStatus === "PAID" ? totals.grand : 0,
        balanceAmount: paymentStatus === "PAID" ? 0 : totals.grand,
        paymentStatus,
        remarks: document.getElementById("invPoRefNo").value ? ("PO Ref: " + document.getElementById("invPoRefNo").value) : null,
        items: currentInvoiceItems.map(it => ({
            productId: it.productId,
            quantity: it.qty,
            shippedQuantity: it.shippedQty != null ? it.shippedQty : it.qty,
            unitPrice: it.price,
            discountAmount: Number(((it.qty * it.price) * (it.discPct||0) / 100).toFixed(2)),
            gstPercentage: it.tax,
        })),
    };

    if (level === "COMPANY_TO_SUPER_STOCKIST"){
        const superStockistId = Number(document.getElementById("invSuperStockist").value);
        if (!superStockistId){ showToast("Missing Super Stockist", "Pick a Super Stockist to bill.", "warning"); return; }
        apiPayload.superStockistId = superStockistId;
    } else if (level === "SUPER_STOCKIST_TO_DISTRIBUTOR"){
        const superStockistId = Number(document.getElementById("invSuperStockist").value);
        const distributorId = Number(document.getElementById("invDistributor").value);
        if (!superStockistId || !distributorId){ showToast("Missing selection", "Pick both a Super Stockist and a distributor.", "warning"); return; }
        apiPayload.superStockistId = superStockistId;
        apiPayload.distributorId = distributorId;
    } else {
        const shopId = Number(document.getElementById("invShop").value);
        const distributorId = Number(document.getElementById("invDistributor").value);
        if (!shopId || !distributorId){ showToast("Missing selection", "Pick both a distributor and a shop.", "warning"); return; }
        apiPayload.shopId = shopId;
        apiPayload.distributorId = distributorId;
    }

    const saveBtn = document.getElementById("saveInvoiceBtn");
    saveBtn.disabled = true;
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.invoices, { method:"POST", body: apiPayload });
        const savedNumber = (res && res.data && res.data.invoiceNumber) || "Invoice";
        showToast("Invoice created", `${savedNumber} has been saved.`, "success");
        showInvoiceView("invoiceListView");
        await loadAndRenderInvoices();
        refreshDashboard();
    }catch(err){
        showApiError(err, "Invoice save failed");
    }finally{
        saveBtn.disabled = false;
        hideSpinner();
    }
});

// Converts a rupee amount into words, Indian numbering style (lakh/crore),
// e.g. 30180 -> "Thirty Thousand One Hundred Eighty".
function numberToWordsIndian(num){
    const ones = ["","One","Two","Three","Four","Five","Six","Seven","Eight","Nine","Ten",
        "Eleven","Twelve","Thirteen","Fourteen","Fifteen","Sixteen","Seventeen","Eighteen","Nineteen"];
    const tens = ["","","Twenty","Thirty","Forty","Fifty","Sixty","Seventy","Eighty","Ninety"];

    function twoDigits(n){
        if (n < 20) return ones[n];
        return (tens[Math.floor(n/10)] + (n%10 ? " " + ones[n%10] : "")).trim();
    }
    function threeDigits(n){
        if (n < 100) return twoDigits(n);
        return ones[Math.floor(n/100)] + " Hundred" + (n%100 ? " " + twoDigits(n%100) : "");
    }

    let n = Math.floor(Math.abs(Number(num) || 0));
    if (n === 0) return "Zero";

    const crore = Math.floor(n / 10000000); n %= 10000000;
    const lakh = Math.floor(n / 100000); n %= 100000;
    const thousand = Math.floor(n / 1000); n %= 1000;
    const hundred = n;

    const parts = [];
    if (crore) parts.push(threeDigits(crore) + " Crore");
    if (lakh) parts.push(threeDigits(lakh) + " Lakh");
    if (thousand) parts.push(threeDigits(thousand) + " Thousand");
    if (hundred) parts.push(threeDigits(hundred));
    return parts.join(" ").trim();
}

function getCompanySettings(){
    try{ return JSON.parse(localStorage.getItem(COMPANY_SETTINGS_KEY) || "{}"); }catch(e){ return {}; }
}

async function openInvoicePreview(inv){
    editingInvoicePreview = inv;
    // Bug fix: company.name/address/phone/email used to come ONLY from
    // getCompanySettings() (localStorage) -- but loadCompanySettingsIntoForm()
    // and the companyForm submit handler above both moved these specific
    // fields server-side a while back (see ENDPOINTS.companySettings), so
    // localStorage's copy of them was permanently empty from then on. That's
    // why the invoice always printed "--" for address/phone/email even
    // though Settings showed the real saved values (Settings reads them
    // straight from the backend) -- website/bank details/logo/terms are
    // still genuinely localStorage-only, so those kept working fine. Fetch
    // the real record here too and merge it over the localStorage object so
    // the invoice prints exactly what Settings shows.
    const company = getCompanySettings();
    try{
        const res = await apiRequest(ENDPOINTS.companySettings);
        const data = res && res.data;
        if (data){
            company.name = data.companyName || company.name;
            company.email = data.email || company.email;
            company.phone = data.phone || company.phone;
            company.address = [data.address, data.city, data.state, data.pincode].filter(Boolean).join(", ") || company.address;
        }
    }catch(e){
        // No company-settings record yet (or offline) -- fall back to
        // whatever localStorage had rather than blocking the preview.
    }
    const shop = STATE.shops.find(s => s.id === inv.shopId);
    const currentUser = getCurrentUser() || {};

    // ---- Header: company + invoice meta ----
    document.getElementById("pvCompanyName").textContent = company.name || "Brisk Traders";
    // companyLogoPreview lives in the ADMIN-only settings pane, which is
    // removed from the DOM for SS/DP -- fall back to the stored logo or a
    // default so invoice preview/PDF keeps working for every role.
    const logoEl = document.getElementById("companyLogoPreview");
    document.getElementById("pvCompanyLogo").src = company.logo
        || (logoEl ? logoEl.src : "https://api.dicebear.com/7.x/shapes/svg?seed=Brisk");
    document.getElementById("pvCompanyAddress").innerHTML = `<i class="fa-solid fa-location-dot me-1"></i>${escapeHtml(company.address || "--")}`;
    document.getElementById("pvCompanyPhone").innerHTML = `<i class="fa-solid fa-phone me-1"></i>${escapeHtml(company.phone || "--")}`;
    document.getElementById("pvCompanyEmail").innerHTML = `<i class="fa-solid fa-envelope me-1"></i>${escapeHtml(company.email || "--")}`;
    document.getElementById("pvCompanyWebsite").innerHTML = `<i class="fa-solid fa-globe me-1"></i>${escapeHtml(company.website || "--")}`;
    document.getElementById("pvFooterPhone").textContent = company.phone || "--";
    document.getElementById("pvFooterEmail").textContent = company.email || "--";
    document.getElementById("pvFooterWebsite").textContent = company.website || "--";

    document.getElementById("pvInvoiceNo").textContent = inv.invoiceNumber;
    document.getElementById("pvInvoiceDate").textContent = formatDate(inv.invoiceDate);

    // Due Date isn't a stored field on the invoice -- computed from the
    // invoice date + your Settings -> "Default Payment Terms (days)".
    const termsDays = Number(company.paymentTermsDays) || 10;
    const invDateObj = inv.invoiceDate ? new Date(inv.invoiceDate) : new Date();
    const dueDateObj = new Date(invDateObj.getTime() + termsDays * 24 * 60 * 60 * 1000);
    document.getElementById("pvDueDate").textContent = formatDate(dueDateObj.toISOString().slice(0,10));
    document.getElementById("pvPaymentTerms").textContent = termsDays + " Days";

    // Invoice Type isn't stored either -- derived from whether anything is
    // still owed (real data: balanceAmount), not fabricated.
    document.getElementById("pvInvoiceType").textContent = Number(inv.balanceAmount) > 0 ? "Credit" : "Cash";

    // ---- Bill To / Ship To (same shop -- there's no separate ship-to
    // address concept in the system, so both cards show the same billing
    // party) ----
    const billName = inv.shopName || inv.distributorName || inv.superStockistName || "--";
    const billAddress = shop ? [shop.address, shop.city, shop.state, shop.pincode].filter(Boolean).join(", ") : "--";
    const billContact = (shop && shop.ownerName) || "--";
    const billPhone = (shop && shop.mobileNumber) || "--";
    document.getElementById("pvShopName").textContent = billName;
    document.getElementById("pvShopAddress").textContent = billAddress;
    document.getElementById("pvShopContact").textContent = billContact;
    document.getElementById("pvShopPhone").textContent = billPhone;
    document.getElementById("pvShipShopName").textContent = billName;
    document.getElementById("pvShipShopAddress").textContent = billAddress;
    document.getElementById("pvShipShopContact").textContent = billContact;
    document.getElementById("pvShipShopPhone").textContent = billPhone;

    document.getElementById("pvDistributor").textContent = inv.distributorName || "--";
    // Shop Code / Distributor Code aren't separate stored fields -- derived
    // from the real record id (e.g. shop id 1 -> SHOP1001), same idea your
    // sample used, not invented data.
    document.getElementById("pvShopCode").textContent = inv.shopId ? ("SHOP" + (1000 + Number(inv.shopId))) : "--";
    document.getElementById("pvPoRefNo").textContent = (inv.remarks && inv.remarks.startsWith("PO Ref: "))
        ? inv.remarks.replace("PO Ref: ", "") : "--";
    document.getElementById("pvSalesExec").textContent = currentUser.fullName || currentUser.username || "--";
    document.getElementById("pvPlaceOfSupply").textContent = (shop && shop.state) || "--";

    // ---- Item table ----
    // MRP and HSN/SAC now come from the real product record (Product.mrp /
    // Product.hsnSacCode, surfaced on each InvoiceItemResponse) instead of
    // "--" placeholders. There's no separate "goods shipped vs goods
    // billed" concept in this system (one quantity per line, no partial
    // shipment tracking) — Qty Shipped and Qty Billed both show that same
    // quantity, matching the paper invoice layout without inventing data.
    const items = inv.items || [];
    if (items.length){
        document.getElementById("pvItemsBody").innerHTML = items.map((it, idx) => {
            const qty = Number(it.quantity) || 0;
            const unitPrice = Number(it.unitPrice) || 0;
            const discountAmt = Number(it.discountAmount) || 0;
            const lineValue = qty * unitPrice;
            // Rate/Amount printed here are post-discount, pre-GST (GST is
            // shown once as CGST+SGST at the bottom) — same convention as
            // the paper invoice this mirrors.
            const amount = lineValue - discountAmt;
            const rate = qty > 0 ? amount / qty : unitPrice;
            const discPct = lineValue > 0 ? (discountAmt / lineValue) * 100 : 0;
            const shippedQty = it.shippedQuantity != null ? it.shippedQuantity : qty;
            return `<tr>
            <td>${idx+1}</td>
            <td>${escapeHtml(it.productName || "--")}</td>
            <td>${escapeHtml(it.hsnSacCode || "--")}</td>
            <td>${it.mrp != null ? formatCurrency(it.mrp) : "--"}</td>
            <td>${shippedQty} ${escapeHtml(it.unit || "")}</td>
            <td>${qty} ${escapeHtml(it.unit || "")}</td>
            <td>${formatCurrency(rate)}</td>
            <td>${discPct > 0 ? discPct.toFixed(2) + "%" : "0.00%"}</td>
            <td>${formatCurrency(amount)}</td>
        </tr>`;
        }).join("");
    } else {
        // Older invoices saved before line items were persisted -- only
        // header totals exist in the database for these, so show exactly
        // that instead of fabricating a fake item row.
        document.getElementById("pvItemsBody").innerHTML = `<tr><td colspan="9" class="text-center text-muted py-3">
          Line-item detail wasn't saved for this invoice — totals below are the figures saved in MySQL.
        </td></tr>`;
    }

    // ---- Totals (CGST/SGST is a straight half-split of the single GST%
    // the system stores per item -- the standard intra-state convention --
    // not a separately tracked figure). Grand Total is rounded to the
    // nearest rupee (matching how printed tax invoices are settled), with
    // the rounding adjustment shown on its own "Round Off" line so the
    // figures always foot correctly — Add (+) when rounding up, Less (-)
    // when rounding down, same convention as the paper invoice. ----
    const taxable = Number(inv.subTotal || 0) - Number(inv.discountAmount || 0);
    const halfTax = Number(inv.taxAmount || 0) / 2;
    const rawTotal = Number(inv.totalAmount || 0);
    const roundedTotal = Math.round(rawTotal);
    const roundOff = roundedTotal - rawTotal;
    document.getElementById("pvSubtotal").textContent = formatCurrency(inv.subTotal);
    document.getElementById("pvDiscount").textContent = formatCurrency(inv.discountAmount);
    document.getElementById("pvTaxable").textContent = formatCurrency(taxable);
    document.getElementById("pvCgst").textContent = formatCurrency(halfTax);
    document.getElementById("pvSgst").textContent = formatCurrency(halfTax);
    document.getElementById("pvRoundOffLabel").textContent = roundOff < 0 ? "Less: Round Off" : "Add: Round Off";
    document.getElementById("pvRoundOff").textContent = (roundOff < 0 ? "(-) " : "(+) ") + formatCurrency(Math.abs(roundOff));
    document.getElementById("pvGrandTotal").textContent = formatCurrency(roundedTotal);
    document.getElementById("pvPaidAmount").textContent = formatCurrency(inv.paidAmount);
    document.getElementById("pvBalanceAmount").textContent = formatCurrency(inv.balanceAmount);
    document.getElementById("pvAmountWords").textContent =
        "Rs. " + numberToWordsIndian(roundedTotal) + " Only";

    // ---- Bank details + Scan & Pay ----
    const bankLines = [];
    if (company.bankName) bankLines.push("Bank Name: " + company.bankName);
    bankLines.push("Account Name: " + (company.name || "Brisk Traders"));
    if (company.bankAccount) bankLines.push("Account No: " + company.bankAccount);
    if (company.bankIfsc) bankLines.push("IFSC Code: " + company.bankIfsc);
    if (company.bankBranch) bankLines.push("Branch: " + company.bankBranch);
    document.getElementById("pvBankDetails").innerHTML = bankLines.map(escapeHtml).join("<br>");

    document.getElementById("pvUpiId").textContent = company.upiId || "Set in Settings";
    if (company.upiId){
        const upiUrl = `upi://pay?pa=${encodeURIComponent(company.upiId)}&pn=${encodeURIComponent(company.name || "Merchant")}&am=${encodeURIComponent(inv.totalAmount || 0)}&cu=INR`;
        document.getElementById("pvQrCode").src = `https://api.qrserver.com/v1/create-qr-code/?size=110x110&data=${encodeURIComponent(upiUrl)}`;
        document.getElementById("pvQrCode").classList.remove("d-none");
    } else {
        document.getElementById("pvQrCode").src = "";
        document.getElementById("pvQrCode").classList.add("d-none");
    }

    showInvoiceView("invoicePreviewView");
}
document.getElementById("backToInvoicesBtn").addEventListener("click", () => showInvoiceView("invoiceListView"));
document.getElementById("printInvoiceBtn").addEventListener("click", () => window.print());

function downloadInvoicePdf(inv){
    const filename = `Invoice_${inv.invoiceNumber}.pdf`;
    showSpinner();
    html2pdf().set({
        margin: 10,
        filename,
        image: { type: "jpeg", quality: 0.98 },
        html2canvas: { scale: 2, useCORS: true, backgroundColor: "#ffffff" },
        jsPDF: { unit: "mm", format: "a4", orientation: "portrait" },
        pagebreak: { mode: ["avoid-all", "css", "legacy"] }
    }).from(document.getElementById("invoicePrintArea")).save()
        .then(() => { hideSpinner(); showToast("Downloaded", `${filename} generated successfully.`, "success"); })
        .catch(() => { hideSpinner(); showToast("PDF failed", "Could not generate the invoice PDF.", "error"); });
}

document.getElementById("downloadInvoiceBtn").addEventListener("click", () => {
    if (!editingInvoicePreview){ showToast("No invoice", "Open an invoice to download.", "error"); return; }
    downloadInvoicePdf(editingInvoicePreview);
});

/* ==================== 9. PAYMENTS ==================== */

async function loadAndRenderPayments(){
    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";

    // dashboard/payment-summary lives under DashboardController, which is
    // ADMIN-only. It used to be bundled into the same Promise.all as the
    // payments list itself — one 403 there killed the whole page (fail-fast),
    // even though /payments loads fine standalone for a distributor login.
    // Promise.allSettled + a role-gated summary call fixes both problems.
    const [payRes, summaryRes] = await Promise.allSettled([
        apiRequest(ENDPOINTS.payments),
        isAdminRole ? apiRequest(ENDPOINTS.dashboardPaymentSummary) : Promise.resolve({ data: {} }),
        STATE.shops.length ? Promise.resolve() : loadAndRenderCrud("shops"),
        STATE.invoices.length ? Promise.resolve() : loadAndRenderInvoices(),
    ]);

    if (payRes.status === "rejected"){
        const err = payRes.reason;
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        document.getElementById("paymentsTableBody").innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4">Could not load payments from the server.</td></tr>`;
        showApiError(err, "Could not load payments");
        return;
    }
    STATE.payments = unwrap(payRes.value, []);
    // Bug fix: GET /dashboard/payment-summary is genuinely admin-only on the
    // backend (DashboardController is class-level @PreAuthorize("hasRole('ADMIN')")),
    // so for an SS/Distributor login this was never even called — the four
    // cards (Cash/UPI/Card/Bank) always rendered ₹0, forever, no matter how
    // many payments that party actually recorded. STATE.payments is already
    // scoped correctly to "my payments" for that role (see
    // PaymentService.getAllPayments()), so for non-admin just total it up
    // client-side instead of leaving the cards permanently blank.
    const summary = isAdminRole
        ? (summaryRes.status === "fulfilled" ? unwrap(summaryRes.value, {}) : {})
        : computePaymentSummaryFromPayments(STATE.payments);
    renderPaymentSummaryCards(summary);
    renderPayments();
}

// Mirrors DashboardService.getPaymentSummary()'s method -> bucket mapping
// exactly, so an SS/Distributor's client-computed cards agree with what
// Admin sees computed server-side for the same data.
function computePaymentSummaryFromPayments(payments){
    const totals = { cashAmount: 0, upiAmount: 0, cardAmount: 0, bankAmount: 0 };
    (payments || []).forEach(p => {
        const method = String(p.paymentMethod || "").trim().toUpperCase();
        const amount = Number(p.amount) || 0;
        if (method === "CASH") totals.cashAmount += amount;
        else if (["UPI","GPAY","PHONEPE","PAYTM"].includes(method)) totals.upiAmount += amount;
        else if (method === "CARD") totals.cardAmount += amount;
        else if (["BANK","BANK_TRANSFER","NEFT","RTGS","IMPS"].includes(method)) totals.bankAmount += amount;
        // CHEQUE and anything unrecognized has no card on this page, same as admin's version.
    });
    return totals;
}

function renderPaymentSummaryCards(summary){
    // Admin: these totals come straight from GET /dashboard/payment-summary,
    // computed server-side from every Payment row. Non-admin: computed
    // client-side above from this party's own scoped payment list (see
    // computePaymentSummaryFromPayments) since that endpoint isn't callable
    // for their role.
    document.getElementById("paymCash").textContent = formatCurrency(summary.cashAmount);
    document.getElementById("paymUpi").textContent = formatCurrency(summary.upiAmount);
    document.getElementById("paymCard").textContent = formatCurrency(summary.cardAmount);
    document.getElementById("paymBank").textContent = formatCurrency(summary.bankAmount);
}

function renderPayments(){
    const term = (document.getElementById("paymentsSearch")?.value || "").toLowerCase();
    const statusFilter = document.getElementById("paymentsStatusFilter")?.value || "";
    const modeFilter = document.getElementById("paymentsModeFilter")?.value || "";
    const dateFrom = document.getElementById("paymentsDateFrom")?.value || "";
    const dateTo = document.getElementById("paymentsDateTo")?.value || "";

    // Status option list is built from whatever values actually exist on
    // real Payment rows (the field is a free-form string on the backend,
    // not a fixed enum) rather than guessing labels that might not match.
    const statusSelect = document.getElementById("paymentsStatusFilter");
    if (statusSelect){
        const prev = statusSelect.value;
        const statuses = [...new Set(STATE.payments.map(p => p.paymentStatus).filter(Boolean))];
        statusSelect.innerHTML = `<option value="">All Status</option>` + statuses.map(s=>`<option value="${escapeHtml(s)}">${escapeHtml(s)}</option>`).join("");
        statusSelect.value = prev;
    }

    const rows = STATE.payments.filter(p => {
        const matchesTerm = !term || [p.transactionId, p.shopName].some(v => String(v||"").toLowerCase().includes(term));
        const matchesStatus = !statusFilter || p.paymentStatus === statusFilter;
        const matchesMode = !modeFilter || String(p.paymentMethod||"").toUpperCase() === modeFilter;
        const payDate = p.paymentDate ? String(p.paymentDate).slice(0,10) : null;
        const matchesFrom = !dateFrom || (payDate && payDate >= dateFrom);
        const matchesTo = !dateTo || (payDate && payDate <= dateTo);
        return matchesTerm && matchesStatus && matchesMode && matchesFrom && matchesTo;
    });

    const isAdminRole = isAdminRoleName((getCurrentUser() || {}).role);

    document.getElementById("paymentsTableBody").innerHTML = rows.map((p) => {
        const idx = STATE.payments.indexOf(p);
        return `<tr>
      <td><button type="button" class="btn btn-link p-0 fw-600 link-soft" style="text-decoration:none" data-details="${idx}">${escapeHtml(p.transactionId || p.id || ("#"+(idx+1)))}</button></td>
      <td>${escapeHtml(p.shopName || "—")}</td><td>${escapeHtml(p.paymentMethod || "—")}</td><td>${p.amount != null ? formatCurrency(p.amount) : "—"}</td><td>${formatDate(p.paymentDate)}</td>
      <td>${p.paymentStatus ? statusBadge(p.paymentStatus) : "—"}</td>
      <td>${renderProofCell(p)}</td>
      <td class="text-end">${paymentVerificationCell(p, isAdminRole)}${p.id ? `<button class="action-btn delete" data-delete-payment="${p.id}" title="Delete Payment"><i class="fa-solid fa-trash"></i></button>` : ""}</td>
    </tr>`;
    }).join("") || `<tr><td colspan="8" class="text-center text-muted py-4">No payments found.</td></tr>`;

    document.getElementById("paymentsTableBody").querySelectorAll("[data-details]").forEach(btn => {
        btn.addEventListener("click", () => openPaymentDetails(Number(btn.dataset.details)));
    });
    document.getElementById("paymentsTableBody").querySelectorAll("[data-delete-payment]").forEach(btn => {
        btn.addEventListener("click", () => deletePayment(Number(btn.dataset.deletePayment)));
    });
    document.getElementById("paymentsTableBody").querySelectorAll("[data-verify-payment]").forEach(btn => {
        btn.addEventListener("click", () => verifyOrRejectPayment(Number(btn.dataset.verifyPayment), "verify"));
    });
    document.getElementById("paymentsTableBody").querySelectorAll("[data-reject-payment]").forEach(btn => {
        btn.addEventListener("click", () => verifyOrRejectPayment(Number(btn.dataset.rejectPayment), "reject"));
    });
    document.getElementById("paymentsTableBody").querySelectorAll("[data-proof-url]").forEach(btn => {
        btn.addEventListener("click", () => openProofPreview(btn.dataset.proofUrl));
    });
    document.getElementById("paymentsTableBody").querySelectorAll("[data-delete-proof]").forEach(btn => {
        btn.addEventListener("click", () => deletePaymentProof(Number(btn.dataset.deleteProof)));
    });

    const pendingInvoices = STATE.invoices.filter(i => normalizeStatusLabel(i.paymentStatus) !== "Paid");
    document.getElementById("pendingCollectionList").innerHTML = pendingInvoices.map(inv => `<div class="mini-item">
      <div class="mini-item-icon bg-rose"><i class="fa-solid fa-hand-holding-dollar"></i></div>
      <div><div class="mini-item-title">${escapeHtml(inv.shopName || "—")}</div><div class="mini-item-sub">${escapeHtml(inv.invoiceNumber)}</div></div>
      <div class="mini-item-value">${formatCurrency(inv.balanceAmount)}</div>
    </div>`).join("") || `<p class="text-center py-4">No pending collections.</p>`;
}

["paymentsSearch"].forEach(id => document.getElementById(id)?.addEventListener("input", renderPayments));
["paymentsStatusFilter","paymentsModeFilter","paymentsDateFrom","paymentsDateTo"].forEach(id => document.getElementById(id)?.addEventListener("change", renderPayments));

function renderProofCell(p){
    const proofUrl = p.proofFilePath || p.proofImage;
    if (!proofUrl) return `<span class="proof-none">Not uploaded</span>`;
    const fileName = p.proofFileName || "Proof file";
    const proofId = p.proofId;
    return `<span class="d-inline-flex align-items-center gap-1" style="max-width:100%">
        <span class="text-truncate small" style="max-width:110px" title="${escapeHtml(fileName)}">${escapeHtml(fileName)}</span>
        <button type="button" class="action-btn view" data-proof-url="${escapeHtml(proofUrl)}" title="View proof"><i class="fa-solid fa-eye"></i></button>
        ${proofId ? `<button type="button" class="action-btn delete" data-delete-proof="${proofId}" title="Delete proof"><i class="fa-solid fa-trash"></i></button>` : ""}
    </span>`;
}

// Resolves a proof path/URL coming from the backend (e.g.
// "/api/v1/payment-proofs/file/123") into a full URL against the API's
// origin, so it works regardless of host/port.
function resolveUploadUrl(path){
    if (!path) return "";
    if (/^https?:\/\//i.test(path)) return path;
    const origin = API_BASE.replace(/\/api\/v1\/?$/, "");
    return origin + (path.startsWith("/") ? path : "/" + path);
}

// Payment proof files are served from an access-controlled endpoint (not
// plain static files — see WebConfig/PaymentProofController on the
// backend), so a plain <img src> or window.open() can't fetch it: the
// browser won't attach the Authorization header to those. This fetches the
// bytes with the same Bearer token apiRequest() uses, then hands the
// caller a local blob: URL to actually display.
async function fetchProofBlobUrl(path){
    const url = resolveUploadUrl(path);
    if (!url) return null;
    const headers = {};
    if (getToken()) headers["Authorization"] = "Bearer " + getToken();
    const response = await fetch(url, { headers });
    if (!response.ok){
        throw new Error(`Could not load proof file (${response.status})`);
    }
    const blob = await response.blob();
    return { blobUrl: URL.createObjectURL(blob), contentType: blob.type || "" };
}

// PDFs can't render inside the <img> preview modal, so those open in a new
// tab; images use the existing "Payment Proof" preview modal.
async function openProofPreview(path){
    if (!path) return;
    let result;
    try{
        result = await fetchProofBlobUrl(path);
    }catch(e){
        showToast("Error", e.message || "Could not load proof file.", "error");
        return;
    }
    if (!result) return;
    const { blobUrl, contentType } = result;
    if (contentType === "application/pdf" || /\.pdf($|\?)/i.test(path)){
        window.open(blobUrl, "_blank");
        return;
    }
    document.getElementById("proofPreviewImage").src = blobUrl;
    new bootstrap.Modal(document.getElementById("proofPreviewModal")).show();
}

function deletePaymentProof(proofId){
    showConfirm("Delete Proof", "This will permanently remove the uploaded proof file. Continue?", async () => {
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.paymentProofs + "/" + proofId, { method: "DELETE" });
            showToast("Deleted", "Proof file removed.", "success");
            await loadAndRenderPayments();
        }catch(err){
            showApiError(err, "Could not delete proof");
        }finally{
            hideSpinner();
        }
    });
}

/* ---------------- Payment verify / reject (Admin only) ----------------
   Backend endpoints PATCH /payments/{id}/verify and /reject already exist
   and are fully implemented but, until now, had no frontend caller at
   all. Same admin-only visibility rule already used across the app
   (isAdminRoleName), and once a payment is VERIFIED/REJECTED the actions
   are replaced by a status badge instead of showing both buttons again. */
function paymentVerificationStatus(p){
    if (p.verificationStatus) return String(p.verificationStatus).toUpperCase();
    if (p.verified === true) return "VERIFIED";
    return "PENDING";
}
function paymentVerificationCell(p, isAdminRole){
    if (!isAdminRole || !p.id) return "";
    const vs = paymentVerificationStatus(p);
    if (vs === "VERIFIED") return `<span class="badge bg-success-subtle text-success me-1">Verified</span>`;
    if (vs === "REJECTED") return `<span class="badge bg-danger-subtle text-danger me-1">Rejected</span>`;
    return `<button class="action-btn edit" data-verify-payment="${p.id}" title="Verify Payment"><i class="fa-solid fa-check"></i></button>
            <button class="action-btn delete" data-reject-payment="${p.id}" title="Reject Payment"><i class="fa-solid fa-ban"></i></button>`;
}
function verifyOrRejectPayment(id, action){
    const title = action === "verify" ? "Verify Payment" : "Reject Payment";
    const body = action === "verify"
        ? "Mark this payment as verified? This confirms the payment proof/details have been checked."
        : "Reject this payment? This marks the payment as invalid/unverified.";
    showConfirm(title, body, async () => {
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.payments + "/" + id + "/" + action, { method: "PATCH" });
            showToast(action === "verify" ? "Payment verified" : "Payment rejected",
                action === "verify" ? "The payment has been marked as verified." : "The payment has been rejected.",
                "success");
            await loadAndRenderPayments();
            refreshDashboard();
        }catch(err){
            showApiError(err, action === "verify" ? "Could not verify payment" : "Could not reject payment");
        }finally{
            hideSpinner();
        }
    });
}

function deletePayment(id){
    showConfirm("Delete Payment", `This will permanently remove this payment record. Continue?`, async () => {
        showSpinner();
        try{
            await apiRequest(ENDPOINTS.payments + "/" + id, { method:"DELETE" });
            showToast("Deleted", "Payment record removed successfully.", "success");
            await loadAndRenderPayments();
            refreshDashboard();
        }catch(err){
            showApiError(err, "Delete failed");
        }finally{
            hideSpinner();
        }
    });
}

function openPaymentDetails(idx){
    const p = STATE.payments[idx];
    if (!p) return;
    document.getElementById("paymentDetailsBody").innerHTML = `
    <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Shop</span><b>${escapeHtml(p.shopName || "—")}</b></div>
    <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Payment Mode</span><b>${escapeHtml(p.paymentMethod || "—")}</b></div>
    <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Reference Number</span><b>${escapeHtml(p.transactionId || "—")}</b></div>
    <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Amount</span><b>${p.amount != null ? formatCurrency(p.amount) : "—"}</b></div>
    <div class="d-flex justify-content-between py-2 border-bottom"><span class="text-muted">Status</span><b>${escapeHtml(p.paymentStatus || "—")}</b></div>
    <div class="d-flex justify-content-between py-2"><span class="text-muted">Date</span><b>${formatDate(p.paymentDate)}</b></div>
  `;
    new bootstrap.Modal(document.getElementById("paymentDetailsModal")).show();
}

// Config describing the upload requirement per payment mode.
const PAYMENT_PROOF_CONFIG = {
    CASH:          { label:"Cash Bill",                accept:".pdf,.jpg,.jpeg,.png", hint:"PDF, JPG, JPEG or PNG · max 5MB" },
    UPI:           { label:"UPI Payment Screenshot",   accept:".jpg,.jpeg,.png",      hint:"JPG, JPEG or PNG · max 5MB" },
    CARD:          { label:"Card Receipt",             accept:".pdf,.jpg,.jpeg,.png", hint:"PDF, JPG, JPEG or PNG · max 5MB" },
    BANK_TRANSFER: { label:"Bank Transfer Receipt",    accept:".pdf,.jpg,.jpeg,.png", hint:"PDF, JPG, JPEG or PNG · max 5MB" },
    CHEQUE:        { label:"Cheque Image",             accept:".jpg,.jpeg,.png,.pdf", hint:"JPG, JPEG, PNG or PDF · max 5MB" },
};
let currentPaymentProof = null;

function buildPaymentUploadCardHtml(mode){
    const cfg = PAYMENT_PROOF_CONFIG[mode] || PAYMENT_PROOF_CONFIG.CASH;
    return `
    <label class="form-label-soft" id="paymentProofLabel">${cfg.label}</label>
    <div class="upload-card" id="paymentUploadCard">
      <input type="file" id="paymentProofInput" class="d-none" accept="${cfg.accept}">
      <div id="paymentUploadIdle">
        <div class="upload-card-icon"><i class="fa-solid fa-cloud-arrow-up"></i></div>
        <div class="upload-card-text">Drag & drop file here, or click to browse</div>
        <div class="upload-card-hint" id="paymentUploadHint">${cfg.hint}</div>
      </div>
      <div id="paymentUploadFilled" class="d-none">
        <div class="upload-preview-row">
          <div id="paymentProofThumbWrap"></div>
          <div class="upload-preview-info">
            <div class="upload-preview-name" id="paymentProofName"></div>
            <div class="upload-preview-size" id="paymentProofSize"></div>
          </div>
          <button type="button" class="upload-remove-btn" id="paymentProofRemoveBtn" title="Remove file"><i class="fa-solid fa-xmark"></i></button>
        </div>
      </div>
    </div>
    <div class="field-error" id="paymentProofError">This proof is required for the selected payment mode.</div>`;
}

function wirePaymentUploadCard(){
    const card = document.getElementById("paymentUploadCard");
    const input = document.getElementById("paymentProofInput");
    card.addEventListener("click", (e) => { if (!e.target.closest("#paymentProofRemoveBtn")) input.click(); });
    ["dragover","dragenter"].forEach(evt => card.addEventListener(evt, (e) => { e.preventDefault(); card.classList.add("drag-over"); }));
    ["dragleave","drop"].forEach(evt => card.addEventListener(evt, (e) => { e.preventDefault(); card.classList.remove("drag-over"); }));
    card.addEventListener("drop", (e) => { if (e.dataTransfer.files[0]) handlePaymentProofFile(e.dataTransfer.files[0]); });
    input.addEventListener("change", (e) => { if (e.target.files[0]) handlePaymentProofFile(e.target.files[0]); });
}

function handlePaymentProofFile(file){
    if (file.size > 5 * 1024 * 1024){
        showToast("File too large", "Please upload a file smaller than 5MB.", "error");
        return;
    }
    document.getElementById("paymentUploadCard").classList.remove("invalid");
    document.getElementById("paymentProofError").classList.remove("show");
    currentPaymentProof = { file, name: file.name, size: file.size, isImage: file.type.startsWith("image/") };
    document.getElementById("paymentUploadIdle").classList.add("d-none");
    document.getElementById("paymentUploadFilled").classList.remove("d-none");
    document.getElementById("paymentUploadCard").classList.add("has-file");
    document.getElementById("paymentProofThumbWrap").innerHTML = currentPaymentProof.isImage
        ? `<img src="${URL.createObjectURL(file)}" class="upload-preview-thumb">`
        : `<div class="upload-preview-pdf"><i class="fa-solid fa-file-pdf"></i></div>`;
    document.getElementById("paymentProofName").textContent = file.name;
    document.getElementById("paymentProofSize").textContent = (file.size / 1024).toFixed(1) + " KB";
}

function resetPaymentProofState(){ currentPaymentProof = null; }

// Invoices billed TO the logged-in party (Distributor/Super Stockist) by
// the tier above never appear in STATE.invoices (that list is only what
// *this* party raised — see loadAndRenderBilledToMe's comment). A label
// helper shared by both dropdown sources ("shopName" only exists on
// DISTRIBUTOR_TO_SHOP invoices; the other two levels only carry
// distributorName/superStockistName).
function invoicePartyLabel(i){
    return i.shopName || i.distributorName || i.superStockistName || "Company (Admin)";
}

// Picks which party id(s) belong in the PaymentRequest body for a given
// invoice, based on invoiceLevel — mirrors PaymentService.resolvePaymentParties()
// on the backend and the exact same three levels the invoice-creation form
// above already branches on. Returns null if the invoice is missing the
// id(s) its own level requires (defensive — same intent as the old
// unconditional shopId/distributorId check, just level-aware now).
function buildPaymentPartyFields(invoice){
    const level = invoice && invoice.invoiceLevel;
    if (level === "COMPANY_TO_SUPER_STOCKIST"){
        if (!invoice.superStockistId) return null;
        return { superStockistId: invoice.superStockistId };
    }
    if (level === "SUPER_STOCKIST_TO_DISTRIBUTOR"){
        if (!invoice.distributorId || !invoice.superStockistId) return null;
        return { distributorId: invoice.distributorId, superStockistId: invoice.superStockistId };
    }
    // DISTRIBUTOR_TO_SHOP, and any legacy/unrecognized level, falls back to
    // the original shop+distributor pairing.
    if (!invoice || !invoice.shopId || !invoice.distributorId) return null;
    return { shopId: invoice.shopId, distributorId: invoice.distributorId };
}

/**
 * openRecordPaymentModal — shared Record Payment modal.
 * - Called with no args from the Payments page's own "Add Payment" button:
 *   the invoice dropdown lists every invoice this party raised (STATE.invoices).
 * - Called with a specific invoice from the "Bills To Me" page (invoices
 *   raised AGAINST this party by the tier above, e.g. a Distributor paying
 *   a SUPER_STOCKIST_TO_DISTRIBUTOR bill): the dropdown is locked to that
 *   one invoice so the right party ids always get sent.
 */
function openRecordPaymentModal(preselectedInvoice){
    const invoiceOptions = preselectedInvoice ? [preselectedInvoice] : STATE.invoices;
    if (!invoiceOptions.length){ showToast("No invoices", "Create an invoice first — payments must be linked to an invoice.", "warning"); return; }
    resetPaymentProofState();
    document.getElementById("crudModalTitle").textContent = "Record Payment";
    document.getElementById("crudFormFields").innerHTML = `
    <div class="col-md-6"><label class="form-label-soft">Invoice</label>
      <select class="form-select" data-field="invoiceId" id="paymentInvoiceSelect" ${preselectedInvoice ? "disabled" : ""}>${invoiceOptions.map(i=>`<option value="${i.id}">${escapeHtml(i.invoiceNumber)} — ${escapeHtml(invoicePartyLabel(i))}</option>`).join("")}</select></div>
    <div class="col-md-6"><label class="form-label-soft">Payment Mode</label>
      <select class="form-select" data-field="paymentMethod" id="paymentModeSelect">
        <option value="CASH">Cash</option>
        <option value="UPI">UPI</option>
        <option value="CARD">Card</option>
        <option value="BANK_TRANSFER">Bank Transfer</option>
        <option value="CHEQUE">Cheque</option>
      </select></div>
    <div class="col-md-6"><label class="form-label-soft">Reference Number</label><input type="text" class="form-control" data-field="transactionId" placeholder="e.g. UTR / Cheque No."></div>
    <div class="col-md-6"><label class="form-label-soft">Amount (₹)</label><input type="number" class="form-control" data-field="amount" required></div>
    <div class="col-md-6"><label class="form-label-soft">Date</label><input type="date" class="form-control" data-field="paymentDate" value="${new Date().toISOString().slice(0,10)}"></div>
    <div class="col-md-6"><label class="form-label-soft">Status</label>
      <select class="form-select" data-field="paymentStatus">
        <option value="COMPLETED">Completed</option>
        <option value="PENDING">Pending</option>
      </select></div>
    <div class="col-12">${buildPaymentUploadCardHtml("CASH")}</div>`;

    wirePaymentUploadCard();
    document.getElementById("paymentModeSelect").addEventListener("change", (e) => {
        const cfg = PAYMENT_PROOF_CONFIG[e.target.value] || PAYMENT_PROOF_CONFIG.CASH;
        document.getElementById("paymentProofLabel").textContent = cfg.label;
        document.getElementById("paymentUploadHint").textContent = cfg.hint;
        document.getElementById("paymentProofInput").setAttribute("accept", cfg.accept);
        resetPaymentProofState();
        document.getElementById("paymentUploadIdle").classList.remove("d-none");
        document.getElementById("paymentUploadFilled").classList.add("d-none");
        document.getElementById("paymentUploadCard").classList.remove("has-file","invalid");
        document.getElementById("paymentProofError").classList.remove("show");
    });
    document.getElementById("paymentProofRemoveBtn")?.addEventListener("click", (e) => {
        e.stopPropagation();
        resetPaymentProofState();
        document.getElementById("paymentUploadIdle").classList.remove("d-none");
        document.getElementById("paymentUploadFilled").classList.add("d-none");
        document.getElementById("paymentUploadCard").classList.remove("has-file");
        document.getElementById("paymentProofInput").value = "";
    });

    const modal = new bootstrap.Modal(document.getElementById("crudModal"));
    document.getElementById("crudSaveBtn").onclick = async () => {
        const form = document.getElementById("crudFormFields");
        const values = {};
        form.querySelectorAll("[data-field]").forEach(el => values[el.dataset.field] = el.value);

        if (!values.amount){ showToast("Missing amount","Enter a payment amount.","error"); return; }
        if (!currentPaymentProof){
            const cfg = PAYMENT_PROOF_CONFIG[values.paymentMethod] || PAYMENT_PROOF_CONFIG.CASH;
            document.getElementById("paymentUploadCard").classList.add("invalid");
            document.getElementById("paymentProofError").classList.add("show");
            showToast("Proof required", `${cfg.label} is required to record this payment.`, "error");
            return;
        }

        // PaymentRequest requires invoiceId + whichever party ids that
        // invoice's level needs (see buildPaymentPartyFields — DISTRIBUTOR_TO_SHOP
        // needs shopId+distributorId, SUPER_STOCKIST_TO_DISTRIBUTOR needs
        // distributorId+superStockistId, COMPANY_TO_SUPER_STOCKIST needs only
        // superStockistId). InvoiceResponse already includes all of these
        // directly (see InvoiceMapper.toResponse), so use those instead of
        // matching names back against the shops/distributors lists —
        // name-matching breaks the moment a shop/distributor is renamed or deleted.
        const invoice = invoiceOptions.find(i => i.id == values.invoiceId);
        const partyFields = invoice ? buildPaymentPartyFields(invoice) : null;
        if (!invoice || !partyFields){
            showToast("Could not resolve invoice", "This invoice has no shop/distributor/super-stockist linked to it.", "error");
            return;
        }

        const payload = {
            invoiceId: Number(values.invoiceId),
            ...partyFields,
            amount: Number(values.amount),
            paymentMethod: values.paymentMethod,
            paymentStatus: values.paymentStatus,
            transactionId: values.transactionId || null,
            paymentDate: values.paymentDate,
        };

        showSpinner();
        try{
            const res = await apiRequest(ENDPOINTS.payments, { method:"POST", body: payload });
            const created = unwrap(res, null);
            if (created && created.id){
                try{
                    const formData = new FormData();
                    formData.append("file", currentPaymentProof.file);
                    await apiRequestMultipart(ENDPOINTS.paymentProofs + "/" + created.id + "/upload", formData);
                }catch(proofErr){
                    showApiError(proofErr, "Payment saved, but the proof upload failed");
                }
            } else {
                showToast("Proof not attached", "Payment was saved, but the backend didn't return a payment ID to attach the proof to.", "warning");
            }
            modal.hide();
            resetPaymentProofState();
            showToast("Payment recorded", "Payment saved successfully.", "success");
            // Bug fix: loadAndRenderPayments() only (re)loads STATE.invoices
            // when it's currently empty -- a plain optimization for page
            // load, but it meant that if invoices had already been viewed
            // earlier in the session, "Pending Collection" kept showing this
            // invoice's OLD balance/status forever after paying it (the
            // server-side balance was correctly updated, the browser's
            // cached copy just never got told). Force a fresh invoice list
            // first so Pending Collection reflects this payment immediately.
            await loadAndRenderInvoices();
            await loadAndRenderPayments();
            refreshDashboard();
        }catch(err){
            showApiError(err, "Payment save failed");
        }finally{
            hideSpinner();
        }
    };
    modal.show();
}
document.getElementById("addPaymentBtn").addEventListener("click", () => openRecordPaymentModal());


/* ==================== 10. REPORTS ==================== */
// All report data is sourced from STATE, which is only ever populated by
// backend GET responses — reports always match what's on the CRUD/Dashboard pages.
/* ==================== SALES RETURNS ==================== */

async function loadAndRenderSalesReturns(){
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.salesReturns);
        STATE.salesReturns = unwrap(res, []);
        renderSalesReturnsTable();
    }catch(err){
        if (err.status === 401 || err.status === 403){ handleSessionExpired(); return; }
        showApiError(err, "Could not load sales returns");
    }finally{
        hideSpinner();
    }
}

function renderSalesReturnsTable(){
    const term = (document.getElementById("salesReturnsSearch")?.value || "").toLowerCase();
    const rows = STATE.salesReturns.filter(r => !term ||
        [r.invoiceNumber, r.shopName, r.productName].some(v => String(v||"").toLowerCase().includes(term)));

    document.getElementById("salesReturnsTableBody").innerHTML = rows.map(r => `
        <tr>
            <td>${formatDate(r.returnDate)}</td>
            <td>${escapeHtml(r.invoiceNumber || "—")}</td>
            <td>${escapeHtml(r.shopName || "—")}</td>
            <td>${escapeHtml(r.distributorName || "—")}</td>
            <td>${escapeHtml(r.productName || "—")}</td>
            <td>${r.quantity ?? "—"}</td>
            <td>${formatCurrency(r.returnAmount)}</td>
            <td>${escapeHtml(r.reason || "—")}</td>
        </tr>`).join("") || `<tr><td colspan="8" class="text-center text-muted py-4">No sales returns recorded yet.</td></tr>`;
}
document.getElementById("salesReturnsSearch")?.addEventListener("input", renderSalesReturnsTable);

document.getElementById("addSalesReturnBtn")?.addEventListener("click", async () => {
    const today = new Date();
    document.getElementById("srReturnDate").value = today.toISOString().slice(0,10);
    if (today.getDate() !== 25){
        document.getElementById("srDateHint").textContent =
            "Returns can only be submitted on the 25th of the month — today isn't the 25th, so Save will be rejected.";
        document.getElementById("srDateHint").classList.add("text-danger");
    } else {
        document.getElementById("srDateHint").textContent = "Today is the 25th — returns can be submitted.";
        document.getElementById("srDateHint").classList.remove("text-danger");
    }
    if (!STATE.invoices.length) await loadAndRenderInvoices();
    const shopInvoices = STATE.invoices.filter(i => i.invoiceLevel === "DISTRIBUTOR_TO_SHOP" && i.shopId);
    const invoiceSelect = document.getElementById("srInvoice");
    invoiceSelect.innerHTML = shopInvoices.map(i =>
        `<option value="${i.id}">${escapeHtml(i.invoiceNumber)} — ${escapeHtml(i.shopName || "")}</option>`).join("")
        || `<option value="">No shop invoices available</option>`;
    document.getElementById("srQuantity").value = 1;
    document.getElementById("srReason").value = "";
    populateSrProductOptions();
    new bootstrap.Modal(document.getElementById("salesReturnModal")).show();
});

function populateSrProductOptions(){
    const invoiceId = document.getElementById("srInvoice").value;
    const invoice = STATE.invoices.find(i => String(i.id) === String(invoiceId));
    const productSelect = document.getElementById("srProduct");
    const items = (invoice && invoice.items) || [];
    productSelect.innerHTML = items.map(it =>
        `<option value="${it.productId}" data-max="${it.quantity}">${escapeHtml(it.productName)} (sold: ${it.quantity})</option>`).join("")
        || `<option value="">No line items on this invoice</option>`;
}
document.getElementById("srInvoice")?.addEventListener("change", populateSrProductOptions);

document.getElementById("srSubmitBtn")?.addEventListener("click", async () => {
    const invoiceId = document.getElementById("srInvoice").value;
    const productId = document.getElementById("srProduct").value;
    const quantity = Number(document.getElementById("srQuantity").value);
    const reason = document.getElementById("srReason").value.trim();
    const confirmPassword = document.getElementById("srConfirmPassword").value;

    if (!invoiceId || !productId){
        showToast("Missing selection", "Choose an invoice and a product.", "error");
        return;
    }
    if (!quantity || quantity < 1){
        showToast("Invalid quantity", "Return quantity must be at least 1.", "error");
        return;
    }
    if (!confirmPassword){
        showToast("Password required", "Re-enter your password to confirm this sales return.", "error");
        return;
    }
    try{
        // NOTE: apiRequest() already JSON.stringifies whatever `body` is
        // given (see its definition) -- passing an already-stringified
        // string here used to double-encode it, so the backend received a
        // JSON string literal instead of a JSON object and every sales
        // return submission failed with a 400. Pass the plain object.
        await apiRequest(ENDPOINTS.salesReturns, {
            method: "POST",
            body: { invoiceId: Number(invoiceId), productId: Number(productId), quantity, reason: reason || null, confirmPassword },
        });
        showToast("Return recorded", "Sales return saved successfully.", "success");
        document.getElementById("srConfirmPassword").value = "";
        bootstrap.Modal.getInstance(document.getElementById("salesReturnModal"))?.hide();
        loadAndRenderSalesReturns();
    }catch(err){
        showApiError(err, "Could not save sales return");
    }
});

const REPORT_BUILDERS = {
    sales(range){
        const invoices = filterReportRows(STATE.invoices, "invoiceDate", range);
        return { head:["Invoice #","Shop","Date","Amount","Status"], rows: invoices.map(inv =>
                [inv.invoiceNumber, inv.shopName || "—", formatDate(inv.invoiceDate), formatCurrency(inv.totalAmount), normalizeStatusLabel(inv.paymentStatus)])};
    },
    product(){
        // Stock is a point-in-time snapshot, not a dated/distributor-scoped
        // transaction — date range and distributor filters have no
        // meaningful effect here, same as Low Stock below.
        return { head:["Product","Category","Stock","Price","Tax %"], rows: STATE.products.map(p =>
                [p.productName, p.categoryName || "—", p.stockQuantity ?? 0, formatCurrency(p.sellingPrice), (p.gstPercentage ?? 0) + "%"])};
    },
    lowstock(){
        const items = STATE.products.filter(p => (p.stockQuantity ?? 0) <= (p.minimumStock ?? 0));
        return { head:["Product","Category","Current Stock","Minimum Stock","Shortfall"], rows: items.map(p =>
                [p.productName, p.categoryName || "—", p.stockQuantity ?? 0, p.minimumStock ?? 0, Math.max((p.minimumStock ?? 0) - (p.stockQuantity ?? 0), 0)])};
    },
    // Connects each distributor to their own invoices + payments so admin
    // can see sales/collections/pending/last-activity per distributor in
    // one place, instead of a bare name/area/contact list.
    distributor(range){
        const distributors = range && range.distributorId
            ? STATE.distributors.filter(d => String(d.id) === String(range.distributorId))
            : STATE.distributors;
        const rows = distributors.map(d => {
            const dInvoices = filterByDateRange(STATE.invoices.filter(i => i.distributorName === d.distributorName), "invoiceDate", range);
            const dPayments = filterByDateRange(STATE.payments.filter(p => p.distributorName === d.distributorName), "paymentDate", range);
            const totalSales = dInvoices.reduce((sum,i) => sum + (Number(i.totalAmount)||0), 0);
            const totalPaid = dPayments.reduce((sum,p) => sum + (Number(p.amount)||0), 0);
            const totalPending = dInvoices.reduce((sum,i) => sum + (Number(i.balanceAmount)||0), 0);
            const activityDates = [...dInvoices.map(i=>i.invoiceDate), ...dPayments.map(p=>p.paymentDate)].filter(Boolean).sort();
            const lastActivity = activityDates.length ? activityDates[activityDates.length-1] : null;
            return [
                d.distributorName, d.city || "—", d.mobileNumber, d.active ? "Active" : "Inactive",
                dInvoices.length, formatCurrency(totalSales), formatCurrency(totalPaid), formatCurrency(totalPending),
                lastActivity ? formatDate(lastActivity) : "No activity yet",
            ];
        });
        return { head:["Name","Area","Contact","Status","Invoices","Total Sales","Total Paid","Pending","Last Activity"], rows };
    },
    invoice(range){
        const invoices = filterReportRows(STATE.invoices, "invoiceDate", range);
        return { head:["Invoice #","Shop","Distributor","Grand Total","Status"], rows: invoices.map(inv =>
                [inv.invoiceNumber, inv.shopName || "—", inv.distributorName || "—", formatCurrency(inv.totalAmount), normalizeStatusLabel(inv.paymentStatus)])};
    },
    payment(range){
        const payments = filterReportRows(STATE.payments, "paymentDate", range);
        return { head:["Ref","Shop","Distributor","Mode","Amount","Date","Status"], rows: payments.map((p,idx) =>
                [p.transactionId || ("#"+(idx+1)), p.shopName || "—", p.distributorName || "—", p.paymentMethod || "—", p.amount != null ? formatCurrency(p.amount) : "—", formatDate(p.paymentDate), p.paymentStatus || "—"])};
    },
    // Mirrors the "Account Ledger" view from Busy/Tally-style accounting
    // software: every Sale (invoice) and Receipt (payment) for ONE shop,
    // in chronological order, with a running Dr/Cr balance. Opening
    // balance is computed from everything BEFORE the "From" date so the
    // running total is correct even when a date range is applied — not
    // just zero-started, matching how Busy's ledger shows "Opening Bal.".
    ledger(range){
        const shopId = range && range.shopId;
        if (!shopId) return { head:["Date","Type","Vch/Bill No","Debit","Credit","Balance"], rows: [] };

        const shopInvoices = STATE.invoices.filter(i => String(i.shopId) === String(shopId));
        const shopPayments = STATE.payments.filter(p => String(p.shopId) === String(shopId));

        // Every transaction as {date, type, ref, debit, credit}. A Sale
        // (invoice) increases what the shop owes (Debit); a Receipt
        // (payment) reduces it (Credit) — same convention as the Busy screenshot.
        let entries = [
            ...shopInvoices.map(i => ({ date: i.invoiceDate, type: "Sale", ref: i.invoiceNumber, debit: Number(i.totalAmount)||0, credit: 0 })),
            ...shopPayments.map(p => ({ date: p.paymentDate, type: "Rcpt", ref: p.transactionId || "", debit: 0, credit: Number(p.amount)||0 })),
        ].filter(e => e.date).sort((a,b) => String(a.date).localeCompare(String(b.date)));

        const from = range.from, to = range.to;
        let openingBalance = 0;
        if (from){
            entries.forEach(e => {
                if (String(e.date).slice(0,10) < from) openingBalance += e.debit - e.credit;
            });
        }
        let visible = entries.filter(e => {
            const d = String(e.date).slice(0,10);
            if (from && d < from) return false;
            if (to && d > to) return false;
            return true;
        });

        const rows = [];
        if (from){
            rows.push(["", "Opening Balance", "", "", "", formatCurrency(openingBalance)]);
        }
        let running = openingBalance;
        visible.forEach(e => {
            running += e.debit - e.credit;
            rows.push([
                formatDate(e.date), e.type, e.ref || "—",
                e.debit ? formatCurrency(e.debit) : "",
                e.credit ? formatCurrency(e.credit) : "",
                formatCurrency(running) + (running >= 0 ? " Dr" : " Cr"),
            ]);
        });
        return { head:["Date","Type","Vch/Bill No","Debit","Credit","Balance"], rows };
    },
    // Honest scope note: this schema tracks one running Product.stockQuantity,
    // not a full multi-warehouse Inward/Outward ledger the way the uploaded
    // Busy PDF does (that would need a proper stock-ledger table per
    // warehouse/entity — a bigger schema addition, not done here). What IS
    // accurately computable from real data: total units actually sold
    // (Outward, from real invoice line items) and current live stock
    // (Closing). No fabricated "Inward" column with no real data behind it.
    stocksummary(){
        const rows = STATE.products.map(p => {
            const outward = STATE.invoices
                .filter(i => i.invoiceLevel === "DISTRIBUTOR_TO_SHOP")
                .flatMap(i => i.items || [])
                .filter(it => String(it.productId) === String(p.id))
                .reduce((sum,it) => sum + (Number(it.quantity)||0), 0);
            return [p.productName, p.categoryName || "—", outward, p.stockQuantity ?? 0, p.minimumStock ?? 0];
        });
        return { head:["Product","Category","Total Sold (Outward)","Closing Stock","Minimum Stock"], rows };
    }
};

/** Shared date-range filter used by every report builder above. `range` is
 * {from, to} (either may be empty/undefined — an empty side is unbounded). */
function filterByDateRange(items, dateField, range){
    const from = range && range.from;
    const to = range && range.to;
    if (!from && !to) return items;
    return items.filter(item => {
        const raw = item[dateField];
        if (!raw) return false;
        const d = String(raw).slice(0, 10);
        if (from && d < from) return false;
        if (to && d > to) return false;
        return true;
    });
}

/** Date range + Distributor filter combined, for report rows that carry a
 * distributorId (invoices, payments). Both filters AND together. */
function filterReportRows(items, dateField, range){
    let rows = filterByDateRange(items, dateField, range);
    if (range && range.distributorId){
        rows = rows.filter(item => String(item.distributorId ?? "") === String(range.distributorId));
    }
    return rows;
}
const REPORT_TITLES = { sales:"Sales Report", product:"Product Report", lowstock:"Low Stock Report", distributor:"Distributor Report", invoice:"Invoice Report", payment:"Payment Report", ledger:"Account Ledger", stocksummary:"Stock Summary (Inward / Outward)" };
let currentReport = null;

async function ensureReportDataLoaded(){
    const jobs = [];
    if (!STATE.invoices.length) jobs.push(loadAndRenderInvoices());
    if (!STATE.products.length) jobs.push(loadAndRenderCrud("products"));
    if (!STATE.payments.length) jobs.push(loadAndRenderPayments());
    if (!STATE.distributors.length) jobs.push(loadDistributorsForCurrentRole());
    if (!STATE.shops.length) jobs.push(loadAndRenderCrud("shops"));
    if (jobs.length) await Promise.all(jobs);

    fillSelectPreserving("reportDistributorFilter", `<option value="">All Distributors</option>`,
        STATE.distributors, d => d.id, d => d.distributorName);
    fillSelectPreserving("reportLedgerAccount", `<option value="">Select a shop...</option>`,
        STATE.shops, s => s.id, s => s.shopName);
}

document.getElementById("reportType").addEventListener("change", (e) => {
    document.getElementById("reportLedgerAccountWrap").classList.toggle("d-none", e.target.value !== "ledger");
    const isSettlement = e.target.value === "returnsettlement";
    document.getElementById("reportSettlementMonthWrap").classList.toggle("d-none", !isSettlement);
    document.getElementById("reportSettlementYearWrap").classList.toggle("d-none", !isSettlement);
    if (isSettlement){
        const yearSel = document.getElementById("reportSettlementYear");
        if (!yearSel.options.length){
            const thisYear = new Date().getFullYear();
            let opts = "";
            for (let y = thisYear; y >= thisYear - 3; y--) opts += `<option value="${y}">${y}</option>`;
            yearSel.innerHTML = opts;
        }
        document.getElementById("reportSettlementMonth").value = String(new Date().getMonth() + 1);
    }
});

document.getElementById("generateReportBtn").addEventListener("click", async () => {
    showSpinner();
    try{ await ensureReportDataLoaded(); } finally { hideSpinner(); }
    const type = document.getElementById("reportType").value;
    if (type === "ledger" && !document.getElementById("reportLedgerAccount").value){
        showToast("Select an account", "Choose a shop to generate its ledger.", "warning");
        return;
    }

    if (type === "returnsettlement"){
        const year = document.getElementById("reportSettlementYear").value;
        const month = document.getElementById("reportSettlementMonth").value;
        try{
            const res = await apiRequest(ENDPOINTS.salesReturns + `/settlement?year=${year}&month=${month}`);
            const data = unwrap(res, {});
            const rows = (data.rows || []).map(r => [r.distributorName, r.returnCount, r.totalQuantity, formatCurrency(r.totalReturnAmount)]);
            currentReport = { head: ["Distributor","Returns","Total Qty","Total Return Amount"], rows };
            document.getElementById("reportTitle").textContent =
                `Sales Return Settlement — ${formatDate(data.cycleStart)} to ${formatDate(data.cycleEnd)} (Grand Total: ${formatCurrency(data.grandTotal)})`;
        }catch(err){
            showApiError(err, "Could not generate settlement");
            return;
        }
        document.getElementById("reportTableHead").innerHTML = "<tr>" + currentReport.head.map(h=>`<th>${h}</th>`).join("") + "</tr>";
        document.getElementById("reportTableBody").innerHTML = currentReport.rows.map(r => "<tr>" + r.map(c=>`<td>${escapeHtml(c)}</td>`).join("") + "</tr>").join("") || `<tr><td colspan="4" class="text-center text-muted py-4">No returns in this cycle.</td></tr>`;
        return;
    }

    const range = {
        from: document.getElementById("reportFrom").value || "",
        to: document.getElementById("reportTo").value || "",
        distributorId: document.getElementById("reportDistributorFilter").value || "",
        shopId: document.getElementById("reportLedgerAccount").value || "",
    };
    currentReport = REPORT_BUILDERS[type](range);
    document.getElementById("reportTitle").textContent = REPORT_TITLES[type];
    document.getElementById("reportTableHead").innerHTML = "<tr>" + currentReport.head.map(h=>`<th>${h}</th>`).join("") + "</tr>";
    document.getElementById("reportTableBody").innerHTML = currentReport.rows.map(r => "<tr>" + r.map(c=>`<td>${escapeHtml(c)}</td>`).join("") + "</tr>").join("") || `<tr><td colspan="6" class="text-center text-muted py-4">No data available.</td></tr>`;
});

document.getElementById("exportCsvBtn").addEventListener("click", () => {
    if (!currentReport){ showToast("No report", "Generate a report first.", "warning"); return; }
    const filenameBase = (document.getElementById("reportTitle").textContent || "report").replace(/\s+/g,"_");
    const escapeCsvCell = (v) => {
        const s = String(v ?? "");
        return /[",\n]/.test(s) ? `"${s.replace(/"/g,'""')}"` : s;
    };
    const csvLines = [currentReport.head, ...currentReport.rows].map(row => row.map(escapeCsvCell).join(","));
    const blob = new Blob(["\ufeff" + csvLines.join("\r\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${filenameBase}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    showToast("Exported", `${filenameBase}.csv generated successfully.`, "success");
});

document.getElementById("exportExcelBtn").addEventListener("click", () => {
    if (!currentReport){ showToast("No report", "Generate a report first.", "warning"); return; }
    const filenameBase = (document.getElementById("reportTitle").textContent || "report").replace(/\s+/g,"_");
    const worksheet = XLSX.utils.aoa_to_sheet([currentReport.head, ...currentReport.rows]);
    worksheet["!cols"] = currentReport.head.map(() => ({ wch: 20 }));
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, "Report");
    XLSX.writeFile(workbook, `${filenameBase}.xlsx`);
    showToast("Exported", `${filenameBase}.xlsx generated successfully.`, "success");
});

function reportMetaSuffix(){
    const from = document.getElementById("reportFrom").value;
    const to = document.getElementById("reportTo").value;
    const distId = document.getElementById("reportDistributorFilter").value;
    let suffix = "";
    if (from) suffix += ` · From ${formatDate(from)}`;
    if (to) suffix += ` to ${formatDate(to)}`;
    if (distId){
        const distName = document.querySelector(`#reportDistributorFilter option[value="${distId}"]`)?.textContent;
        if (distName) suffix += ` · Distributor: ${distName}`;
    }
    return suffix;
}

document.getElementById("exportWordBtn").addEventListener("click", () => {
    if (!currentReport){ showToast("No report", "Generate a report first.", "warning"); return; }
    const title = document.getElementById("reportTitle").textContent || "Report";
    const filenameBase = title.replace(/\s+/g,"_");
    const meta = `Generated on ${formatDate(new Date().toISOString())}` + reportMetaSuffix();

    const tableHead = "<tr>" + currentReport.head.map(h => `<th style="border:1px solid #999;padding:6px 10px;background:#f2f2f2;text-align:left;">${escapeHtml(h)}</th>`).join("") + "</tr>";
    const tableRows = currentReport.rows.map(r =>
        "<tr>" + r.map(c => `<td style="border:1px solid #999;padding:6px 10px;">${escapeHtml(c)}</td>`).join("") + "</tr>"
    ).join("") || `<tr><td colspan="${currentReport.head.length}" style="padding:10px;text-align:center;color:#777;">No data available.</td></tr>`;

    // A .doc file that's actually plain HTML — Word opens this natively via
    // its HTML import filter, so no extra docx-generation library is needed.
    const docHtml = `<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word" xmlns="http://www.w3.org/TR/REC-html40">
<head><meta charset="utf-8"><title>${escapeHtml(title)}</title></head>
<body style="font-family:Calibri,Arial,sans-serif;">
<h1 style="margin-bottom:2px;">${escapeHtml(title)}</h1>
<p style="color:#555;margin-top:0;">${escapeHtml(meta)}</p>
<table style="border-collapse:collapse;width:100%;">
<thead>${tableHead}</thead>
<tbody>${tableRows}</tbody>
</table>
</body></html>`;

    const blob = new Blob(['\ufeff', docHtml], { type: "application/msword" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${filenameBase}.doc`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    showToast("Exported", `${filenameBase}.doc generated successfully.`, "success");
});

document.getElementById("exportPdfBtn").addEventListener("click", () => {
    if (!currentReport){ showToast("No report", "Generate a report first.", "warning"); return; }
    const title = document.getElementById("reportTitle").textContent || "Report";
    const filenameBase = title.replace(/\s+/g,"_");

    document.getElementById("reportPdfTitle").textContent = title;
    document.getElementById("reportPdfMeta").textContent =
        `Generated on ${formatDate(new Date().toISOString())}` + reportMetaSuffix();
    document.getElementById("reportPdfTable").innerHTML =
        "<thead><tr>" + currentReport.head.map(h=>`<th>${h}</th>`).join("") + "</tr></thead>" +
        "<tbody>" + currentReport.rows.map(r => "<tr>" + r.map(c=>`<td>${escapeHtml(c)}</td>`).join("") + "</tr>").join("") + "</tbody>";

    showSpinner();
    html2pdf().set({
        margin: 10,
        filename: `${filenameBase}.pdf`,
        image: { type: "jpeg", quality: 0.98 },
        html2canvas: { scale: 2, useCORS: true, backgroundColor: "#ffffff" },
        jsPDF: { unit: "mm", format: "a4", orientation: "landscape" }
    }).from(document.getElementById("reportPdfArea")).save()
        .then(() => { hideSpinner(); showToast("Exported", `${filenameBase}.pdf generated successfully.`, "success"); })
        .catch(() => { hideSpinner(); showToast("PDF failed", "Could not generate the report PDF.", "error"); });
});

/* ==================== 11. SETTINGS ====================
   NOTE: there is no backend entity/endpoint for "company profile" (no
   CompanyController), and UserService.getProfile()/updateProfile() are
   stubs that always return null. These forms are therefore UI-only
   conveniences (logo/company letterhead used for invoice PDFs) and are
   NOT presented as if they were syncing to a MySQL table — none of the 7
   modules covered by this rewrite (Users, Distributors, Products,
   Categories, Shops, Invoices, Payments) are affected. */
const COMPANY_SETTINGS_KEY = "dms_company_settings_v1";

function loadCompanySettingsIntoForm(){
    // No-op when the admin pane isn't in the DOM (SS/DP session).
    if (!document.getElementById("companyName")) return;
    let saved = {};
    try{ saved = JSON.parse(localStorage.getItem(COMPANY_SETTINGS_KEY) || "{}"); }catch(e){ saved = {}; }
    // Cosmetic-only fields (no backend field for these yet) stay in localStorage.
    document.getElementById("companyPan").value = saved.pan || "";
    document.getElementById("companyWebsite").value = saved.website || "";
    document.getElementById("companyBankName").value = saved.bankName || "";
    document.getElementById("companyBankBranch").value = saved.bankBranch || "";
    document.getElementById("companyBankAccount").value = saved.bankAccount || "";
    document.getElementById("companyBankIfsc").value = saved.bankIfsc || "";
    document.getElementById("companyUpiId").value = saved.upiId || "";
    document.getElementById("companyPaymentTermsDays").value = saved.paymentTermsDays || 10;
    if (saved.terms) document.getElementById("companyTerms").value = saved.terms;
    if (saved.logo) document.getElementById("companyLogoPreview").src = saved.logo;

    // Name/GST/FSSAI/Address/City/State/Pincode/Phone/Email are the real
    // seller details printed on Company -> Super Stockist invoices — these
    // load from the backend (CompanySettingsController), not localStorage,
    // so what Admin sees here always matches what actually prints.
    apiRequest(ENDPOINTS.companySettings).then(res => {
        const data = res && res.data;
        if (!data) return;
        document.getElementById("companyName").value = data.companyName || "";
        document.getElementById("companyGst").value = data.gstNumber || "";
        document.getElementById("companyFssai").value = data.fssaiNumber || "";
        document.getElementById("companyEmail").value = data.email || "";
        document.getElementById("companyPhone").value = data.phone || "";
        document.getElementById("companyAddress").value = data.address || "";
        document.getElementById("companyCity").value = data.city || "";
        document.getElementById("companyState").value = data.state || "";
        document.getElementById("companyPincode").value = data.pincode || "";
    }).catch(() => {
        // Read-only fallback: leave whatever was already in the fields
        // (e.g. the HTML defaults) rather than blocking the rest of Settings.
    });
}

/* ---------- Role-aware Settings ----------
   Company settings (logo, GST/PAN, bank, UPI, invoice terms) are ADMIN-only
   configuration. SS/DP get a personal account page instead.

   settingsApplyRole() REMOVES the non-applicable pane from the DOM rather
   than hiding it with CSS, so an SS/DP browser session never holds the
   company fields at all -- nothing to un-hide with devtools, and no stray
   company inputs for a stale submit handler to read.

   NOTE ON BACKEND ENFORCEMENT: there is deliberately no new API here.
   Company settings in this project are browser-local only (see
   COMPANY_SETTINGS_KEY below) -- there is no company table, entity or
   endpoint to authorize, so there is no admin company API for an SS/DP to
   call. Personal settings reuse the EXISTING, already role-safe endpoints:
   PUT /users/profile and PATCH /users/me/settings, both of which resolve
   the target user from the caller's own JWT and so can only ever read or
   write the caller's own record. */
function settingsApplyRole(){
    const user = getCurrentUser();
    const isAdmin = isAdminRoleName(user && user.role);

    const adminPane = document.getElementById("settingsAdminPane");
    const personalPane = document.getElementById("settingsPersonalPane");
    const subtitle = document.getElementById("settingsSubtitle");

    if (isAdmin){
        personalPane?.remove();
        if (subtitle) subtitle.textContent = "Configure company profile and preferences.";
        loadCompanySettingsIntoForm();
    }else{
        adminPane?.remove();
        if (subtitle) subtitle.textContent = "Personalize your account and preferences.";
        loadPersonalSettings();
    }
}

async function loadPersonalSettings(){
    // Fetch the full record rather than reading the cached login payload:
    // LoginResponse does NOT include mobileNumber, so populating from the
    // cache alone left Mobile Number permanently blank for SS/DP.
    // GET /users/profile resolves the user from their own JWT, so it can
    // only ever return the caller's own record.
    let user = getCurrentUser() || {};
    try{
        const fresh = unwrap(await apiRequest(ENDPOINTS.users + "/profile"), null);
        if (fresh){
            user = { ...user, ...fresh };
            setCurrentUser(user);
        }
    }catch(err){
        // Fall back to the cached values -- the page still renders.
    }
    const set = (id, val) => { const el = document.getElementById(id); if (el) el.value = val || ""; };
    set("meFullName", user.fullName);
    set("meUsername", user.username);
    set("meEmail", user.email);
    set("meMobile", user.mobileNumber);
    set("meRole", user.role);
    const avatar = document.getElementById("meAvatarPreview");
    if (avatar) avatar.src = user.profileImage || avatarUrl(user.fullName || user.username || "User");
    const theme = document.getElementById("meTheme");
    if (theme) theme.value = document.body.classList.contains("dark-mode") ? "dark" : "light";
    const font = document.getElementById("meFontSize");
    if (font) font.value = user.fontSizePreference || "medium";
}

document.getElementById("meSaveProfileBtn")?.addEventListener("click", async () => {
    const payload = {
        fullName: document.getElementById("meFullName").value.trim(),
        email: document.getElementById("meEmail").value.trim(),
        mobileNumber: document.getElementById("meMobile").value.trim(),
    };
    if (!payload.fullName){ showToast("Name required", "Full name cannot be blank.", "error"); return; }
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.users + "/profile", { method: "PUT", body: payload });
        const updated = unwrap(res, null);
        if (updated) setCurrentUser({ ...(getCurrentUser() || {}), ...updated });
        else setCurrentUser({ ...(getCurrentUser() || {}), ...payload });
        document.getElementById("topbarUserName").textContent = payload.fullName;
        showToast("Saved", "Your profile has been updated.", "success");
    }catch(err){
        showApiError(err, "Could not save your profile");
    }finally{
        hideSpinner();
    }
});

document.getElementById("meChangePasswordBtn")?.addEventListener("click", async () => {
    const currentPassword = document.getElementById("meCurrentPassword").value;
    const password = document.getElementById("meNewPassword").value;
    const confirm = document.getElementById("meConfirmPassword").value;

    if (!currentPassword || !password){ showToast("Missing fields", "Enter your current and new password.", "error"); return; }
    if (password !== confirm){ showToast("Mismatch", "New passwords do not match.", "error"); return; }

    showSpinner();
    try{
        // Same PUT /users/profile endpoint -- it requires currentPassword
        // whenever a new password is supplied (see UserService.updateProfile),
        // so a hijacked session alone can't take the account over.
        await apiRequest(ENDPOINTS.users + "/profile", { method: "PUT", body: {
            fullName: document.getElementById("meFullName").value.trim(),
            email: document.getElementById("meEmail").value.trim(),
            mobileNumber: document.getElementById("meMobile").value.trim(),
            currentPassword, password,
        }});
        ["meCurrentPassword","meNewPassword","meConfirmPassword"].forEach(id => document.getElementById(id).value = "");
        showToast("Password changed", "Your password has been updated.", "success");
    }catch(err){
        showApiError(err, "Could not change your password");
    }finally{
        hideSpinner();
    }
});

document.getElementById("meTheme")?.addEventListener("change", async (e) => {
    const dark = e.target.value === "dark";
    document.body.classList.toggle("dark-mode", dark);
    localStorage.setItem("brisk_dark", dark ? "1" : "0");
    try{ await apiRequest(ENDPOINTS.users + "/me/settings", { method:"PATCH", body:{ themePreference: e.target.value } }); }catch(err){ /* local toggle still applied */ }
});

document.getElementById("meFontSize")?.addEventListener("change", async (e) => {
    try{ await apiRequest(ENDPOINTS.users + "/me/settings", { method:"PATCH", body:{ fontSizePreference: e.target.value } }); }
    catch(err){ showApiError(err, "Could not save font size"); }
});

document.getElementById("meAvatarInput")?.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async (ev) => {
        const dataUrl = ev.target.result;
        document.getElementById("meAvatarPreview").src = dataUrl;
        try{
            await apiRequest(ENDPOINTS.users + "/me/settings", { method:"PATCH", body:{ profileImage: dataUrl } });
            document.getElementById("topbarAvatar").src = dataUrl;
            setCurrentUser({ ...(getCurrentUser() || {}), profileImage: dataUrl });
            showToast("Saved", "Profile photo updated.", "success");
        }catch(err){ showApiError(err, "Could not save photo"); }
    };
    reader.readAsDataURL(file);
});


document.getElementById("logoUploadInput")?.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
        document.getElementById("companyLogoPreview").src = ev.target.result;
        let saved = {};
        try{ saved = JSON.parse(localStorage.getItem(COMPANY_SETTINGS_KEY) || "{}"); }catch(err){ saved = {}; }
        saved.logo = ev.target.result;
        localStorage.setItem(COMPANY_SETTINGS_KEY, JSON.stringify(saved));
    };
    reader.readAsDataURL(file);
    showToast("Logo updated", "Company logo saved for invoice PDFs.", "success");
});

document.getElementById("companyForm")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    // Cosmetic-only fields (no backend column yet) — kept in localStorage.
    const cosmetic = {
        pan: document.getElementById("companyPan").value,
        website: document.getElementById("companyWebsite").value,
        bankName: document.getElementById("companyBankName").value,
        bankBranch: document.getElementById("companyBankBranch").value,
        bankAccount: document.getElementById("companyBankAccount").value,
        bankIfsc: document.getElementById("companyBankIfsc").value,
        upiId: document.getElementById("companyUpiId").value,
        paymentTermsDays: Number(document.getElementById("companyPaymentTermsDays").value) || 10,
        terms: document.getElementById("companyTerms").value,
        logo: document.getElementById("companyLogoPreview").src,
    };
    localStorage.setItem(COMPANY_SETTINGS_KEY, JSON.stringify(cosmetic));

    // Real seller details — persisted server-side so every invoice PDF
    // (generated on the backend) picks these up immediately.
    try{
        await apiRequest(ENDPOINTS.companySettings, {
            method: "PUT",
            body: {
                companyName: document.getElementById("companyName").value,
                gstNumber: document.getElementById("companyGst").value,
                fssaiNumber: document.getElementById("companyFssai").value,
                email: document.getElementById("companyEmail").value,
                phone: document.getElementById("companyPhone").value,
                address: document.getElementById("companyAddress").value,
                city: document.getElementById("companyCity").value,
                state: document.getElementById("companyState").value,
                pincode: document.getElementById("companyPincode").value,
            },
        });
        showToast("Saved", "Company details saved. Every new invoice will use this from now on.", "success");
    }catch(err){
        showToast("Save failed", err.message || "Could not save company details.", "error");
    }
});
document.getElementById("profileForm").addEventListener("submit", (e) => {
    e.preventDefault();
    const user = getCurrentUser() || {};
    user.fullName = document.getElementById("profileFullName").value;
    user.email = document.getElementById("profileEmail").value;
    setCurrentUser(user);
    document.getElementById("topbarUserName").textContent = user.fullName;
    showToast("Profile updated locally", "The backend's profile endpoint doesn't persist changes yet — this updated your session display only.", "warning");
});
document.getElementById("changePasswordForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const newPass = document.getElementById("newPassword").value;
    const confirmPass = document.getElementById("confirmNewPassword").value;
    if (!newPass || newPass !== confirmPass){ showToast("Password mismatch", "New password and confirmation must match.", "error"); return; }
    const user = getCurrentUser();
    if (!user || !user.id){
        showToast("Can't change password", "Your session doesn't have a user ID on record — please sign in again.", "error");
        return;
    }
    showSpinner();
    try{
        // BUG-H10 fix: the new password now travels in the JSON request
        // body (matching ResetPasswordByAdminRequest's "password" field)
        // instead of a ?password= query string, which used to end up in
        // server access logs / browser history / proxy logs.
        await apiRequest(ENDPOINTS.users + "/" + user.id + "/reset-password", { method:"PATCH", body:{ password: newPass } });
        e.target.reset();
        showToast("Password changed", "Your password has been updated successfully.", "success");
    }catch(err){
        showApiError(err, "Password change failed");
    }finally{
        hideSpinner();
    }
});

function loadProfileIntoSettings(){
    const user = getCurrentUser();
    if (!user) return;
    // BUG fix: this used to call document.getElementById(...).value directly,
    // which threw (and aborted the REST of loadAllModules -- categories,
    // products, shops, invoices, payments, notifications -- since this is
    // the first statement in that async function) for any non-admin login.
    // settingsApplyRole() removes #settingsAdminPane (which contains these
    // profile* fields) for non-admin roles, so these elements are only
    // ever present in the DOM for an admin session. Guard each assignment
    // the same way loadPersonalSettings()'s set() helper already does.
    const set = (id, val) => { const el = document.getElementById(id); if (el) el.value = val || ""; };
    set("profileFullName", user.fullName);
    set("profileUsername", user.username);
    set("profileEmail", user.email);
    set("darkModeSelect", user.themePreference || "light");
    set("fontSizeSelect", user.fontSizePreference || "medium");
    const photoPreview = document.getElementById("profilePhotoPreview");
    const photoPlaceholder = document.getElementById("profilePhotoPlaceholder");
    if (photoPreview && photoPlaceholder){
        if (user.profileImage){
            photoPreview.src = user.profileImage;
            photoPreview.style.display = "inline-block";
            photoPlaceholder.style.display = "none";
        } else {
            photoPreview.style.display = "none";
            photoPlaceholder.style.display = "inline-block";
        }
    }
    applyDisplayPreferences(user.themePreference || "light", user.fontSizePreference || "medium");
}

// Page 12: Dark/Light mode + Font Size, actually persisted server-side
// (PATCH /api/v1/users/me/settings) rather than just held in the DOM.
function applyDisplayPreferences(mode, fontSize){
    document.body.classList.toggle("dark-mode", mode === "dark");
    document.body.classList.remove("font-small","font-medium","font-large");
    document.body.classList.add("font-" + (fontSize || "medium"));
}

document.getElementById("saveDisplayPrefsBtn").addEventListener("click", async () => {
    const themePreference = document.getElementById("darkModeSelect").value;
    const fontSizePreference = document.getElementById("fontSizeSelect").value;
    showSpinner();
    try{
        const res = await apiRequest(ENDPOINTS.users + "/me/settings", { method:"PATCH", body: { themePreference, fontSizePreference } });
        const updated = unwrap(res, {});
        const user = getCurrentUser();
        setCurrentUser({ ...user, themePreference: updated.themePreference, fontSizePreference: updated.fontSizePreference });
        applyDisplayPreferences(updated.themePreference, updated.fontSizePreference);
        showToast("Preferences saved", "Your display preferences have been updated.", "success");
    }catch(err){
        showApiError(err, "Could not save preferences");
    }finally{
        hideSpinner();
    }
});

document.getElementById("profilePhotoInput").addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024){
        showToast("Photo too large", "Please choose an image under 2MB.", "warning");
        return;
    }
    const reader = new FileReader();
    reader.onload = async () => {
        const dataUrl = reader.result;
        showSpinner();
        try{
            const res = await apiRequest(ENDPOINTS.users + "/me/settings", { method:"PATCH", body: { profileImage: dataUrl } });
            const updated = unwrap(res, {});
            const user = getCurrentUser();
            setCurrentUser({ ...user, profileImage: updated.profileImage });
            document.getElementById("profilePhotoPreview").src = updated.profileImage;
            document.getElementById("profilePhotoPreview").style.display = "inline-block";
            document.getElementById("profilePhotoPlaceholder").style.display = "none";
            document.getElementById("topbarAvatar").src = updated.profileImage;
            showToast("Photo updated", "Your profile photo has been updated.", "success");
        }catch(err){
            showApiError(err, "Could not upload photo");
        }finally{
            hideSpinner();
        }
    };
    reader.readAsDataURL(file);
});

/* ==================== 12. INIT ==================== */
async function loadAllModules(){
    loadProfileIntoSettings();

    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    const isAdminRole = role === "ADMIN" || role === "SUPER_ADMIN";

    // Distributors and Users are ADMIN-only on the backend (@PreAuthorize),
    // so a DISTRIBUTOR login must never call them — doing so always came
    // back as an error toast even though the sidebar correctly hides those
    // sections for this role.
    await loadAndRenderCrud("categories");
    if (isAdminRole) await loadAndRenderCrud("distributors");
    await Promise.all([
        isAdminRole ? loadAndRenderCrud("users") : Promise.resolve(),
        loadAndRenderCrud("products"),
        loadAndRenderCrud("shops"),
    ]);
    await loadAndRenderInvoices();
    await loadAndRenderPayments();
    loadAndRenderNotificationBell();
}

checkSession();

/* ==================== STOCK SUMMARY ==================== */
function debounce(fn, delay){
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), delay); };
}
function formatQty(v){
    const n = Number(v) || 0;
    return n % 1 === 0 ? n.toLocaleString("en-IN") : n.toLocaleString("en-IN", { maximumFractionDigits: 3 });
}

async function loadAndRenderStockSummary(){
    const tbody = document.getElementById("stockSummaryTableBody");
    const tfoot = document.getElementById("stockSummaryTableFoot");
    tbody.innerHTML = `<tr><td colspan="14" class="text-center text-muted py-4">Loading...</td></tr>`;
    tfoot.innerHTML = "";

    const fromInput = document.getElementById("stockSummaryFromDate");
    const toInput = document.getElementById("stockSummaryToDate");
    if (!fromInput.value){
        const d = new Date(); d.setDate(1);
        fromInput.value = d.toISOString().slice(0, 10);
    }
    if (!toInput.value){
        toInput.value = new Date().toISOString().slice(0, 10);
    }

    const params = buildStockSummaryParams();

    try {
        const res = await apiRequest(`${API_BASE}/stock-summary?${params.toString()}`);
        const data = unwrap(res, { rows: [] });
        const rows = data.rows || [];

        if (!rows.length){
            tbody.innerHTML = `<tr><td colspan="14" class="text-center text-muted py-4">No stock movement in this range.</td></tr>`;
            return;
        }

        tbody.innerHTML = rows.map(r => `
            <tr>
                <td class="fw-600">${escapeHtml(r.productName ?? "-")}</td>
                <td>${escapeHtml(r.unit ?? "-")}</td>
                <td class="text-end">${formatQty(r.openingQty)}</td>
                <td class="text-end">${formatCurrency(r.openingRate)}</td>
                <td class="text-end">${formatCurrency(r.openingValue)}</td>
                <td class="text-end">${formatQty(r.inwardQty)}</td>
                <td class="text-end">${formatCurrency(r.inwardRate)}</td>
                <td class="text-end">${formatCurrency(r.inwardValue)}</td>
                <td class="text-end">${formatQty(r.outwardQty)}</td>
                <td class="text-end">${formatCurrency(r.outwardRate)}</td>
                <td class="text-end">${formatCurrency(r.outwardValue)}</td>
                <td class="text-end">${formatQty(r.closingQty)}</td>
                <td class="text-end">${formatCurrency(r.closingRate)}</td>
                <td class="text-end fw-600">${formatCurrency(r.closingValue)}</td>
            </tr>
        `).join("");

        tfoot.innerHTML = `
            <tr class="fw-700">
                <td colspan="4">GRAND TOTAL</td>
                <td class="text-end">${formatCurrency(data.grandTotalOpeningValue)}</td>
                <td colspan="2"></td>
                <td class="text-end">${formatCurrency(data.grandTotalInwardValue)}</td>
                <td colspan="2"></td>
                <td class="text-end">${formatCurrency(data.grandTotalOutwardValue)}</td>
                <td colspan="2"></td>
                <td class="text-end">${formatCurrency(data.grandTotalClosingValue)}</td>
            </tr>
        `;
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="14" class="text-center text-danger py-4">Failed to load stock summary.</td></tr>`;
    }
}

function buildStockSummaryParams(){
    const params = new URLSearchParams();
    const from = document.getElementById("stockSummaryFromDate").value;
    const to = document.getElementById("stockSummaryToDate").value;
    const categoryId = document.getElementById("stockSummaryCategoryFilter").value;
    const search = document.getElementById("stockSummarySearch").value.trim();
    if (from) params.set("fromDate", from);
    if (to) params.set("toDate", to);
    if (categoryId) params.set("categoryId", categoryId);
    if (search) params.set("search", search);
    return params;
}

document.getElementById("stockSummaryApplyBtn")?.addEventListener("click", loadAndRenderStockSummary);
document.getElementById("stockSummarySearch")?.addEventListener("input", debounce(loadAndRenderStockSummary, 400));
document.getElementById("stockSummaryCategoryFilter")?.addEventListener("change", loadAndRenderStockSummary);

document.getElementById("stockSummaryExportExcel")?.addEventListener("click", () => {
    const params = buildStockSummaryParams();
    apiDownloadFile(`${API_BASE}/stock-summary/export/excel?${params.toString()}`, "stock-summary.xlsx");
});
document.getElementById("stockSummaryExportPdf")?.addEventListener("click", () => {
    const params = buildStockSummaryParams();
    apiDownloadFile(`${API_BASE}/stock-summary/export/pdf?${params.toString()}`, "stock-summary.pdf");
});

/* ==================== PRODUCT LEDGER (Admin) ==================== */
/**
 * Read-only view over GET /api/v1/product-ledger/product/{id}. There is
 * no create/edit/delete UI here on purpose — every row is written
 * automatically by InvoiceService, SalesReturnService, ProductService and
 * ProductRequestService the moment the underlying stock actually moves.
 */
let PRODUCT_LEDGER_PAGE = 0;

function formatDateTime(d){
    if (!d) return "--";
    const date = new Date(d);
    if (isNaN(date.getTime())) return "--";
    return date.toLocaleString("en-IN", { day:"2-digit", month:"short", year:"numeric", hour:"2-digit", minute:"2-digit" });
}

function ledgerTypeLabel(type){
    const labels = {
        OPENING_STOCK: "Opening Stock",
        PURCHASE: "Purchase",
        SALES: "Sales",
        SALES_RETURN: "Sales Return",
        STOCK_TRANSFER_IN: "Transfer In",
        STOCK_TRANSFER_OUT: "Transfer Out",
        STOCK_ADJUSTMENT: "Adjustment",
        DAMAGE_LOSS: "Damage/Loss",
        MANUAL_STOCK_CORRECTION: "Manual Correction",
    };
    return labels[type] || (type || "-");
}

function ledgerTypeBadgeClass(type){
    if (type === "SALES" || type === "STOCK_TRANSFER_OUT" || type === "DAMAGE_LOSS") return "status-badge status-unpaid";
    if (type === "OPENING_STOCK" || type === "SALES_RETURN" || type === "STOCK_TRANSFER_IN" || type === "PURCHASE") return "status-badge status-paid";
    return "status-badge status-partial";
}

function populateProductLedgerSelectors(){
    fillSelectPreserving("productLedgerProductSelect", `<option value="">Select product...</option>`,
        STATE.products, p => p.id,
        p => p.productName + (p.productCode ? " (" + p.productCode + ")" : ""));
    const locSelect = document.getElementById("productLedgerLocationSelect");
    if (locSelect && locSelect.dataset.populated !== "1"){
        const ssOptions = (STATE.superStockists || []).map(s => `<option value="ss:${s.id}">Super Stockist: ${escapeHtml(s.superStockistName)}</option>`).join("");
        const distOptions = (STATE.distributors || []).map(d => `<option value="dist:${d.id}">Distributor: ${escapeHtml(d.distributorName)}</option>`).join("");
        locSelect.innerHTML = `<option value="">All Locations (full history)</option>${ssOptions}${distOptions}`;
        locSelect.dataset.populated = "1";
    }
}

async function loadAndRenderProductLedger(){
    if (!STATE.superStockists || !STATE.superStockists.length){
        await loadAndRenderCrud("superstockists");
    }
    populateProductLedgerSelectors();
}

function renderLedgerRows(tbodyId, rows, showProductColumn){
    const tbody = document.getElementById(tbodyId);
    if (!rows.length){
        tbody.innerHTML = `<tr><td colspan="12" class="text-center text-muted py-4">No ledger entries found.</td></tr>`;
        return;
    }
    tbody.innerHTML = rows.map(r => `
        <tr>
            <td>${formatDateTime(r.transactionDateTime)}</td>
            <td>${escapeHtml(r.voucherNo ?? "-")}</td>
            <td><span class="${ledgerTypeBadgeClass(r.transactionType)}">${escapeHtml(ledgerTypeLabel(r.transactionType))}</span></td>
            ${showProductColumn ? `<td class="fw-600">${escapeHtml(r.productName ?? "-")}</td>` : ""}
            <td>${escapeHtml(r.batchNo ?? "-")}</td>
            ${showProductColumn ? "" : `<td>${escapeHtml(r.locationName ?? r.ownerType ?? "-")}</td>`}
            <td class="text-end">${Number(r.inQuantity) > 0 ? formatQty(r.inQuantity) : "-"}</td>
            <td class="text-end">${Number(r.outQuantity) > 0 ? formatQty(r.outQuantity) : "-"}</td>
            <td class="text-end fw-600">${formatQty(r.balanceQuantity)}</td>
            <td class="text-end">${formatCurrency(r.unitCost)}</td>
            <td class="text-end">${formatCurrency(r.stockValue)}</td>
            <td>${escapeHtml(r.performedBy ?? "-")}</td>
            <td class="small text-muted">${escapeHtml(r.remarks ?? "-")}</td>
        </tr>
    `).join("");
}

async function loadProductLedgerData(page = 0){
    const productId = document.getElementById("productLedgerProductSelect").value;
    const tbody = document.getElementById("productLedgerTableBody");
    if (!productId){
        tbody.innerHTML = `<tr><td colspan="12" class="text-center text-muted py-4">Select a product and click "Show Ledger".</td></tr>`;
        return;
    }
    PRODUCT_LEDGER_PAGE = page;
    tbody.innerHTML = `<tr><td colspan="12" class="text-center text-muted py-4">Loading...</td></tr>`;

    const locationValue = document.getElementById("productLedgerLocationSelect").value;
    const params = new URLSearchParams();
    params.set("page", page);
    params.set("size", 50);
    if (locationValue.startsWith("ss:")) params.set("superStockistId", locationValue.split(":")[1]);
    else if (locationValue.startsWith("dist:")) params.set("distributorId", locationValue.split(":")[1]);

    try {
        const res = await apiRequest(`${API_BASE}/product-ledger/product/${productId}?${params.toString()}`);
        const data = unwrap(res, { content: [], totalPages: 0 });
        renderLedgerRows("productLedgerTableBody", data.content || [], false);
        renderPagination("productLedgerPagination", data.totalPages || 0, page + 1, (p) => loadProductLedgerData(p - 1));
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="12" class="text-center text-danger py-4">Failed to load product ledger.</td></tr>`;
    }
}

document.getElementById("productLedgerLoadBtn")?.addEventListener("click", () => loadProductLedgerData(0));
document.getElementById("productLedgerProductSelect")?.addEventListener("change", () => loadProductLedgerData(0));
document.getElementById("productLedgerLocationSelect")?.addEventListener("change", () => loadProductLedgerData(0));

/* ==================== MY STOCK LEDGER (Super Stockist / Distributor) ==================== */
let MY_STOCK_LEDGER_PAGE = 0;

async function loadMyStockLedgerData(page = 0){
    MY_STOCK_LEDGER_PAGE = page;
    const tbody = document.getElementById("myStockLedgerTableBody");
    tbody.innerHTML = `<tr><td colspan="12" class="text-center text-muted py-4">Loading...</td></tr>`;
    try {
        const res = await apiRequest(`${API_BASE}/product-ledger/my-location?page=${page}&size=50`);
        const data = unwrap(res, { content: [], totalPages: 0 });
        renderLedgerRows("myStockLedgerTableBody", data.content || [], true);
        renderPagination("myStockLedgerPagination", data.totalPages || 0, page + 1, (p) => loadMyStockLedgerData(p - 1));
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="12" class="text-center text-danger py-4">Failed to load stock ledger.</td></tr>`;
    }
}

async function loadAndRenderMyStockLedger(){
    await loadMyStockLedgerData(0);
}

/* ==================== SALES ANALYSIS ==================== */
/**
 * Party-wise / Product-wise / Category-wise, scoped to whichever slice of
 * the network the drill-down breadcrumb currently points at. All three
 * tabs share the same breadcrumb/scope — switching tabs never resets it,
 * only navigating the breadcrumb or re-opening the page does.
 */
const SALES_ANALYSIS_STATE = {
    tab: "party",
    // First entry is always the top-level view for the caller's role
    // (no ids). Each further entry is one drill-down click.
    path: [{ label: "All", superStockistId: null, distributorId: null }],
};

function salesAnalysisCurrentScope(){
    return SALES_ANALYSIS_STATE.path[SALES_ANALYSIS_STATE.path.length - 1];
}

function renderSalesAnalysisBreadcrumb(){
    const el = document.getElementById("salesAnalysisBreadcrumb");
    el.innerHTML = SALES_ANALYSIS_STATE.path.map((step, idx) => {
        const isLast = idx === SALES_ANALYSIS_STATE.path.length - 1;
        return `<li class="breadcrumb-item ${isLast ? "active" : ""}">${
            isLast ? escapeHtml(step.label) : `<a data-idx="${idx}">${escapeHtml(step.label)}</a>`
        }</li>`;
    }).join("");
    el.querySelectorAll("a[data-idx]").forEach(a => {
        a.addEventListener("click", () => {
            const idx = Number(a.dataset.idx);
            SALES_ANALYSIS_STATE.path = SALES_ANALYSIS_STATE.path.slice(0, idx + 1);
            loadSalesAnalysisActiveTab();
        });
    });
}

function drillIntoParty(partyId, partyName, partyType){
    if (partyType === "SHOP") return; // leaf — nothing further to drill into
    const scope = salesAnalysisCurrentScope();
    const nextStep = partyType === "SUPER_STOCKIST"
        ? { label: partyName, superStockistId: partyId, distributorId: null }
        : { label: partyName, superStockistId: scope.superStockistId, distributorId: partyId };
    SALES_ANALYSIS_STATE.path.push(nextStep);
    loadSalesAnalysisActiveTab();
}

function switchSalesAnalysisTab(tab){
    SALES_ANALYSIS_STATE.tab = tab;
    document.querySelectorAll("#salesAnalysisTabs [data-tab]").forEach(b => b.classList.toggle("active", b.dataset.tab === tab));
    document.getElementById("salesAnalysisTabParty").classList.toggle("d-none", tab !== "party");
    document.getElementById("salesAnalysisTabProduct").classList.toggle("d-none", tab !== "product");
    document.getElementById("salesAnalysisTabCategory").classList.toggle("d-none", tab !== "category");
    loadSalesAnalysisActiveTab();
}

document.querySelectorAll("#salesAnalysisTabs [data-tab]").forEach(btn => {
    btn.addEventListener("click", () => switchSalesAnalysisTab(btn.dataset.tab));
});

async function loadSalesAnalysisActiveTab(){
    renderSalesAnalysisBreadcrumb();
    const scope = salesAnalysisCurrentScope();
    const params = new URLSearchParams();
    if (scope.superStockistId) params.set("superStockistId", scope.superStockistId);
    if (scope.distributorId) params.set("distributorId", scope.distributorId);

    if (SALES_ANALYSIS_STATE.tab === "party") await loadSalesAnalysisParty(params);
    else if (SALES_ANALYSIS_STATE.tab === "product") await loadSalesAnalysisProduct(params);
    else await loadSalesAnalysisCategory(params);
}

async function loadSalesAnalysisParty(params){
    const tbody = document.getElementById("salesAnalysisPartyTableBody");
    tbody.innerHTML = `<tr><td colspan="5" class="text-center text-muted py-4">Loading...</td></tr>`;
    try {
        const res = await apiRequest(`${API_BASE}/sales-analysis/party-wise?${params.toString()}`);
        const rows = unwrap(res, []);
        if (!rows.length){
            tbody.innerHTML = `<tr><td colspan="5" class="text-center text-muted py-4">No sales in this view.</td></tr>`;
            return;
        }
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td>${r.partyType === "SHOP"
                    ? escapeHtml(r.partyName)
                    : `<span class="sales-analysis-party-link" data-id="${r.partyId}" data-name="${escapeHtml(r.partyName)}" data-type="${r.partyType}">${escapeHtml(r.partyName)}</span>`}</td>
                <td><span class="text-muted small">${escapeHtml(r.partyType)}</span></td>
                <td class="text-end">${r.invoiceCount ?? 0}</td>
                <td class="text-end fw-600">${formatCurrency(r.totalSales)}</td>
                <td class="text-end">${r.partyType !== "SHOP" ? `<i class="fa-solid fa-chevron-right text-muted"></i>` : ""}</td>
            </tr>
        `).join("");
        tbody.querySelectorAll(".sales-analysis-party-link").forEach(el => {
            el.addEventListener("click", () => drillIntoParty(Number(el.dataset.id), el.dataset.name, el.dataset.type));
        });
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="5" class="text-center text-danger py-4">Failed to load party-wise sales.</td></tr>`;
    }
}

async function loadSalesAnalysisProduct(params){
    const tbody = document.getElementById("salesAnalysisProductTableBody");
    tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted py-4">Loading...</td></tr>`;
    try {
        const res = await apiRequest(`${API_BASE}/sales-analysis/product-wise?${params.toString()}`);
        const rows = unwrap(res, []);
        if (!rows.length){
            tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted py-4">No sales in this view.</td></tr>`;
            return;
        }
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td class="fw-600">${escapeHtml(r.productName ?? "-")}</td>
                <td>${escapeHtml(r.categoryName ?? "-")}</td>
                <td class="text-end">${formatQty(r.quantitySold)}</td>
                <td class="text-end fw-600">${formatCurrency(r.totalRevenue)}</td>
            </tr>
        `).join("");
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="4" class="text-center text-danger py-4">Failed to load product-wise sales.</td></tr>`;
    }
}

async function loadSalesAnalysisCategory(params){
    const tbody = document.getElementById("salesAnalysisCategoryTableBody");
    tbody.innerHTML = `<tr><td colspan="3" class="text-center text-muted py-4">Loading...</td></tr>`;
    try {
        const res = await apiRequest(`${API_BASE}/sales-analysis/category-wise?${params.toString()}`);
        const rows = unwrap(res, []);
        if (!rows.length){
            tbody.innerHTML = `<tr><td colspan="3" class="text-center text-muted py-4">No sales in this view.</td></tr>`;
            return;
        }
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td class="fw-600">${escapeHtml(r.categoryName ?? "-")}</td>
                <td class="text-end">${formatQty(r.totalQuantity)}</td>
                <td class="text-end fw-600">${formatCurrency(r.totalSales)}</td>
            </tr>
        `).join("");
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="3" class="text-center text-danger py-4">Failed to load category-wise sales.</td></tr>`;
    }
}

async function loadAndRenderSalesAnalysis(){
    const user = getCurrentUser();
    const role = String((user && user.role) || "").toUpperCase().replace("ROLE_", "");
    let topLabel = "All Super Stockists";
    if (role === "SUPER_STOCKIST") topLabel = "My Distributors";
    else if (role === "DISTRIBUTOR") topLabel = "My Shops";

    SALES_ANALYSIS_STATE.path = [{ label: topLabel, superStockistId: null, distributorId: null }];
    SALES_ANALYSIS_STATE.tab = "party";
    document.querySelectorAll("#salesAnalysisTabs [data-tab]").forEach(b => b.classList.toggle("active", b.dataset.tab === "party"));
    document.getElementById("salesAnalysisTabParty").classList.remove("d-none");
    document.getElementById("salesAnalysisTabProduct").classList.add("d-none");
    document.getElementById("salesAnalysisTabCategory").classList.add("d-none");

    await loadSalesAnalysisActiveTab();
}

/* ---------------- Stock Entry (Inward) modal ---------------- */
document.getElementById("stockEntryBtn")?.addEventListener("click", () => {
    const select = document.getElementById("stockEntryProduct");
    select.innerHTML = `<option value="">Select product...</option>` +
        STATE.products.map(p => `<option value="${p.id}">${escapeHtml(p.productName)} (${escapeHtml(p.unit ?? "")})</option>`).join("");
    document.getElementById("stockEntryForm").reset();
    document.getElementById("stockEntryCurrentStock").textContent = "";
    new bootstrap.Modal(document.getElementById("stockEntryModal")).show();
});

document.getElementById("stockEntryProduct")?.addEventListener("change", (e) => {
    const product = STATE.products.find(p => p.id == e.target.value);
    const hint = document.getElementById("stockEntryCurrentStock");
    hint.textContent = product ? `Current stock: ${formatQty(product.stockQuantity)} ${product.unit ?? ""}` : "";
});

document.getElementById("stockEntryForm")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const productId = document.getElementById("stockEntryProduct").value;
    const quantity = document.getElementById("stockEntryQuantity").value;
    const rate = document.getElementById("stockEntryRate").value;
    const remarks = document.getElementById("stockEntryRemarks").value.trim();

    if (!productId){ showToast("Missing product", "Please select a product.", "warning"); return; }
    if (!quantity || Number(quantity) <= 0){ showToast("Invalid quantity", "Enter a quantity greater than zero.", "warning"); return; }

    const params = new URLSearchParams({ quantity });
    if (rate) params.set("rate", rate);
    if (remarks) params.set("remarks", remarks);

    const submitBtn = document.getElementById("stockEntrySubmitBtn");
    submitBtn.disabled = true;
    submitBtn.textContent = "Adding...";
    try {
        await apiRequest(`${API_BASE}/products/${productId}/stock-entry?${params.toString()}`, { method: "POST" });
        showToast("Stock added", "Inward stock entry recorded successfully.", "success");
        bootstrap.Modal.getInstance(document.getElementById("stockEntryModal"))?.hide();
        await loadAndRenderCrud("products");
        if (document.getElementById("section-stock-summary") && !document.getElementById("section-stock-summary").classList.contains("d-none")){
            loadAndRenderStockSummary();
        }
    } catch (err) {
        showToast("Failed to add stock", err.message || "Please try again.", "danger");
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "Add Stock";
    }
});
/* ==================== OUTSTANDING REPORT ==================== */
const outstandingState = { page: 0, size: 20, sortDir: "desc" };

function buildOutstandingParams(){
    const params = new URLSearchParams();
    const search = document.getElementById("outstandingSearch").value.trim();
    const partyType = document.getElementById("outstandingPartyTypeFilter").value;
    const district = document.getElementById("outstandingDistrictFilter").value;
    const sortBy = document.getElementById("outstandingSortBy").value;
    if (search) params.set("search", search);
    if (partyType) params.set("partyType", partyType);
    if (district) params.set("district", district);
    params.set("sortBy", sortBy);
    params.set("sortDir", outstandingState.sortDir);
    params.set("page", outstandingState.page);
    params.set("size", outstandingState.size);
    return params;
}

async function loadAndRenderOutstandingReport(){
    const tbody = document.getElementById("outstandingReportTableBody");
    const tfoot = document.getElementById("outstandingReportTableFoot");
    const pagination = document.getElementById("outstandingPagination");
    tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;

    try {
        const res = await apiRequest(`${API_BASE}/accounts/outstanding?${buildOutstandingParams().toString()}`);
        const data = unwrap(res, { content: [] });
        const rows = data.content || [];

        // District options accumulate across loads rather than being frozen
        // from the first page. These rows are ONE page of a paginated,
        // already-filtered result, so populating once from page 1 left any
        // district that only appears later permanently unselectable --
        // while rebuilding from scratch each time would shrink the list to
        // whatever the active filter already narrowed it to.
        OUTSTANDING_DISTRICTS_SEEN = new Set([
            ...OUTSTANDING_DISTRICTS_SEEN,
            ...rows.map(r => r.district).filter(Boolean),
        ]);
        fillSelectPreserving("outstandingDistrictFilter", `<option value="">All Districts</option>`,
            [...OUTSTANDING_DISTRICTS_SEEN].sort(), v => escapeHtml(v), v => v);

        if (!rows.length){
            tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">No outstanding balances found.</td></tr>`;
            tfoot.innerHTML = "";
            pagination.innerHTML = "";
            return;
        }

        tbody.innerHTML = rows.map(r => `
            <tr>
                <td class="fw-600">${escapeHtml(r.partyName ?? "-")}</td>
                <td><span class="badge bg-secondary-subtle text-secondary-emphasis">${escapeHtml(r.partyType ?? "-")}</span></td>
                <td>${escapeHtml(r.ownerOrContact ?? "-")}<br><small class="text-muted">${escapeHtml(r.mobileNumber ?? "")}</small></td>
                <td>${escapeHtml(r.district ?? "-")}</td>
                <td>${(r.products && r.products.length) ? escapeHtml(r.products.slice(0,3).join(", ")) + (r.products.length > 3 ? "…" : "") : "-"}</td>
                <td class="text-center">${r.pendingInvoiceCount ?? 0}</td>
                <td class="text-end fw-600 text-danger">${formatCurrency(r.totalOutstanding)}</td>
            </tr>
        `).join("");

        const totals = data.totals || {};
        tfoot.innerHTML = `
            <tr class="fw-700">
                <td colspan="5">GRAND TOTAL (all pages)</td>
                <td class="text-center">${totals.grandTotalPendingInvoices ?? 0}</td>
                <td class="text-end">${formatCurrency(totals.grandTotalOutstanding)}</td>
            </tr>
        `;

        renderOutstandingPagination(data);
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Failed to load outstanding report.</td></tr>`;
    }
}

function renderOutstandingPagination(data){
    const pagination = document.getElementById("outstandingPagination");
    const totalPages = data.totalPages || 1;
    const current = data.page || 0;
    if (totalPages <= 1){ pagination.innerHTML = ""; return; }

    let html = `<button class="btn btn-sm btn-outline-secondary" ${current === 0 ? "disabled" : ""} data-page="${current - 1}">Prev</button>`;
    html += `<span class="mx-2 small text-muted">Page ${current + 1} of ${totalPages} (${data.totalElements} total)</span>`;
    html += `<button class="btn btn-sm btn-outline-secondary" ${current >= totalPages - 1 ? "disabled" : ""} data-page="${current + 1}">Next</button>`;
    pagination.innerHTML = html;

    pagination.querySelectorAll("[data-page]").forEach(btn => {
        btn.addEventListener("click", () => {
            outstandingState.page = Number(btn.dataset.page);
            loadAndRenderOutstandingReport();
        });
    });
}

document.getElementById("outstandingSearch")?.addEventListener("input", debounce(() => { outstandingState.page = 0; loadAndRenderOutstandingReport(); }, 400));
document.getElementById("outstandingPartyTypeFilter")?.addEventListener("change", () => { outstandingState.page = 0; loadAndRenderOutstandingReport(); });
document.getElementById("outstandingDistrictFilter")?.addEventListener("change", () => { outstandingState.page = 0; loadAndRenderOutstandingReport(); });
document.getElementById("outstandingSortBy")?.addEventListener("change", () => { outstandingState.page = 0; loadAndRenderOutstandingReport(); });
document.getElementById("outstandingSortDirBtn")?.addEventListener("click", (e) => {
    outstandingState.sortDir = outstandingState.sortDir === "desc" ? "asc" : "desc";
    e.currentTarget.querySelector("i").className = outstandingState.sortDir === "desc" ? "fa-solid fa-arrow-down-wide-short" : "fa-solid fa-arrow-up-wide-short";
    outstandingState.page = 0;
    loadAndRenderOutstandingReport();
});
document.getElementById("outstandingPrintBtn")?.addEventListener("click", () => window.print());

function buildOutstandingExportParams(){
    const params = new URLSearchParams();
    const search = document.getElementById("outstandingSearch").value.trim();
    const partyType = document.getElementById("outstandingPartyTypeFilter").value;
    const district = document.getElementById("outstandingDistrictFilter").value;
    const sortBy = document.getElementById("outstandingSortBy").value;
    if (search) params.set("search", search);
    if (partyType) params.set("partyType", partyType);
    if (district) params.set("district", district);
    params.set("sortBy", sortBy);
    params.set("sortDir", outstandingState.sortDir);
    return params;
}
document.getElementById("outstandingExportCsv")?.addEventListener("click", () => {
    apiDownloadFile(`${API_BASE}/accounts/outstanding/export/csv?${buildOutstandingExportParams().toString()}`, "outstanding.csv");
});
document.getElementById("outstandingExportExcel")?.addEventListener("click", () => {
    apiDownloadFile(`${API_BASE}/accounts/outstanding/export/excel?${buildOutstandingExportParams().toString()}`, "outstanding.xlsx");
});
document.getElementById("outstandingExportPdf")?.addEventListener("click", () => {
    apiDownloadFile(`${API_BASE}/accounts/outstanding/export/pdf?${buildOutstandingExportParams().toString()}`, "outstanding.pdf");
});

/* ==================== LEDGER PAGES (Account / Customer / Supplier) ====================
   One generic controller factory reused for all three — they differ only
   in which party types populate the picker (Account Ledger: both, Customer
   Ledger: Shops only, Supplier Ledger: Distributors only) and their DOM id
   prefix. Search/voucher-type filter/pagination run client-side over the
   already-fetched statement (the backend call itself is per-account/date-
   range, so there's nothing further to page server-side). */
function ledgerRowClass(balanceType){
    return balanceType === "Cr" ? "text-success" : "text-dark";
}

function createLedgerController(prefix, partyTypeFilter){
    const el = (suffix) => document.getElementById(prefix + suffix);
    const state = { page: 0, size: 15, allEntries: [], ledgerData: null };

    async function loadPartyOptions(){
        const select = el("PartySelect");
        if (!select) return;
        try {
            const qs = partyTypeFilter ? `?partyType=${partyTypeFilter}` : "";
            const res = await apiRequest(`${API_BASE}/account-ledger/parties${qs}`);
            const parties = unwrap(res, []);
            const prev = select.value;
            select.innerHTML = `<option value="">Select ${partyTypeFilter === "SHOP" ? "customer" : partyTypeFilter === "DISTRIBUTOR" ? "distributor" : "account"}...</option>` +
                parties.map(p => `<option value="${p.partyType}:${p.id}">${escapeHtml(p.name)}${p.subtitle ? " — " + escapeHtml(p.subtitle) : ""}${partyTypeFilter ? "" : ` (${p.partyType === "SHOP" ? "Shop" : "Distributor"})`}</option>`).join("");
            select.value = prev;
        } catch (err) {
            showToast("Failed to load accounts", err.message || "Please try again.", "danger");
        }
    }

    function buildParams(){
        const [partyType, partyId] = (el("PartySelect").value || "").split(":");
        const params = new URLSearchParams();
        if (partyType) params.set("partyType", partyType);
        if (partyId) params.set("partyId", partyId);
        const from = el("FromDate").value;
        const to = el("ToDate").value;
        if (from) params.set("fromDate", from);
        if (to) params.set("toDate", to);
        return { params, partyId };
    }

    function applyClientFilters(){
        const search = (el("Search")?.value || "").trim().toLowerCase();
        const typeFilter = el("VoucherTypeFilter")?.value || "";
        let rows = state.allEntries;
        if (typeFilter) rows = rows.filter(e => e.voucherType === typeFilter);
        if (search) rows = rows.filter(e =>
            (e.voucherNo && e.voucherNo.toLowerCase().includes(search)) ||
            (e.narration && e.narration.toLowerCase().includes(search))
        );
        return rows;
    }

    function renderPage(){
        const tbody = el("TableBody");
        const filtered = applyClientFilters();
        const totalPages = Math.max(Math.ceil(filtered.length / state.size), 1);
        state.page = Math.min(state.page, totalPages - 1);
        const from = state.page * state.size;
        const pageRows = filtered.slice(from, from + state.size);

        if (!pageRows.length){
            tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">No matching transactions.</td></tr>`;
        } else {
            tbody.innerHTML = pageRows.map(eRow => `
                <tr class="ledger-row" style="cursor:pointer" data-voucher-type="${eRow.voucherType}" data-voucher-id="${eRow.voucherId}">
                    <td>${eRow.date ?? "-"}</td>
                    <td>${escapeHtml(eRow.voucherType ?? "-")}</td>
                    <td class="text-primary">${escapeHtml(eRow.voucherNo ?? "-")}</td>
                    <td>${escapeHtml(eRow.narration ?? "-")}</td>
                    <td class="text-end">${eRow.debit ? formatCurrency(eRow.debit) : ""}</td>
                    <td class="text-end">${eRow.credit ? formatCurrency(eRow.credit) : ""}</td>
                    <td class="text-end fw-600 ${ledgerRowClass(eRow.runningBalanceType)}">${formatCurrency(eRow.runningBalance)} ${escapeHtml(eRow.runningBalanceType ?? "")}</td>
                </tr>
            `).join("");
        }

        const pagination = el("Pagination");
        if (pagination){
            if (totalPages <= 1){ pagination.innerHTML = ""; }
            else {
                pagination.innerHTML = `
                    <button class="btn btn-sm btn-outline-secondary" ${state.page === 0 ? "disabled" : ""} data-dir="-1">Prev</button>
                    <span class="mx-2 small text-muted">Page ${state.page + 1} of ${totalPages} (${filtered.length} entries)</span>
                    <button class="btn btn-sm btn-outline-secondary" ${state.page >= totalPages - 1 ? "disabled" : ""} data-dir="1">Next</button>
                `;
                pagination.querySelectorAll("[data-dir]").forEach(btn => {
                    btn.addEventListener("click", () => { state.page += Number(btn.dataset.dir); renderPage(); });
                });
            }
        }
    }

    async function fetchAndRender(){
        const tbody = el("TableBody");
        const tfoot = el("TableFoot");
        const headerInfo = el("HeaderInfo");
        const { params, partyId } = buildParams();

        if (!partyId){
            showToast("Select an account", "Please choose an account first.", "warning");
            return;
        }

        const fromInput = el("FromDate");
        const toInput = el("ToDate");
        if (!fromInput.value){ const d = new Date(); d.setDate(1); fromInput.value = d.toISOString().slice(0,10); }
        if (!toInput.value){ toInput.value = new Date().toISOString().slice(0,10); }

        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Loading...</td></tr>`;
        tfoot.innerHTML = "";

        try {
            const res = await apiRequest(`${API_BASE}/account-ledger?${buildParams().params.toString()}`);
            const data = unwrap(res, {});
            state.ledgerData = data;
            state.allEntries = data.entries || [];
            state.page = 0;

            headerInfo.innerHTML = `Account: <strong>${escapeHtml(data.partyName ?? "-")}</strong>
                &nbsp;|&nbsp; ${data.fromDate} to ${data.toDate}
                &nbsp;|&nbsp; Opening Balance: <strong>${formatCurrency(data.openingBalance)} ${escapeHtml(data.openingBalanceType ?? "")}</strong>`;

            renderPage();

            tfoot.innerHTML = `
                <tr class="fw-700">
                    <td colspan="4">TOTAL</td>
                    <td class="text-end">${formatCurrency(data.totalDebit)}</td>
                    <td class="text-end">${formatCurrency(data.totalCredit)}</td>
                    <td class="text-end">Closing: ${formatCurrency(data.closingBalance)} ${escapeHtml(data.closingBalanceType ?? "")}</td>
                </tr>
            `;
        } catch (err) {
            tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger py-4">Failed to load ledger.</td></tr>`;
        }
    }

    el("ApplyBtn")?.addEventListener("click", fetchAndRender);
    el("PartySelect")?.addEventListener("change", fetchAndRender);
    el("PrintBtn")?.addEventListener("click", () => window.print());
    el("Search")?.addEventListener("input", debounce(() => { state.page = 0; renderPage(); }, 300));
    el("VoucherTypeFilter")?.addEventListener("change", () => { state.page = 0; renderPage(); });

    el("TableBody")?.addEventListener("click", (e) => {
        const row = e.target.closest(".ledger-row");
        if (!row) return;
        openVoucherDetails(row.dataset.voucherType, row.dataset.voucherId);
    });

    el("ExportCsv")?.addEventListener("click", () => {
        const { params, partyId } = buildParams();
        if (!partyId){ showToast("Select an account", "Please choose an account first.", "warning"); return; }
        apiDownloadFile(`${API_BASE}/account-ledger/export/csv?${params.toString()}`, "ledger.csv");
    });
    el("ExportExcel")?.addEventListener("click", () => {
        const { params, partyId } = buildParams();
        if (!partyId){ showToast("Select an account", "Please choose an account first.", "warning"); return; }
        apiDownloadFile(`${API_BASE}/account-ledger/export/excel?${params.toString()}`, "ledger.xlsx");
    });
    el("ExportPdf")?.addEventListener("click", () => {
        const { params, partyId } = buildParams();
        if (!partyId){ showToast("Select an account", "Please choose an account first.", "warning"); return; }
        apiDownloadFile(`${API_BASE}/account-ledger/export/pdf?${params.toString()}`, "ledger.pdf");
    });

    return {
        init: async () => {
            await loadPartyOptions();
            if (!el("PartySelect").value){
                el("TableBody").innerHTML = `<tr><td colspan="7" class="text-center text-muted py-4">Select an account and click "Show Ledger".</td></tr>`;
                el("TableFoot").innerHTML = "";
                el("HeaderInfo").textContent = "";
            }
        }
    };
}

const accountLedgerCtl = createLedgerController("ledger", null);
const customerLedgerCtl = createLedgerController("custLedger", "SHOP");
const supplierLedgerCtl = createLedgerController("suppLedger", "DISTRIBUTOR");

function loadAndRenderAccountLedger(){ return accountLedgerCtl.init(); }
function loadAndRenderCustomerLedger(){ return customerLedgerCtl.init(); }
function loadAndRenderSupplierLedger(){ return supplierLedgerCtl.init(); }

/** Busy-style voucher drill-down — shared by all three ledger pages above. */
async function openVoucherDetails(voucherType, voucherId){
    const title = document.getElementById("voucherDetailsTitle");
    const body = document.getElementById("voucherDetailsBody");
    title.textContent = voucherType === "Sale" ? "Sales Voucher" : voucherType === "SRet" ? "Sales Return Voucher" : "Receipt Voucher";
    body.innerHTML = `<div class="text-center text-muted py-4">Loading...</div>`;
    new bootstrap.Modal(document.getElementById("voucherDetailsModal")).show();

    try {
        if (voucherType === "Sale"){
            const res = await apiRequest(`${API_BASE}/invoices/${voucherId}`);
            const inv = unwrap(res, {});
            body.innerHTML = `
                <div class="d-flex justify-content-between mb-3">
                    <div><strong>Invoice #:</strong> ${escapeHtml(inv.invoiceNumber ?? "-")}</div>
                    <div><strong>Date:</strong> ${inv.invoiceDate ?? "-"}</div>
                </div>
                <div class="mb-3">
                    <strong>Party:</strong> ${escapeHtml(inv.shopName || inv.distributorName || inv.superStockistName || "-")}
                </div>
                <div class="table-responsive mb-3">
                    <table class="table table-sm">
                        <thead><tr><th>Item</th><th class="text-end">Qty</th><th class="text-end">Rate</th><th class="text-end">GST</th><th class="text-end">Amount</th></tr></thead>
                        <tbody>
                            ${(inv.items || []).map(it => `
                                <tr>
                                    <td>${escapeHtml(it.productName ?? "-")}</td>
                                    <td class="text-end">${it.quantity ?? 0} ${escapeHtml(it.unit ?? "")}</td>
                                    <td class="text-end">${formatCurrency(it.unitPrice)}</td>
                                    <td class="text-end">${formatCurrency(it.gstAmount)}</td>
                                    <td class="text-end">${formatCurrency(it.totalAmount)}</td>
                                </tr>
                            `).join("")}
                        </tbody>
                    </table>
                </div>
                <div class="d-flex justify-content-end">
                    <table class="table table-sm w-auto">
                        <tr><td class="text-muted">Sub Total</td><td class="text-end">${formatCurrency(inv.subTotal)}</td></tr>
                        <tr><td class="text-muted">Discount</td><td class="text-end">${formatCurrency(inv.discountAmount)}</td></tr>
                        <tr><td class="text-muted">Tax</td><td class="text-end">${formatCurrency(inv.taxAmount)}</td></tr>
                        <tr class="fw-700"><td>Total</td><td class="text-end">${formatCurrency(inv.totalAmount)}</td></tr>
                        <tr><td class="text-muted">Paid</td><td class="text-end">${formatCurrency(inv.paidAmount)}</td></tr>
                        <tr><td class="text-muted">Balance</td><td class="text-end">${formatCurrency(inv.balanceAmount)}</td></tr>
                        <tr><td class="text-muted">Status</td><td class="text-end">${escapeHtml(inv.paymentStatus ?? "-")}</td></tr>
                    </table>
                </div>
            `;
        } else if (voucherType === "SRet") {
            const res = await apiRequest(`${API_BASE}/sales-returns/${voucherId}`);
            const sr = unwrap(res, {});
            body.innerHTML = `
                <div class="row g-3">
                    <div class="col-6"><strong>Date:</strong> ${sr.returnDate ?? "-"}</div>
                    <div class="col-6"><strong>Amount:</strong> ${formatCurrency(sr.returnAmount)}</div>
                    <div class="col-6"><strong>Product:</strong> ${escapeHtml(sr.productName ?? "-")}</div>
                    <div class="col-6"><strong>Quantity:</strong> ${sr.quantity ?? 0}</div>
                    <div class="col-6"><strong>Against Invoice:</strong> ${escapeHtml(sr.invoiceNumber ?? "-")}</div>
                    <div class="col-6"><strong>Party (Shop):</strong> ${escapeHtml(sr.shopName ?? "-")}</div>
                    <div class="col-12"><strong>Reason:</strong> ${escapeHtml(sr.reason ?? "-")}</div>
                </div>
            `;
        } else {
            const res = await apiRequest(`${API_BASE}/payments/${voucherId}`);
            const pay = unwrap(res, {});
            body.innerHTML = `
                <div class="row g-3">
                    <div class="col-6"><strong>Date:</strong> ${pay.paymentDate ?? "-"}</div>
                    <div class="col-6"><strong>Amount:</strong> ${formatCurrency(pay.amount)}</div>
                    <div class="col-6"><strong>Method:</strong> ${escapeHtml(pay.paymentMethod ?? "-")}</div>
                    <div class="col-6"><strong>Status:</strong> ${escapeHtml(pay.paymentStatus ?? "-")}</div>
                    <div class="col-6"><strong>Against Invoice:</strong> ${escapeHtml(pay.invoiceNumber ?? "-")}</div>
                    <div class="col-6"><strong>Transaction ID:</strong> ${escapeHtml(pay.transactionId ?? "-")}</div>
                    <div class="col-6"><strong>Party:</strong> ${escapeHtml(pay.shopName || pay.distributorName || "-")}</div>
                    <div class="col-6"><strong>Verified:</strong> ${pay.verified ? "Yes" : "No"}</div>
                </div>
            `;
        }
    } catch (err) {
        body.innerHTML = `<div class="text-center text-danger py-4">Failed to load voucher details.</div>`;
    }
}

/* ==================== CASH BOOK / BANK BOOK / DAY BOOK ====================
   Same generic-controller pattern as the ledger pages — one factory, three
   instances (prefix + endpoint differ; Day Book has no running balance
   column since it spans every account). Pagination here IS server-side
   (unlike the ledger pages) since these can span the whole business. */
function createBookController(prefix, endpoint, hasRunningBalance){
    const el = (suffix) => document.getElementById(prefix + suffix);
    const state = { page: 0, size: 25 };

    function buildParams(){
        const params = new URLSearchParams();
        const from = el("FromDate").value;
        const to = el("ToDate").value;
        const search = el("Search").value.trim();
        if (from) params.set("fromDate", from);
        if (to) params.set("toDate", to);
        if (search) params.set("search", search);
        params.set("page", state.page);
        params.set("size", state.size);
        return params;
    }

    function renderPagination(data){
        const pagination = el("Pagination");
        const totalPages = data.totalPages || 1;
        if (totalPages <= 1){ pagination.innerHTML = ""; return; }
        pagination.innerHTML = `
            <button class="btn btn-sm btn-outline-secondary" ${data.page === 0 ? "disabled" : ""} data-dir="-1">Prev</button>
            <span class="mx-2 small text-muted">Page ${data.page + 1} of ${totalPages} (${data.totalElements} entries)</span>
            <button class="btn btn-sm btn-outline-secondary" ${data.page >= totalPages - 1 ? "disabled" : ""} data-dir="1">Next</button>
        `;
        pagination.querySelectorAll("[data-dir]").forEach(btn => {
            btn.addEventListener("click", () => { state.page += Number(btn.dataset.dir); load(); });
        });
    }

    async function load(){
        const tbody = el("TableBody");
        const tfoot = el("TableFoot");
        const headerInfo = el("HeaderInfo");
        const colspan = hasRunningBalance ? 8 : 7;

        const fromInput = el("FromDate");
        const toInput = el("ToDate");
        if (!fromInput.value){ const d = new Date(); d.setDate(1); fromInput.value = d.toISOString().slice(0,10); }
        if (!toInput.value){ toInput.value = new Date().toISOString().slice(0,10); }

        tbody.innerHTML = `<tr><td colspan="${colspan}" class="text-center text-muted py-4">Loading...</td></tr>`;
        tfoot.innerHTML = "";

        try {
            const res = await apiRequest(`${API_BASE}/accounts/${endpoint}?${buildParams().toString()}`);
            const data = unwrap(res, { entries: [] });
            const rows = data.entries || [];

            if (hasRunningBalance){
                headerInfo.innerHTML = `${data.fromDate} to ${data.toDate} &nbsp;|&nbsp; Opening Balance: <strong>${formatCurrency(data.openingBalance)}</strong>`;
            } else {
                headerInfo.innerHTML = `${data.fromDate} to ${data.toDate}`;
            }

            if (!rows.length){
                tbody.innerHTML = `<tr><td colspan="${colspan}" class="text-center text-muted py-4">No entries in this range.</td></tr>`;
            } else {
                tbody.innerHTML = rows.map(r => `
                    <tr class="ledger-row" style="cursor:pointer" data-voucher-type="${r.voucherType}" data-voucher-id="${r.voucherId}">
                        <td>${r.date ?? "-"}</td>
                        <td>${escapeHtml(r.voucherType ?? "-")}</td>
                        <td class="text-primary">${escapeHtml(r.voucherNo ?? "-")}</td>
                        <td>${escapeHtml(r.partyName ?? "-")}</td>
                        <td>${escapeHtml(r.paymentMethod ?? "-")}</td>
                        <td class="text-end">${r.debit ? formatCurrency(r.debit) : ""}</td>
                        <td class="text-end">${r.credit ? formatCurrency(r.credit) : ""}</td>
                        ${hasRunningBalance ? `<td class="text-end fw-600">${formatCurrency(r.runningBalance)}</td>` : ""}
                    </tr>
                `).join("");
            }

            tfoot.innerHTML = `
                <tr class="fw-700">
                    <td colspan="5">TOTAL</td>
                    <td class="text-end">${formatCurrency(data.totalDebit)}</td>
                    <td class="text-end">${formatCurrency(data.totalCredit)}</td>
                    ${hasRunningBalance ? `<td class="text-end">Closing: ${formatCurrency(data.closingBalance)}</td>` : ""}
                </tr>
            `;

            renderPagination(data);
        } catch (err) {
            tbody.innerHTML = `<tr><td colspan="${colspan}" class="text-center text-danger py-4">Failed to load.</td></tr>`;
        }
    }

    el("ApplyBtn")?.addEventListener("click", () => { state.page = 0; load(); });
    el("Search")?.addEventListener("input", debounce(() => { state.page = 0; load(); }, 400));
    el("PrintBtn")?.addEventListener("click", () => window.print());
    el("TableBody")?.addEventListener("click", (e) => {
        const row = e.target.closest(".ledger-row");
        if (!row || !row.dataset.voucherType) return;
        openVoucherDetails(row.dataset.voucherType, row.dataset.voucherId);
    });

    function buildExportParams(){
        const params = new URLSearchParams();
        const from = el("FromDate").value;
        const to = el("ToDate").value;
        const search = el("Search").value.trim();
        params.set("bookType", endpoint === "cash-book" ? "CASH" : endpoint === "bank-book" ? "BANK" : "DAY");
        if (from) params.set("fromDate", from);
        if (to) params.set("toDate", to);
        if (search) params.set("search", search);
        return params;
    }
    el("ExportCsv")?.addEventListener("click", () => apiDownloadFile(`${API_BASE}/accounts/books/export/csv?${buildExportParams().toString()}`, `${endpoint}.csv`));
    el("ExportExcel")?.addEventListener("click", () => apiDownloadFile(`${API_BASE}/accounts/books/export/excel?${buildExportParams().toString()}`, `${endpoint}.xlsx`));
    el("ExportPdf")?.addEventListener("click", () => apiDownloadFile(`${API_BASE}/accounts/books/export/pdf?${buildExportParams().toString()}`, `${endpoint}.pdf`));

    return { load };
}

const cashBookCtl = createBookController("cashBook", "cash-book", true);
const bankBookCtl = createBookController("bankBook", "bank-book", true);
const dayBookCtl = createBookController("dayBook", "day-book", false);

function loadAndRenderCashBook(){ return cashBookCtl.load(); }
function loadAndRenderBankBook(){ return bankBookCtl.load(); }
function loadAndRenderDayBook(){ return dayBookCtl.load(); }
