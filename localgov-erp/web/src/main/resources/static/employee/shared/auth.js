/**
 * ERP Frontend Auth Module (V194b.2)
 *
 * Single source of truth for authenticated user state across all employee pages.
 *
 * Backend auth payload (from POST /auth/login):
 *   {
 *     token: "eyJ...",
 *     roles: ["HEAD", "ROLE_HEAD", ...],
 *     dashboardIdentity: {
 *       employeeId, employeeCode, employeeName, email, department,
 *       positionId, positionTitle,
 *       authorityCode, authorityType, gender
 *     }
 *   }
 *
 * HR Onboarding owns position_title. Auth just carries it through to the UI.
 *
 * When council_position catalogue arrives (V194c/V196), only this module
 * needs to change — every page inherits the correct title for free.
 */
const ERP = (function() {
    const TOKEN_KEY = 'erp_token';
    const USER_KEY = 'erp_user';
    const ROLES_KEY = 'erp_roles';
    const LOGIN_URL = '/employee/login.html';

    // ---------------------------------------------------------------
    // Session management
    // ---------------------------------------------------------------
    function setSession(loginResponse) {
        localStorage.setItem(TOKEN_KEY, loginResponse.token || '');
        localStorage.setItem(USER_KEY, JSON.stringify(loginResponse.dashboardIdentity || {}));
        localStorage.setItem(ROLES_KEY, JSON.stringify(loginResponse.roles || []));
    }

    function clearSession() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        localStorage.removeItem(ROLES_KEY);
    }

    // ---------------------------------------------------------------
    // Current user
    // ---------------------------------------------------------------
    function currentUser(opts) {
        const redirectOnMissing = !opts || opts.redirect !== false;
        const token = localStorage.getItem(TOKEN_KEY);
        const userJson = localStorage.getItem(USER_KEY);
        const rolesJson = localStorage.getItem(ROLES_KEY);

        if (!token || !userJson) {
            if (redirectOnMissing) {
                window.location.href = LOGIN_URL;
            }
            return null;
        }

        let identity, roles;
        try {
            identity = JSON.parse(userJson || '{}');
            roles = JSON.parse(rolesJson || '[]');
        } catch (e) {
            if (redirectOnMissing) {
                clearSession();
                window.location.href = LOGIN_URL;
            }
            return null;
        }

        return {
            token,
            employeeId: identity.employeeId,
            employeeCode: identity.employeeCode,
            employeeName: identity.employeeName,
            email: identity.email,
            department: identity.department,
            positionId: identity.positionId,
            positionTitle: identity.positionTitle,
            authorityCode: identity.authorityCode,
            authorityType: identity.authorityType,
            gender: identity.gender,
            roles: roles,
            hasRole: (r) => roles.some(x => String(x).replace(/^ROLE_/, '') === r),
            hasAnyRole: (...rs) => rs.some(r => roles.some(x => String(x).replace(/^ROLE_/, '') === r))
        };
    }

    // ---------------------------------------------------------------
    // API wrapper
    // ---------------------------------------------------------------
    async function api(path, opts = {}) {
        const token = localStorage.getItem(TOKEN_KEY);
        const res = await fetch(path, {
            ...opts,
            headers: {
                'Authorization': 'Bearer ' + (token || ''),
                'Content-Type': 'application/json',
                ...(opts.headers || {})
            }
        });
        if (res.status === 401) {
            clearSession();
            window.location.href = LOGIN_URL;
            throw new Error('Unauthorized');
        }
        if (!res.ok) {
            const text = await res.text();
            throw new Error(text || ('HTTP ' + res.status));
        }
        const text = await res.text();
        return text ? JSON.parse(text) : {};
    }

    // ---------------------------------------------------------------
    // Display helpers
    // ---------------------------------------------------------------
    function userChipText(user) {
        if (!user) return 'Employee';
        const name = user.employeeName || user.employeeCode || 'Employee';
        const title = user.positionTitle || user.positionId || 'EMPLOYEE';
        return name + ' / ' + title;
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------
    return { setSession, clearSession, currentUser, api, userChipText };
})();
