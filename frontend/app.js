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
    title: "",
    sourceUrl: "",
    search: "",
    selected: [],
    sendStep: null,
    sentMessage: null,
    sendError: null,

    get isInstructor() {
      return this.me?.roles.includes("INSTRUCTOR");
    },

    matchingStudents(classGroup) {
      const search = this.search.trim().toLowerCase();
      return classGroup.students.filter((student) => student.studentNumber.toLowerCase().includes(search));
    },

    isWholeClassGroupSelected(classGroup) {
      return classGroup.students.every((student) => this.selected.includes(student.sub));
    },

    // A Student may be in several ClassGroups, so selection is kept per Student.
    selectWholeClassGroup(classGroup, chosen) {
      const subs = classGroup.students.map((student) => student.sub);
      const others = this.selected.filter((sub) => !subs.includes(sub));
      this.selected = chosen ? [...others, ...subs] : others;
    },

    // Two requests, so a wrong URL is obvious at once, before any StudentRepository is attempted.
    async send() {
      this.sendError = null;
      this.sentMessage = null;
      this.sendStep = "copying";
      try {
        const created = await callApi("/api/assignments", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ title: this.title, sourceUrl: this.sourceUrl, recipients: this.selected }),
        });
        if (!created.ok) {
          throw new Error(await reasonFrom(created));
        }
        const { id } = await created.json();

        // The Assignment exists now, so sending the form again would make a second one.
        const sent = `"${this.title}" to ${this.selected.length} Student(s)`;
        this.title = "";
        this.sourceUrl = "";
        this.selected = [];

        this.sendStep = "creating";
        const repositories = await callApi(`/api/assignments/${id}/repositories`, { method: "POST" });
        if (!repositories.ok) {
          throw new Error(`The repository was copied, but sending ${sent} did not finish. ${await reasonFrom(repositories)}`);
        }

        this.sentMessage = `Sent ${sent}.`;
      } catch (error) {
        this.sendError = error.message;
      } finally {
        this.sendStep = null;
      }
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
      this.title = "";
      this.sourceUrl = "";
      this.search = "";
      this.selected = [];
      this.sentMessage = null;
      this.sendError = null;
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
        // Someone who has left no longer shows, so they could not be unticked.
        const present = this.classGroups.flatMap((classGroup) => classGroup.students.map((student) => student.sub));
        this.selected = this.selected.filter((sub) => present.includes(sub));
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
  return fetch(path, { ...options, headers: { ...options.headers, Authorization: `Bearer ${token}` } });
}

// Spring explains what it refuses in `detail`, though Spring Security's own refusals have no body.
async function reasonFrom(response) {
  const body = await response.json().catch(() => null);
  return body?.detail ?? `Spring answered ${response.status}.`;
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
