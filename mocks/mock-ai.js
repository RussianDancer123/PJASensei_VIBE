// Stands in for the AI, keeping to the TutorSession and AI contract in the spec. It remembers messages
// only while it runs, ignores attached files when answering, and answers with the reversed question.
// Only Spring calls this: Caddy has no route to it.
const crypto = require("node:crypto");

const sessions = new Map();

async function handleMockAi(request, response, pathname) {
  const path = pathname.slice("/ai".length);

  if (path === "/conversations" && request.method === "POST") {
    const conversationId = crypto.randomUUID();
    sessions.set(conversationId, []);
    return sendJson(response, 200, { conversation_id: conversationId });
  }

  const [, sessionId] = path.match(/^\/conversations\/([^/]+)\/messages$/) ?? [];
  if (!sessionId) {
    return sendJson(response, 404, { message: "No such address." });
  }
  const messages = sessions.get(sessionId);
  if (!messages) {
    return sendJson(response, 404, { message: "Unknown session." });
  }

  if (request.method === "GET") {
    return sendJson(response, 200, { messages });
  }
  if (request.method === "POST") {
    const { question, files } = JSON.parse(await readBody(request));
    const answer = [...question].reverse().join("");
    messages.push(
      { role: "user", content: question, ...(files?.length ? { files } : {}) },
      { role: "assistant", content: answer },
    );
    return sendJson(response, 200, { answer });
  }
  return sendJson(response, 405, { message: "No such method." });
}

async function readBody(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function sendJson(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(body));
}

module.exports = { handleMockAi };
