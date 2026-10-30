// Mareshine's mod ratings: one vote (1 to 5 suns) per mod and app install,
// and every mod's sum and count of votes. No accounts and nothing about the
// player: an install is a random id the app made. Kept in a KV namespace
// (RATINGS): "vote:<mod>:<install>" holds one vote and when it was given,
// "agg:<mod>" the totals, "mods" the list of rated mods.
//
// Against spam: an install can change its vote on a mod once an hour; one
// connection gives one vote a mod an hour ("ipmod:<hash>") and at most ten
// votes an hour in all ("ip:<hash>"): salted hashes of the address that expire
// after the hour; the address itself is not kept.
//
//   GET  /catalog              the mods the app offers and their newest
//                              versions (catalog.json, put into KV as "catalog")
//   GET  /ratings              {"<mod>": {"sum": 17, "count": 4}, ...}
//   POST /rate  {"mod": "eclipse", "install": "<32 hex>", "suns": 5}
//                              {"sum": ..., "count": ...} for that mod;
//                              429 {"error": "wait", "wait": seconds} too soon
//   POST /report {"version", "device", "android", "reason", "note", "log"}
//                              a player's bug report (Send to developer, after
//                              a freeze or from Settings → LOGS): kept 30 days
//                              as "report:<id>", announced in the developer's
//                              Discord channel; {"id": "<8 chars>"} for the
//                              player to quote
//   GET  /r/<id>               the report as text (the id is the secret: it is
//                              only in the Discord message)

const ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const INSTALL = /^[0-9a-f]{32}$/;
const HOUR = 3600, PER_IP = 10;

async function hash(request, extra) {
  const ip = request.headers.get("cf-connecting-ip") || "";
  const day = new Date().toISOString().slice(0, 10);  // the salt changes daily
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("mareshine:" + day + ":" + ip + extra));
  return [...new Uint8Array(d)].slice(0, 12).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

// --- bug reports -------------------------------------------------------------------
const REPORT_BYTES = 512 * 1024, REPORT_DAYS = 30, REPORTS_PER_IP = 3;
const SHORT = 200;  // the most a version, device or reason line may be

function randomId(bytes) {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

function line(v, max = SHORT) {
  return String(v == null ? "" : v).replace(/[\r\n]+/g, " ").slice(0, max);
}

// A short message in the developer's Discord channel through a webhook
// (REPORT_WEBHOOK, a secret, so the address is not in the repository).
// Without it the report is only kept. The player's text goes into a code
// block and mentions are switched off, so a note cannot ping anyone.
async function notifyReport(env, url, id, r) {
  if (!env.REPORT_WEBHOOK) return;
  const quote = (s) => s.replace(/`/g, "'");
  const content = [
    "**Report " + id.slice(0, 8) + "** (" + quote(line(r.reason, 40)) + ")",
    "```",
    "Version: " + quote(line(r.version)),
    "Device:  " + quote(line(r.device)),
    "Android: " + quote(line(r.android)),
    "Note:    " + (quote(line(r.note, 1000)) || "(none)"),
    "```",
    "Full report (kept " + REPORT_DAYS + " days): <" + url.origin + "/r/" + id + ">",
  ].join("\n").slice(0, 2000);
  const res = await fetch(env.REPORT_WEBHOOK, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
  });
  if (!res.ok) throw new Error("webhook " + res.status);
}

