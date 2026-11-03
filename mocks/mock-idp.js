// Development-only stand-in for Cognito: real OIDC discovery, signing keys, and authorization code
// with PKCE on its own login page, and the Cognito API calls Refresh makes. Spring and the front end
// know it only through its public contract.
const crypto = require("node:crypto");
const users = require("./mock-idp-users.json");

const BASE_PATH = "/mock-idp";
const CODE_LIFETIME_SECONDS = 300;
const TOKEN_LIFETIME_SECONDS = 3600;
const COGNITO_TARGET_PREFIX = "AWSCognitoIdentityProviderService.";
// Small, so that everyday use of the mock goes through paging as well.
const COGNITO_PAGE_SIZE = 2;

const issuer = process.env.IDENTITY_ISSUER;
const clientId = process.env.IDENTITY_CLIENT_ID;
const userPoolId = process.env.COGNITO_USER_POOL_ID;

// A new key at every start, so nothing secret is stored; tokens from before a restart stop working.
const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const keyId = crypto.randomUUID();

const pendingCodes = new Map();

async function handleMockIdp(request, response, pathname) {
  const route = `${request.method} ${pathname.slice(BASE_PATH.length)}`;
  switch (route) {
    case "GET /.well-known/openid-configuration":
      return sendJson(response, 200, discoveryDocument());
    case "GET /.well-known/jwks.json":
      return sendJson(response, 200, signingKeys());
    case "GET /oauth2/authorize":
      return authorize(request, response);
    case "POST /login":
      return logIn(request, response);
    case "POST /oauth2/token":
      return exchangeCode(request, response);
    // The AWS SDK adds a slash to the path of an endpoint override.
    case "POST /cognito-api":
    case "POST /cognito-api/":
      return cognitoApi(request, response);
    default:
      response.writeHead(404);
      response.end();
  }
}

function discoveryDocument() {
  return {
    issuer,
    authorization_endpoint: `${issuer}/oauth2/authorize`,
    token_endpoint: `${issuer}/oauth2/token`,
    jwks_uri: `${issuer}/.well-known/jwks.json`,
    response_types_supported: ["code"],
    grant_types_supported: ["authorization_code"],
    subject_types_supported: ["public"],
    id_token_signing_alg_values_supported: ["RS256"],
    scopes_supported: ["openid"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none"],
  };
}

function signingKeys() {
  return { keys: [{ ...publicKey.export({ format: "jwk" }), kid: keyId, use: "sig", alg: "RS256" }] };
}

function authorize(request, response) {
  const params = new URL(request.url, issuer).searchParams;
  const problem = authorizationProblem(params);
  if (problem) {
    return sendText(response, 400, problem);
  }
  sendLoginPage(response, 200, params);
}

function authorizationProblem(params) {
  if (params.get("client_id") !== clientId) return "Unknown client_id.";
  if (!isOwnRedirect(params.get("redirect_uri"))) return "redirect_uri is not registered.";
  if (params.get("response_type") !== "code") return "Only response_type=code is supported.";
  if (!params.get("code_challenge") || params.get("code_challenge_method") !== "S256") {
    return "PKCE with code_challenge_method=S256 is required.";
  }
  return null;
}

// The mock has no client registry: everything local shares the origin of the issuer.
function isOwnRedirect(redirectUri) {
  return URL.canParse(redirectUri) && new URL(redirectUri).origin === new URL(issuer).origin;
}

async function logIn(request, response) {
  const form = await readForm(request);
  const problem = authorizationProblem(form);
  if (problem) {
    return sendText(response, 400, problem);
  }
  const user = users.find(
    (candidate) => candidate.username === form.get("username") && candidate.password === form.get("password"),
  );
  if (!user) {
    return sendLoginPage(response, 401, form, "Incorrect username or password.");
  }

  const code = crypto.randomUUID();
  pendingCodes.set(code, {
    user,
    redirectUri: form.get("redirect_uri"),
    codeChallenge: form.get("code_challenge"),
    nonce: form.get("nonce"),
    expiresAt: Date.now() + CODE_LIFETIME_SECONDS * 1000,
  });

  const redirect = new URL(form.get("redirect_uri"));
  redirect.searchParams.set("code", code);
  if (form.get("state")) {
    redirect.searchParams.set("state", form.get("state"));
  }
  response.writeHead(302, { Location: redirect.href });
  response.end();
}

async function exchangeCode(request, response) {
  const form = await readForm(request);
  if (form.get("grant_type") !== "authorization_code") {
    return sendJson(response, 400, { error: "unsupported_grant_type" });
  }
  if (form.get("client_id") !== clientId) {
    return sendJson(response, 400, { error: "invalid_client" });
  }

  // A code works once, even when this attempt fails.
  const pending = pendingCodes.get(form.get("code"));
  pendingCodes.delete(form.get("code"));
  const valid =
    pending &&
    pending.expiresAt > Date.now() &&
    pending.redirectUri === form.get("redirect_uri") &&
    pending.codeChallenge === challengeOf(form.get("code_verifier") ?? "");
  if (!valid) {
    return sendJson(response, 400, { error: "invalid_grant" });
  }

  sendJson(response, 200, {
    access_token: accessToken(pending.user),
    id_token: idToken(pending.user, pending.nonce),
    token_type: "Bearer",
    expires_in: TOKEN_LIFETIME_SECONDS,
  });
}

function challengeOf(verifier) {
  return crypto.createHash("sha256").update(verifier).digest("base64url");
}

// As in Cognito, the access token has no email and no aud.
function accessToken(user) {
  return signedToken({
    ...groupsClaim(user),
    sub: user.sub,
    client_id: clientId,
    username: user.username,
    token_use: "access",
    scope: "openid",
  });
}

function idToken(user, nonce) {
  return signedToken({
    ...groupsClaim(user),
    sub: user.sub,
    aud: clientId,
    "cognito:username": user.username,
    email: user.email,
    token_use: "id",
    ...(nonce ? { nonce } : {}),
  });
}

// As in Cognito, the claim is absent for a user in no groups.
function groupsClaim(user) {
  return user.groups.length > 0 ? { "cognito:groups": user.groups } : {};
}

function signedToken(claims) {
  const now = Math.floor(Date.now() / 1000);
  const header = encode({ alg: "RS256", kid: keyId });
  const payload = encode({ ...claims, iss: issuer, iat: now, auth_time: now, exp: now + TOKEN_LIFETIME_SECONDS });
  const signature = crypto.sign("RSA-SHA256", Buffer.from(`${header}.${payload}`), privateKey).toString("base64url");
  return `${header}.${payload}.${signature}`;
}

function encode(json) {
  return Buffer.from(JSON.stringify(json)).toString("base64url");
}

// Answers as the Cognito API does, including its error shape. Requests are not checked for a valid
// AWS signature, because the mock has no secret to compare them with.
async function cognitoApi(request, response) {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
  }
  const input = JSON.parse(body);
  if (input.UserPoolId !== userPoolId) {
    return sendCognitoError(response, "ResourceNotFoundException", `User pool ${input.UserPoolId} does not exist.`);
  }
  switch (request.headers["x-amz-target"]) {
    case `${COGNITO_TARGET_PREFIX}ListUsersInGroup`:
      return listUsersInGroup(response, input);
    case `${COGNITO_TARGET_PREFIX}AdminGetUser`:
      return adminGetUser(response, input);
    default:
      return sendCognitoError(response, "UnknownOperationException", "Unknown operation.");
  }
}

