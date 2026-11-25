package dev.pfp;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

public final class PfpConfig {
	/** days: null = globale Einstellung, 0 = unendlich ("inf"), sonst Tage. */
	public record Entry(String name, String reason, long added, String mode, Integer days) { }

	public record Parsed(String reason, Integer days) { }

	/** Haengt am Ende ein "7T" oder "inf" an, wird es als Dauer gelesen (sonst null = Standard). */
	public static Parsed parse(String text) {
		String t = text.trim();
		int i = t.lastIndexOf(' ');
		String last = i < 0 ? t : t.substring(i + 1);
		Integer days = null;
		if (last.equalsIgnoreCase("inf")) days = 0;
		else if (last.matches("(?i)\\d{1,4}[dt]")) days = Integer.parseInt(last.substring(0, last.length() - 1));
		if (days == null) return new Parsed(t, null);
		String reason = i < 0 ? "" : t.substring(0, i).trim();
		return new Parsed(reason, days);
	}

	private static int effectiveDays(Entry e) {
		return e.days() != null ? e.days() : expireDays;
	}

	public static final String DUNGEON = "dungeon";
	public static final String KUUDRA = "kuudra";
	public static final String BOTH = "both";

	public static String modeLabel(String mode) {
		return KUUDRA.equals(mode) ? "Kuudra" : DUNGEON.equals(mode) ? "Dungeons" : "Both";
	}

	/** Greift der Eintrag im gerade erkannten Modus? Unbekannter Modus (null) = nur "Both". */
	public static boolean applies(Entry e, String currentMode) {
		String m = e.mode() == null ? BOTH : e.mode();
		return m.equals(BOTH) || m.equals(currentMode);
	}

	/** Auswahl fuer "Auto-Entfernen" in Tagen (0 = nie). */
	public static final int[] EXPIRY_CHOICES = {0, 1, 3, 7, 14, 30, 90};
	public static final long DAY_MS = 86_400_000L;

	public static boolean enabled = true;
	public static boolean pfOnly = false;
	public static int expireDays = 0;
	public static Map<String, Entry> entries = new LinkedHashMap<>();
	/** Texte; vor jeder Nachricht steht immer "[PFP] ". Platzhalter: {name}, {reason}. */
	public static String kickMsg = "{name} was kicked: {reason}";
	public static String addMsg = "{name} was added to PFP: {reason}";
	public static String adMsg = "look for PFP (26.2) by Squexso on Github";
	public static boolean announceAdd = true;
	public static boolean adEnabled = true;
	public static boolean stackChat = true;
	public static boolean tooltipWarn = true;
	public static boolean tooltipReason = true;
	public static boolean partyCheck = true;
	public static boolean historyOn = true;
	public static boolean repeatAlerts = true;
	public static boolean secretTracker = false;
	/** Your own Hypixel API key for the secret tracker. Stored in plain text in config/pfp.json, never printed. */
	public static String apiKey = "";
	public static boolean debug = false;
	/** Items every joining player must own (comma separated, e.g. "Term"). Empty = off. */
	public static String requirement = "";
	/** Local player tags (lowercase name -> tag), separate from the blacklist. */
	public static Map<String, String> tags = new LinkedHashMap<>();
	public static final String[] TAGS = {"Trusted", "Friend", "Toxic", "Carry", "Suspicious"};
	public static final int[] TAG_COLORS = {0xFF55FF7F, 0xFF55FFFF, 0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55};

	public static String tagOf(String name) {
		return tags.get(key(name));
	}

