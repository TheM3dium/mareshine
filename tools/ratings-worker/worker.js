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
//   GET  /ratings              {"<mod>": {"sum": 17, "count": 4}, ...}
//   POST /rate  {"mod": "eclipse", "install": "<32 hex>", "suns": 5}
//                              {"sum": ..., "count": ...} for that mod;
//                              429 {"error": "wait", "wait": seconds} too soon

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

async function agg(env, mod) {
  return (await env.RATINGS.get("agg:" + mod, "json")) || { sum: 0, count: 0 };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
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
    return json({ error: "not found" }, 404);
  },
};
