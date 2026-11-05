// Signs the user in with authorization code and PKCE on the identity provider's own page, so this
// page never sees a password. The sign-in lives in sessionStorage, so it ends with the tab.
const TOKEN_KEY = "pjasensei.accessToken";
const PENDING_SIGN_IN_KEY = "pjasensei.pendingSignIn";
// Without a slash, as registered in Cognito: it compares the address character by character.
const REDIRECT_URI = location.origin;

// Embedded in an IDE plugin (docs/plugin-contract.md), the plugin signs in and hands over its token.
// It stays in memory, because a parent on this origin shares sessionStorage with this page.
const EMBEDDED = new URLSearchParams(location.search).has("embedded");
let pluginToken = null;
let pluginOrigins = [];

document.addEventListener("alpine:init", () => {
  Alpine.data("session", () => ({
    embedded: EMBEDDED,
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
    studentNumbersWithoutRepository: [],
    assignments: [],
    sentAssignments: [],
    openedAssignment: null,
    browseError: null,
    preview: null,
    cloneCommand: null,
    copied: false,
    workError: null,
    publicKey: "",
    registeringKey: false,
    keyMessage: null,
    keyError: null,

    get isInstructor() {
      return this.me?.roles.includes("INSTRUCTOR");
    },

    get isStudent() {
      return this.me?.roles.includes("STUDENT");
    },

    // Kept apart from what called it: a failure here is neither a failed send nor a reason to hide
    // the Assignments of someone who is also a Student.
    async reloadSentAssignments() {
      this.browseError = null;
      try {
        this.sentAssignments = await fetchSentAssignments();
      } catch (error) {
        this.browseError = error.message;
      }
    },

    async openSentAssignment(id) {
      this.browseError = null;
      this.openedAssignment = null;
      try {
        const response = await callApi(`/api/assignments/${id}`);
        if (!response.ok) {
          throw new Error(await reasonFrom(response));
        }
        this.openedAssignment = await response.json();
      } catch (error) {
        this.browseError = error.message;
      }
    },

    async openPreview(id) {
      this.workError = null;
      this.preview = null;
      this.cloneCommand = null;
      this.copied = false;
      try {
        const response = await callApi(`/api/student/assignments/${id}`);
        if (!response.ok) {
          throw new Error(await reasonFrom(response));
        }
        this.preview = await response.json();
      } catch (error) {
        this.workError = error.message;
      }
    },

    async getRepository() {
      this.workError = null;
      try {
        const response = await callApi(`/api/student/assignments/${this.preview.id}/ssh-address`);
        if (!response.ok) {
          throw new Error(await reasonFrom(response));
        }
        const { sshUrl } = await response.json();
        if (EMBEDDED) {
          this.sendCloneToPlugin(sshUrl, this.preview.id);
          return;
        }
        this.cloneCommand = `git clone ${sshUrl}`;
        this.copied = false;
      } catch (error) {
        this.workError = error.message;
      }
    },

    sendCloneToPlugin(sshUrl, assignmentId) {
      sendToPlugin({ type: "clone", sshUrl, assignmentId });
    },

    async copyCloneCommand() {
      try {
        await navigator.clipboard.writeText(this.cloneCommand);
        this.copied = true;
      } catch {
        this.workError = "Could not copy. Please select the command and copy it yourself.";
      }
    },

    async registerKey() {
      this.keyError = null;
      this.keyMessage = null;
      this.registeringKey = true;
      try {
        const response = await callApi("/api/ssh-keys", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ publicKey: this.publicKey }),
        });
        if (!response.ok) {
          throw new Error(await reasonFrom(response));
        }
        this.publicKey = "";
        this.keyMessage = "Key added.";
      } catch (error) {
        this.keyError = error.message;
      } finally {
        this.registeringKey = false;
      }
    },

    matchingStudents(classGroup) {
      const search = this.search.trim().toLowerCase();
      return classGroup.students.filter((student) => student.studentNumber.toLowerCase().includes(search));
    },

    areMatchingStudentsSelected(classGroup) {
      return this.matchingStudents(classGroup).every((student) => this.selected.includes(student.sub));
    },

    // With no search active every Student matches, so this covers the whole ClassGroup.
    // A Student may be in several ClassGroups, so selection is kept per Student.
    selectMatchingStudents(classGroup, chosen) {
      const subs = this.matchingStudents(classGroup).map((student) => student.sub);
      const others = this.selected.filter((sub) => !subs.includes(sub));
      this.selected = chosen ? [...others, ...subs] : others;
    },

    // Two requests, so a wrong URL is obvious at once, before any StudentRepository is attempted.
    async send() {
      this.sendError = null;
      this.sentMessage = null;
      this.studentNumbersWithoutRepository = [];
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
        const title = this.title;
        const recipientCount = this.selected.length;
        const sent = `"${title}" to ${recipientCount} Student(s)`;
        this.title = "";
        this.sourceUrl = "";
        this.selected = [];

        this.sendStep = "creating";
        const repositories = await callApi(`/api/assignments/${id}/repositories`, { method: "POST" });
        if (!repositories.ok) {
          throw new Error(`The repository was copied, but sending ${sent} did not finish. ${await reasonFrom(repositories)}`);
        }

        const { failures } = await repositories.json();
        this.studentNumbersWithoutRepository = failures.map((failure) => failure.studentNumber);
        this.sentMessage = `Sent "${title}" to ${recipientCount - failures.length} of ${recipientCount} Student(s).`;
        await this.reloadSentAssignments();
      } catch (error) {
        this.sendError = error.message;
      } finally {
        this.sendStep = null;
      }
    },

    async init() {
      try {
        if (EMBEDDED) {
          await this.connectToPlugin();
        } else {
          await completeSignIn();
          await this.loadAccount();
        }
      } catch (error) {
        this.error = error.message;
      } finally {
        this.loading = false;
      }
    },

    async loadAccount() {
      this.me = await fetchMe();
      if (this.isInstructor) {
        this.classGroups = await fetchClassGroups();
        await this.reloadSentAssignments();
      }
      if (this.isStudent) {
        this.assignments = await fetchStudentAssignments();
      }
    },

    // The listener is in place before "ready" goes out, so the answer cannot be missed.
    async connectToPlugin() {
      pluginOrigins = originsOf((await getJson("/config.json")).frameAncestors);
      window.addEventListener("message", (event) => {
        if (event.source !== window.parent || !pluginOrigins.includes(event.origin)) {
          return;
        }
        if (event.data?.type === "token" && typeof event.data.accessToken === "string") {
          this.receiveToken(event.data.accessToken);
        }
      });
      sendToPlugin({ type: "ready" });
    },

    // A renewed token only replaces the old one, leaving whatever is open on the page alone.
    async receiveToken(token) {
      pluginToken = token;
      if (this.me) {
        return;
      }
      this.error = null;
      try {
        await this.loadAccount();
      } catch (error) {
        this.error = error.message;
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

    // Cognito also ends its own login session, which would otherwise sign the next person straight
    // back in. The mock keeps none, so its discovery document has no end_session_endpoint.
    async signOut() {
      sessionStorage.removeItem(TOKEN_KEY);
      const { clientId, end_session_endpoint: endSession } = await identityProvider().catch(() => ({}));
      if (endSession) {
        // Cognito's own parameters, and logout_uri has to be one of the client's sign-out URLs.
        location.assign(`${endSession}?${new URLSearchParams({ client_id: clientId, logout_uri: location.origin })}`);
        return;
      }
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
      this.studentNumbersWithoutRepository = [];
      this.assignments = [];
      this.sentAssignments = [];
      this.openedAssignment = null;
      this.browseError = null;
      this.preview = null;
      this.cloneCommand = null;
      this.copied = false;
      this.workError = null;
      this.publicKey = "";
      this.keyMessage = null;
      this.keyError = null;
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
    scope: "openid email profile",
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

function currentToken() {
  return EMBEDDED ? pluginToken : sessionStorage.getItem(TOKEN_KEY);
}

// Who may embed this page is also who may talk to it. A CSP source that is not one exact origin,
// such as a wildcard or a bare host, cannot be compared with the origin of a message, so it counts
// for nothing here. Such a source parses to the origin "null", which postMessage refuses as a target.
function originsOf(frameAncestors) {
  return frameAncestors.split(/\s+/).flatMap((source) => {
    if (source === "'self'") {
      return [location.origin];
    }
    const origin = URL.canParse(source) ? new URL(source).origin : "null";
    return origin !== "null" && !source.includes("*") ? [origin] : [];
  });
}

// Goes only to a parent on an allowed origin: the browser drops it for any other.
function sendToPlugin(message) {
  pluginOrigins.forEach((origin) => window.parent.postMessage(message, origin));
}

async function fetchMe() {
  const token = currentToken();
  if (!token) {
    return null;
  }
  const response = await fetch("/api/me", { headers: { Authorization: `Bearer ${token}` } });
  if (response.status === 401) {
    // Expired or no longer accepted: back to the sign-in button, or to waiting for the plugin. A
    // renewed token may have arrived during this request, and that one is still good.
    if (pluginToken === token) {
      pluginToken = null;
    }
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

// An Instructor Refresh has not added yet is refused, and simply has nothing to show so far.
async function fetchSentAssignments() {
  const response = await callApi("/api/assignments");
  if (response.status === 403) {
    return [];
  }
  if (!response.ok) {
    throw new Error(`Spring answered ${response.status}.`);
  }
  return (await response.json()).assignments;
}

// A Student no Refresh has added yet is refused, and simply has nothing to show so far.
async function fetchStudentAssignments() {
  const response = await callApi("/api/student/assignments");
  if (response.status === 403) {
    return [];
  }
  if (!response.ok) {
    throw new Error(`Spring answered ${response.status}.`);
  }
  return (await response.json()).assignments;
}

function callApi(path, options = {}) {
  return fetch(path, { ...options, headers: { ...options.headers, Authorization: `Bearer ${currentToken()}` } });
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