function listUsersInGroup(response, { GroupName: group, NextToken: nextToken }) {
  // The seed file has no list of groups, so a group nobody belongs to does not exist here.
  const members = users.filter((user) => user.groups.includes(group));
  if (members.length === 0) {
    return sendCognitoError(response, "ResourceNotFoundException", `Group ${group} does not exist.`);
  }
  const start = Number(nextToken ?? 0);
  const end = start + COGNITO_PAGE_SIZE;
  sendCognitoJson(response, 200, {
    Users: members.slice(start, end).map((user) => ({ Username: user.username, Attributes: attributesOf(user) })),
    ...(end < members.length ? { NextToken: String(end) } : {}),
  });
}

function adminGetUser(response, { Username: username }) {
  const user = users.find((candidate) => candidate.username === username);
  if (!user) {
    return sendCognitoError(response, "UserNotFoundException", "User does not exist.");
  }
  sendCognitoJson(response, 200, { Username: user.username, UserAttributes: attributesOf(user) });
}

function attributesOf(user) {
  return [
    { Name: "sub", Value: user.sub },
    ...(user.email ? [{ Name: "email", Value: user.email }] : []),
  ];
}

function sendCognitoError(response, type, message) {
  sendCognitoJson(response, 400, { __type: type, message });
}

function sendCognitoJson(response, status, json) {
  response.writeHead(status, { "Content-Type": "application/x-amz-json-1.1" });
  response.end(JSON.stringify(json));
}

function sendLoginPage(response, status, params, problem = "") {
  const hidden = ["client_id", "redirect_uri", "response_type", "state", "nonce", "code_challenge", "code_challenge_method"]
    .filter((name) => params.get(name))
    .map((name) => `<input type="hidden" name="${name}" value="${escapeHtml(params.get(name))}">`)
    .join("\n    ");
  response.writeHead(status, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
  response.end(`<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>Mock identity provider</title></head>
<body>
  <h1>Mock identity provider</h1>
  ${problem ? `<p role="alert">${escapeHtml(problem)}</p>` : ""}
  <form method="post" action="${BASE_PATH}/login">
    ${hidden}
    <p><label>Username <input name="username" autocomplete="username" required autofocus></label></p>
    <p><label>Password <input name="password" type="password" autocomplete="current-password" required></label></p>
    <p><button>Sign in</button></p>
  </form>
</body>
</html>
`);
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (character) => `&#${character.charCodeAt(0)};`);
}

async function readForm(request) {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
  }
  return new URLSearchParams(body);
}

function sendJson(response, status, json) {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(JSON.stringify(json) + "\n");
}

function sendText(response, status, text) {
  response.writeHead(status, { "Content-Type": "text/plain; charset=utf-8" });
  response.end(text + "\n");
}

module.exports = { handleMockIdp };
