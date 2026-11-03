// Development-only stand-ins for the identity provider and the IDE plugin, later also the AI.
// The plugin is a placeholder for now: a later ticket fills in its real contract.
// Node's standard library only, so there is no npm and no build step.
const http = require("node:http");
const { handleMockIdp } = require("./mock-idp.js");

const port = Number(process.env.PORT);

const server = http.createServer((request, response) => {
  const { pathname } = new URL(request.url, "http://mocks");

  if (pathname === "/plugin-mock" || pathname.startsWith("/plugin-mock/")) {
    response.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    response.end("<!doctype html><title>Plugin mock</title><h1>Plugin mock placeholder</h1>\n");
  } else if (pathname.startsWith("/mock-idp/")) {
    handleMockIdp(request, response, pathname).catch((error) => {
      console.error(error);
      response.destroy();
    });
  } else {
    response.writeHead(404);
    response.end();
  }
});

server.listen(port, () => console.log(`Mocks listening on ${port}`));
