// Cloudflare Worker: lets the PFP mod read Hypixel data without the mod ever holding an API key. The key stays in the HYPIXEL_KEY secret.
//   GET /secrets/<uuid>  ->  {"secrets": 12345}                       (cached 60 s)
//   GET /gear/<uuid>     ->  {"api": true, "ids": ["TERMINATOR", ..]} (cached 5 min; api=false when the inventory API is off)
export default {
	async fetch(req, env, ctx) {
		const m = new URL(req.url).pathname.match(/^\/(secrets|gear)\/([0-9a-f]{32})$/i);
		if (req.method !== "GET" || !m) return json({ error: "not found" }, 404);
		const cache = caches.default;
		const cacheKey = new Request(req.url);
		const hit = await cache.match(cacheKey);
		if (hit) return hit;
		const uuid = m[2].toLowerCase();
		const out = m[1].toLowerCase() === "secrets" ? await secrets(uuid, env) : await gear(uuid, env);
		if (out.status !== 200) return out;
		const age = m[1].toLowerCase() === "secrets" ? 60 : 300;
		const res = new Response(out.body, { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=" + age } });
		ctx.waitUntil(cache.put(cacheKey, res.clone()));
		return res;
	},
};

async function hypixel(path, env) {
	const r = await fetch("https://api.hypixel.net" + path, { headers: { "API-Key": env.HYPIXEL_KEY } });
	if (!r.ok) {
		const cause = (await r.json().catch(() => ({}))).cause;
		return { err: json({ error: "hypixel " + r.status, cause }, r.status === 429 ? 429 : 502) };
	}
	return { data: await r.json() };
}

async function secrets(uuid, env) {
	const r = await hypixel("/v2/player?uuid=" + uuid, env);
	if (r.err) return r.err;
	const n = r.data?.player?.achievements?.skyblock_treasure_hunter;
	if (!r.data.success || typeof n !== "number") return json({ error: "no data" }, 404);
	return json({ secrets: n }, 200);
}

async function gear(uuid, env) {
	const r = await hypixel("/v2/skyblock/profiles?uuid=" + uuid, env);
	if (r.err) return r.err;
	const profiles = r.data?.profiles;
	if (!r.data.success || !Array.isArray(profiles) || !profiles.length) return json({ error: "no data" }, 404);
	const inv = (profiles.find(p => p.selected) || profiles[0]).members?.[uuid]?.inventory;
	if (!inv?.inv_contents?.data) return json({ api: false, ids: [] }, 200); // inventory API disabled
	const ids = new Set();
	for (const k of ["inv_contents", "inv_armor", "equipment_contents", "ender_chest_contents"]) {
		if (!inv[k]?.data) continue;
		try { for (const id of nbtIds(await gunzip(inv[k].data))) ids.add(id); } catch { /* skip an unreadable section */ }
	}
	return json({ api: true, ids: [...ids] }, 200);
}

async function gunzip(b64) {
	const bin = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
	const stream = new Blob([bin]).stream().pipeThrough(new DecompressionStream("gzip"));
	return new Uint8Array(await new Response(stream).arrayBuffer());
}

// Minimal NBT reader: collects the string tag "id" inside every "ExtraAttributes" compound (= the SkyBlock item id).
function nbtIds(buf) {
	const v = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
	const dec = new TextDecoder();
	const out = [];
	let p = 0;
	const str = () => { const n = v.getUint16(p); p += 2; const s = dec.decode(buf.subarray(p, p + n)); p += n; return s; };
	function payload(t, extra) {
		switch (t) {
			case 1: p += 1; break;
			case 2: p += 2; break;
			case 3: case 5: p += 4; break;
			case 4: case 6: p += 8; break;
			case 7: p += 4 + v.getInt32(p); break;
			case 8: return str();
			case 9: { const it = v.getUint8(p); const n = v.getInt32(p + 1); p += 5; for (let i = 0; i < n; i++) payload(it, false); break; }
			case 10:
				for (;;) {
					const tt = v.getUint8(p++);
					if (tt === 0) break;
					const name = str();
					const r = payload(tt, name === "ExtraAttributes");
					if (extra && tt === 8 && name === "id") out.push(r);
				}
				break;
			case 11: p += 4 + v.getInt32(p) * 4; break;
			case 12: p += 4 + v.getInt32(p) * 8; break;
		}
	}
	const t = v.getUint8(p++);
	str();
	payload(t, false);
	return out;
}

function json(body, status) {
	return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