async function report(request, env, url) {
  const len = Number(request.headers.get("content-length") || 0);
  if (len > REPORT_BYTES + 16 * 1024) return json({ error: "too big" }, 413);
  let r;
  try {
    const text = await request.text();
    if (text.length > REPORT_BYTES + 16 * 1024) return json({ error: "too big" }, 413);
    r = JSON.parse(text);
  } catch {
    return json({ error: "bad json" }, 400);
  }
  if (!r || typeof r.log !== "string" || !r.log) return json({ error: "no log" }, 400);
  const ik = "repip:" + (await hash(request, ":report"));
  const used = Number(await env.RATINGS.get(ik)) || 0;
  if (used >= REPORTS_PER_IP) return json({ error: "wait", wait: HOUR }, 429);
  await env.RATINGS.put(ik, String(used + 1), { expirationTtl: HOUR });
  const id = randomId(16);
  const head = [
    "Version: " + line(r.version),
    "Device:  " + line(r.device),
    "Android: " + line(r.android),
    "Reason:  " + line(r.reason),
    "Sent:    " + new Date().toISOString(),
    "Note:    " + line(r.note, 2000),
    "",
  ].join("\n");
  await env.RATINGS.put("report:" + id, head + "\n" + r.log.slice(0, REPORT_BYTES), {
    expirationTtl: REPORT_DAYS * 86400,
  });
  try {
    await notifyReport(env, url, id, r);
  } catch (e) {
    console.log("report notice failed: " + e);  // kept anyway
  }
  return json({ id: id.slice(0, 8) });
}

async function agg(env, mod) {
  return (await env.RATINGS.get("agg:" + mod, "json")) || { sum: 0, count: 0 };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/catalog") {
      const c = await env.RATINGS.get("catalog");
      return new Response(c || '{"releases": []}', {
        headers: { "content-type": "application/json", "cache-control": "public, max-age=300" },
      });
    }
    if (request.method === "GET" && url.pathname === "/ratings") {
      const mods = (await env.RATINGS.get("mods", "json")) || [];
      const out = {};
      for (const mod of mods) out[mod] = await agg(env, mod);
      return json(out);
    }
    if (request.method === "POST" && url.pathname === "/rate") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const { mod, install } = body || {};
      const suns = Number(body && body.suns);
      if (!ID.test(mod || "") || !INSTALL.test(install || "") || !Number.isInteger(suns) || suns < 1 || suns > 5)
        return json({ error: "bad vote" }, 400);
      const now = Math.floor(Date.now() / 1000);
      const key = "vote:" + mod + ":" + install;
      const prev = (await env.RATINGS.get(key, "json")) || { s: 0, t: 0 };
      if (prev.s && now - prev.t < HOUR) return json({ error: "wait", wait: HOUR - (now - prev.t) }, 429);
      const ik = "ip:" + (await hash(request, "")), mk = "ipmod:" + (await hash(request, ":" + mod));
      const used = Number(await env.RATINGS.get(ik)) || 0;
      if (used >= PER_IP || (await env.RATINGS.get(mk))) return json({ error: "wait", wait: HOUR }, 429);
      await env.RATINGS.put(ik, String(used + 1), { expirationTtl: HOUR });
      await env.RATINGS.put(mk, "1", { expirationTtl: HOUR });
      const old = prev.s;
      const a = await agg(env, mod);
      a.sum += suns - old;
      if (!old) a.count += 1;
      await env.RATINGS.put(key, JSON.stringify({ s: suns, t: now }));
      await env.RATINGS.put("agg:" + mod, JSON.stringify(a));
      const mods = (await env.RATINGS.get("mods", "json")) || [];
      if (!mods.includes(mod)) {
        if (mods.length >= 1000) return json({ error: "too many mods" }, 400);
        mods.push(mod);
        await env.RATINGS.put("mods", JSON.stringify(mods));
      }
      return json(a);
    }
    if (request.method === "POST" && url.pathname === "/report") return report(request, env, url);
    if (request.method === "GET" && url.pathname.startsWith("/r/")) {
      const id = url.pathname.slice(3);
      if (!/^[0-9a-f]{32}$/.test(id)) return json({ error: "not found" }, 404);
      const text = await env.RATINGS.get("report:" + id);
      if (text == null) return json({ error: "not found" }, 404);
      return new Response(text, {
        headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex" },
      });
    }
    return json({ error: "not found" }, 404);
  },
};
