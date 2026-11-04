// Development-only stand-ins for the identity provider, the IDE plugin and the AI.
// Node's standard library only, so there is no npm and no build step.
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const { handleMockAi } = require("./mock-ai.js");
const { handleMockIdp } = require("./mock-idp.js");

const port = Number(process.env.PORT);
const pluginMockPage = fs.readFileSync(path.join(__dirname, "plugin-mock.html"));

const server = http.createServer((request, response) => {
  const { pathname } = new URL(request.url, "http://mocks");

  if (pathname === "/plugin-mock" || pathname.startsWith("/plugin-mock/")) {
    response.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    response.end(pluginMockPage);
  } else if (pathname.startsWith("/mock-idp/")) {
    handleMockIdp(request, response, pathname).catch((error) => {
      console.error(error);
      response.destroy();
    });
  } else if (pathname.startsWith("/ai/")) {
    handleMockAi(request, response, pathname).catch((error) => {
      console.error(error);
      response.destroy();
    });
  } else {
    response.writeHead(404);
    response.end();
  }
});

server.listen(port, () => console.log(`Mocks listening on ${port}`));