	public static int tagColor(String tag) {
		for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equalsIgnoreCase(tag)) return TAG_COLORS[i];
		return 0xFFAAAAAA;
	}

	public static String format(String template, String name, String reason) {
		return template.replace("{name}", name).replace("{reason}", reason);
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pfp.json");

	private static final class Data {
		boolean enabled = true;
		boolean pfOnly = false;
		int expireDays = 0;
		Map<String, Entry> entries = new LinkedHashMap<>();
		String kickMsg = "{name} was kicked: {reason}";
		String addMsg = "{name} was added to PFP: {reason}";
		String adMsg = "look for PFP (26.2) by Squexso on Github";
		boolean announceAdd = true;
		boolean adEnabled = true;
		boolean stackChat = true;
		boolean tooltipWarn = true;
		boolean tooltipReason = true;
		boolean partyCheck = true;
		boolean historyOn = true;
		boolean repeatAlerts = true;
		boolean secretTracker = false;
		String apiKey = "";
		boolean debug = false;
		String requirement = "";
		Map<String, String> tags = new LinkedHashMap<>();
	}

	private PfpConfig() { }

	public static void load() {
		try {
			if (!Files.exists(FILE)) return;
			JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			if (o.has("entries")) {
				Data d = GSON.fromJson(o, Data.class);
				enabled = d.enabled;
				pfOnly = d.pfOnly;
				expireDays = d.expireDays;
				entries = d.entries != null ? d.entries : new LinkedHashMap<>();
				if (d.kickMsg != null) kickMsg = d.kickMsg;
				if (d.addMsg != null) addMsg = d.addMsg;
				if (d.adMsg != null) adMsg = d.adMsg;
				announceAdd = d.announceAdd;
				adEnabled = d.adEnabled;
				stackChat = d.stackChat;
				tooltipWarn = d.tooltipWarn;
				tooltipReason = d.tooltipReason;
				partyCheck = d.partyCheck;
				historyOn = d.historyOn;
				repeatAlerts = d.repeatAlerts;
				secretTracker = d.secretTracker;
				if (d.apiKey != null) apiKey = d.apiKey;
				debug = d.debug;
				if (d.requirement != null) requirement = d.requirement;
				if (d.tags != null) tags = d.tags;
			} else { // altes Format: reine Map
				Map<String, Entry> old = GSON.fromJson(o, MAP_TYPE);
				if (old != null) entries = old;
			}
			long now = System.currentTimeMillis();
			entries.replaceAll((k, e) -> e.added() == 0 || e.mode() == null
				? new Entry(e.name(), e.reason(), e.added() == 0 ? now : e.added(), e.mode() == null ? BOTH : e.mode(), e.days()) : e);
			if (!purge()) save();
		} catch (IOException | RuntimeException ignored) { }
	}

	public static void save() {
		Data d = new Data();
		d.enabled = enabled;
		d.pfOnly = pfOnly;
		d.expireDays = expireDays;
		d.entries = entries;
		d.kickMsg = kickMsg;
		d.addMsg = addMsg;
		d.adMsg = adMsg;
		d.announceAdd = announceAdd;
		d.adEnabled = adEnabled;
		d.stackChat = stackChat;
		d.tooltipWarn = tooltipWarn;
		d.tooltipReason = tooltipReason;
		d.partyCheck = partyCheck;
		d.historyOn = historyOn;
		d.repeatAlerts = repeatAlerts;
		d.secretTracker = secretTracker;
		d.apiKey = apiKey;
		d.debug = debug;
		d.requirement = requirement;
		d.tags = tags;
		try {
			Files.writeString(FILE, GSON.toJson(d));
		} catch (IOException ignored) { }
	}

	public static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public static void add(String name, String reason, String mode, Integer days) {
		entries.put(key(name), new Entry(name, reason, System.currentTimeMillis(), mode, days));
		save();
	}

	public static boolean remove(String name) {
		boolean ok = entries.remove(key(name)) != null;
		save();
		return ok;
	}

	/** Entfernt abgelaufene Eintraege. @return true wenn etwas entfernt (und gespeichert) wurde. */
	public static boolean purge() {
		long now = System.currentTimeMillis();
		boolean changed = entries.values().removeIf(e -> {
			int d = effectiveDays(e);
			return d > 0 && e.added() + d * DAY_MS < now;
		});
		if (changed) save();
		return changed;
	}

	/** Verbleibende Tage bis zum Auto-Entfernen, -1 wenn unendlich. */
	public static long daysLeft(Entry e) {
		int d = effectiveDays(e);
		if (d <= 0) return -1;
		long left = e.added() + d * DAY_MS - System.currentTimeMillis();
		return Math.max(0, (left + DAY_MS - 1) / DAY_MS);
	}

	public static void cycleExpiry() {
		int i = 0;
		for (int j = 0; j < EXPIRY_CHOICES.length; j++) if (EXPIRY_CHOICES[j] == expireDays) i = j;
		expireDays = EXPIRY_CHOICES[(i + 1) % EXPIRY_CHOICES.length];
		purge();
		save();
	}
}
