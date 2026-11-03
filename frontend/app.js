// Signs the user in with authorization code and PKCE on the identity provider's own page, so this
// page never sees a password. The sign-in lives in sessionStorage, so it ends with the tab.
const TOKEN_KEY = "pjasensei.accessToken";
const PENDING_SIGN_IN_KEY = "pjasensei.pendingSignIn";
const REDIRECT_URI = `${location.origin}/`;

document.addEventListener("alpine:init", () => {
  Alpine.data("session", () => ({
    me: null,
    error: null,
    loading: true,
    classGroups: [],
    refreshFailures: [],
    refreshing: false,

    get isInstructor() {
      return this.me?.roles.includes("INSTRUCTOR");
    },

    async init() {
      try {
        await completeSignIn();
        this.me = await fetchMe();
        if (this.isInstructor) {
          this.classGroups = await fetchClassGroups();
        }
      } catch (error) {
        this.error = error.message;
      } finally {
        this.loading = false;
      }
    },

    async signIn() {
      this.error = null;
      try {
        await startSignIn();
      } catch (error) {
        this.error = error.message;
      }
    },

    // Forgets the token here only: with real Cognito, its own login session stays open.
    signOut() {
      sessionStorage.removeItem(TOKEN_KEY);
      this.me = null;
      this.error = null;
      this.classGroups = [];
      this.refreshFailures = [];
    },

    async refresh() {
      this.error = null;
      this.refreshing = true;
      try {
        const response = await callApi("/api/refresh", { method: "POST" });
        if (!response.ok) {
          throw new Error(`Refresh failed: Spring answered ${response.status}.`);
        }
        this.refreshFailures = (await response.json()).failures;
        this.classGroups = await fetchClassGroups();
      } catch (error) {
        this.error = error.message;
      } finally {
        this.refreshing = false;
      }
    },
  }));
});

async function startSignIn() {
  const { clientId, authorization_endpoint: authorizationEndpoint } = await identityProvider();

  const state = randomString();
  const codeVerifier = randomString();
  sessionStorage.setItem(PENDING_SIGN_IN_KEY, JSON.stringify({ state, codeVerifier }));

  const url = new URL(authorizationEndpoint);
  url.search = new URLSearchParams({
    response_type: "code",
    client_id: clientId,
    redirect_uri: REDIRECT_URI,
    scope: "openid",
    state,
    code_challenge: await challengeOf(codeVerifier),
    code_challenge_method: "S256",
  });
  location.assign(url);
}

// The identity provider sends the browser back here, with a code or an error in the query.
async function completeSignIn() {
  const params = new URLSearchParams(location.search);
  if (!params.has("code") && !params.has("error")) {
    return;
  }
  const pending = JSON.parse(sessionStorage.getItem(PENDING_SIGN_IN_KEY));
  sessionStorage.removeItem(PENDING_SIGN_IN_KEY);
  history.replaceState(null, "", location.pathname);

  if (params.has("error")) {
    throw new Error(`Sign-in failed: ${params.get("error_description") ?? params.get("error")}`);
  }
  if (pending?.state !== params.get("state")) {
    throw new Error("Sign-in failed: the answer does not belong to this tab. Please try again.");
  }

  const { clientId, token_endpoint: tokenEndpoint } = await identityProvider();
  const response = await fetch(tokenEndpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: clientId,
      code: params.get("code"),
      redirect_uri: REDIRECT_URI,
      code_verifier: pending.codeVerifier,
    }),
  });
  if (!response.ok) {
    throw new Error(`Sign-in failed: the identity provider answered ${response.status}.`);
  }
  sessionStorage.setItem(TOKEN_KEY, (await response.json()).access_token);
}

async function fetchMe() {
  const token = sessionStorage.getItem(TOKEN_KEY);
  if (!token) {
    return null;
  }
  const response = await fetch("/api/me", { headers: { Authorization: `Bearer ${token}` } });
  if (response.status === 401) {
    // Expired or no longer accepted: back to the sign-in button.
    sessionStorage.removeItem(TOKEN_KEY);
    return null;
  }
  if (!response.ok) {
    throw new Error(`Spring answered ${response.status}.`);
  }
  return response.json();
}

// An Instructor Refresh has not added yet is refused, and simply has nothing to show so far.
async function fetchClassGroups() {
  const response = await callApi("/api/class-groups");
  if (response.status === 403) {
    return [];
  }
  if (!response.ok) {
    throw new Error(`Spring answered ${response.status}.`);
  }
  return (await response.json()).classGroups;
}

function callApi(path, options = {}) {
  const token = sessionStorage.getItem(TOKEN_KEY);
  return fetch(path, { ...options, headers: { Authorization: `Bearer ${token}` } });
}

async function identityProvider() {
  const { issuer, clientId } = await getJson("/config.json");
  return { clientId, ...(await getJson(`${issuer}/.well-known/openid-configuration`)) };
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${url} answered ${response.status}.`);
  }
  return response.json();
}

function randomString() {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

async function challengeOf(codeVerifier) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(codeVerifier));
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
