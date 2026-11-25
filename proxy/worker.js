// Cloudflare Worker: lets the PFP mod read a player's Hypixel secret counter without the mod ever holding an API key.
// GET /secrets/<uuid>  ->  {"secrets": 12345}   (cached for 60 s, key stays in the HYPIXEL_KEY secret)
export default {
	async fetch(req, env, ctx) {
		const m = new URL(req.url).pathname.match(/^\/secrets\/([0-9a-f]{32})$/i);
		if (req.method !== "GET" || !m) return json({ error: "not found" }, 404);
		const cache = caches.default;
		const cacheKey = new Request(req.url);
		const hit = await cache.match(cacheKey);
		if (hit) return hit;
		const r = await fetch("https://api.hypixel.net/v2/player?uuid=" + m[1], { headers: { "API-Key": env.HYPIXEL_KEY } });
		if (!r.ok) return json({ error: "hypixel " + r.status }, r.status === 429 ? 429 : 502);
		const data = await r.json();
		const n = data?.player?.achievements?.skyblock_treasure_hunter;
		if (!data.success || typeof n !== "number") return json({ error: "no data" }, 404);
		const res = json({ secrets: n }, 200, { "Cache-Control": "public, max-age=60" });
		ctx.waitUntil(cache.put(cacheKey, res.clone()));
		return res;
	},
};

function json(body, status, headers = {}) {
	return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}
