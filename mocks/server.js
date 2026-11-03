// Development-only stand-ins for the identity provider and the IDE plugin, later also the AI.
// Placeholders for now: later tickets fill in their real API contracts.
// Node's standard library only, so there is no npm and no build step.
const http = require("node:http");

const port = Number(process.env.PORT);

const server = http.createServer((request, response) => {
  const { pathname } = new URL(request.url, "http://mocks");

  if (pathname === "/plugin-mock" || pathname.startsWith("/plugin-mock/")) {
    response.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    response.end("<!doctype html><title>Plugin mock</title><h1>Plugin mock placeholder</h1>\n");
  } else if (pathname === "/mock-idp" || pathname.startsWith("/mock-idp/")) {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ service: "mock-idp", status: "placeholder" }) + "\n");
  } else {
    response.writeHead(404);
    response.end();
  }
});

server.listen(port, () => console.log(`Mocks listening on ${port}`));
