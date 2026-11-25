package dev.pfp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Secrets found per player in a dungeon run = the player's Hypixel secret counter after the run minus before it.
 * Same method as Skyblocker (SecretsTracker) and Odin (SecretsCounter): the client never sees who picked up a secret.
 * Needs the user's own Hypixel API key. Anything that could not be read stays unknown.
 */
final class SecretTracker {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final Map<String, String> UUIDS = new ConcurrentHashMap<>();
	private static final Map<String, Integer> BASELINE = new ConcurrentHashMap<>();
	private static final long AFTER_RUN_DELAY_S = 20; // the API lags behind the game
	private static boolean warnedKey;
	/** Deployed proxy/worker.js (it holds the API key). If it fails, the player's own key is used. */
	static final String PROXY_URL = "https://pfp-secrets.danielseinfont1.workers.dev";

	private static final Map<String, Integer> CAPTURED = new ConcurrentHashMap<>();
	private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("^(\\w{1,16}): (\\d+)$");
	private static volatile long captureUntil;

	private SecretTracker() { }

	/** Hides and records the answer of Hypixel's /secretcount while we asked for it (same command NoammAddons uses). */
	static boolean capture(String text) {
		if (System.currentTimeMillis() > captureUntil) return false;
		if (text.equals("Secret Counts:")) return true;
		var m = COUNT.matcher(text);
		if (!m.matches()) return false;
		CAPTURED.put(m.group(1).toLowerCase(), Integer.parseInt(m.group(2)));
		return true;
	}

	/** Runs /secretcount and returns what it listed (at least our own count). Blocks ~3 s, call off the main thread. */
	private static Map<String, Integer> ask() {
		CAPTURED.clear();
		captureUntil = System.currentTimeMillis() + 3000;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> { if (mc.player != null) mc.player.connection.sendCommand("secretcount"); });
		try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
		Map<String, Integer> out = new LinkedHashMap<>(CAPTURED);
		if (PfpConfig.debug) PartyLog.debug("secretcount answer: " + out);
		return out;
	}

	private static boolean ready() {
		if (!PfpConfig.secretTracker) return false;
		if (PROXY_URL.isEmpty() && PfpConfig.apiKey.isBlank() && !warnedKey) {
			warnedKey = true;
			chat("Secret tracker: only your own secrets can be read without an API key (/pfp apikey <key> for the others).", ChatFormatting.YELLOW);
		}
		return true;
	}

	private static List<String> names(PartyLog.Session s) {
		List<String> n = new ArrayList<>(s.members);
		if (n.stream().noneMatch(PartyLog.me()::equalsIgnoreCase)) n.add(PartyLog.me());
		return n;
	}

	static void start(PartyLog.Session s) {
		if (!ready()) return;
		BASELINE.clear();
		List<String> names = names(s);
		CompletableFuture.runAsync(() -> {
			BASELINE.putAll(ask());
			for (String n : names) {
				if (BASELINE.containsKey(n.toLowerCase()) || !hasApi()) continue;
				Integer v = secrets(n);
				if (v != null) BASELINE.put(n.toLowerCase(), v);
			}
		});
	}

	static void finish(PartyLog.Session s) {
		if (!ready()) return;
		List<String> names = names(s);
		CompletableFuture.runAsync(() -> {
			Map<String, Integer> result = new LinkedHashMap<>();
			Map<String, Integer> now = ask();
			for (String n : names) {
				Integer before = BASELINE.get(n.toLowerCase());
				Integer after = before == null ? null : now.get(n.toLowerCase());
				if (before != null && after == null && hasApi()) after = secrets(n);
				if (before != null && after != null && after >= before) result.put(n, after - before);
			}
			Minecraft.getInstance().execute(() -> {
				s.secrets = result;
				PartyLog.save();
				report(names, result);
			});
		}, CompletableFuture.delayedExecutor(AFTER_RUN_DELAY_S, TimeUnit.SECONDS));
	}

	/** One line per player. A difference of 0 is shown with a warning: it can also mean the API had not updated yet. */
	private static void report(List<String> names, Map<String, Integer> result) {
		chat("Secrets found this run:", ChatFormatting.GRAY);
		for (String n : names) {
			Integer v = result.get(n);
			String text = v == null ? "Unknown" : v == 0 ? "0 (unconfirmed, the API may lag)" : String.valueOf(v);
			chat("- " + n + ": " + text, v == null || v == 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
		}
	}

	static void chat(String text, ChatFormatting color) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[PFP] ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(text).withStyle(color)));
		});
	}

	/** Item ids of a player (inventory, armor, equipment, ender chest) via the proxy, or null when unknown (API off, error). */
	static java.util.Set<String> gear(String name) {
		try {
			String uuid = uuid(name);
			if (uuid == null) return null;
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(PROXY_URL + "/gear/" + uuid))
				.timeout(Duration.ofSeconds(15)).header("User-Agent", "PFP-Party-Finder-Premium").build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() != 200) return null;
			JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
			if (!o.get("api").getAsBoolean()) return null;
			java.util.Set<String> ids = new java.util.HashSet<>();
			o.getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
			return ids;
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean hasApi() {
		return !PROXY_URL.isEmpty() || !PfpConfig.apiKey.isBlank();
	}

	/** Total secrets (achievement skyblock_treasure_hunter) or null when it could not be read. */
	private static Integer secrets(String name) {
		try {
			String uuid = uuid(name);
			if (uuid == null) return null;
			if (!PROXY_URL.isEmpty()) {
				HttpResponse<String> p = HTTP.send(HttpRequest.newBuilder(URI.create(PROXY_URL + "/secrets/" + uuid))
					.timeout(Duration.ofSeconds(10)).header("User-Agent", "PFP-Party-Finder-Premium").build(), HttpResponse.BodyHandlers.ofString());
				if (p.statusCode() == 429) chat("Secret lookup rate limit reached.", ChatFormatting.RED);
				if (p.statusCode() == 200) return JsonParser.parseString(p.body()).getAsJsonObject().get("secrets").getAsInt();
				if (PfpConfig.apiKey.isBlank()) return null; // proxy has no key yet or failed: fall back to the player's own key
			}
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/player?uuid=" + uuid))
				.timeout(Duration.ofSeconds(10)).header("API-Key", PfpConfig.apiKey).header("User-Agent", "PFP-Party-Finder-Premium").build(),
				HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 403) chat("Hypixel rejected the API key (403).", ChatFormatting.RED);
			else if (r.statusCode() == 429) chat("Hypixel API rate limit reached (429).", ChatFormatting.RED);
			if (r.statusCode() != 200) return null;
			JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
			if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("player") || !root.get("player").isJsonObject()) return null;
			JsonObject player = root.getAsJsonObject("player");
			if (!player.has("achievements")) return null;
			JsonObject ach = player.getAsJsonObject("achievements");
			return ach.has("skyblock_treasure_hunter") ? ach.get("skyblock_treasure_hunter").getAsInt() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String uuid(String name) throws Exception {
		if (name.equalsIgnoreCase(PartyLog.me())) return Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
		String cached = UUIDS.get(name.toLowerCase());
		if (cached != null) return cached;
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name))
			.timeout(Duration.ofSeconds(10)).header("User-Agent", "PFP-Party-Finder-Premium").build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) return null;
		String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
		UUIDS.put(name.toLowerCase(), id);
		return id;
	}
}
