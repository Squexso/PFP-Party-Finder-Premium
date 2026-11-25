package dev.pfp;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.loader.api.FabricLoader;

/** Current party, party history (last 20 sessions), party-join warnings and Party Finder tooltip warnings. */
final class PartyLog {
	static final class Session {
		String type; // PfpConfig.DUNGEON / KUUDRA / null when unknown
		String info; // "Floor VII", "Basic Tier" (from the Party Finder tooltip), may be null
		String leader;
		long joined, left;
		List<String> members = new ArrayList<>(); // everyone seen in the party except you
		List<String> matches = new ArrayList<>(); // blacklisted players seen
		List<String> kicked = new ArrayList<>(); // players kicked by PFP
		String result; // "Completed" when the run-end message was seen, null = unknown
		boolean ran; // the run start message was seen while this session was active
		Map<String, Integer> supplies; // Kuudra supplies recovered per player (only set when the run start was seen)
		Map<String, Integer> secrets; // secret counter difference per player, absent = unknown
	}

	/** One entry of the persistent activity list on the Overview tab. */
	static final class Act {
		long t;
		String text;
		boolean warn;
	}

	static final int MAX = 20;
	static final int REPEAT_MIN = 3;
	/** Newest first. */
	static List<Session> history = new ArrayList<>();
	static Session cur;
	static final List<String> present = new ArrayList<>();
	/** Recent notable events (joins of parties, blacklist hits, kicks), newest first, persisted. */
	static List<Act> activity = new ArrayList<>();

	private static final Set<String> alerted = new HashSet<>();
	private static String hoverLeader, hoverInfo, hoverType;
	private static long hoverAt;
	private static String lastTip;

	private static final Gson GSON = new Gson();
	private static final Type LIST = new TypeToken<List<Session>>() { }.getType();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pfp_history.json");
	private static final Pattern NAME = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)?(\\w{1,16})(?:\\s*[:(].*)?$");
	private static final Path ACT_FILE = FabricLoader.getInstance().getConfigDir().resolve("pfp_activity.json");
	private static final Type ACT_LIST = new TypeToken<List<Act>>() { }.getType();
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM HH:mm");

	private PartyLog() { }

	static void load() {
		try {
			if (Files.exists(FILE)) {
				List<Session> l = GSON.fromJson(Files.readString(FILE), LIST);
				if (l != null) history = new ArrayList<>(l.subList(0, Math.min(MAX, l.size())));
			}
			if (Files.exists(ACT_FILE)) {
				List<Act> a = GSON.fromJson(Files.readString(ACT_FILE), ACT_LIST);
				if (a != null) activity = new ArrayList<>(a.subList(0, Math.min(ACT_MAX, a.size())));
			}
		} catch (IOException | RuntimeException ignored) { }
	}

	static void save() {
		try {
			Files.writeString(FILE, GSON.toJson(history));
		} catch (IOException ignored) { }
	}

	private static final int ACT_MAX = 20;

	static String me() {
		return Minecraft.getInstance().getUser().getName();
	}

	/** persist = false keeps routine lines (members joining) out of the stored list: they are already in the session. */
	private static void log(String text, boolean warn, boolean persist) {
		if (!persist) return;
		Act a = new Act();
		a.t = System.currentTimeMillis();
		a.text = text;
		a.warn = warn;
		activity.add(0, a);
		while (activity.size() > ACT_MAX) activity.remove(activity.size() - 1);
		try {
			Files.writeString(ACT_FILE, GSON.toJson(activity));
		} catch (IOException ignored) { }
	}

	static String actTime(Act a) {
		var t = java.time.Instant.ofEpochMilli(a.t).atZone(java.time.ZoneId.systemDefault());
		return t.toLocalDate().equals(java.time.LocalDate.now()) ? t.format(HM) : t.format(DM);
	}

	private static void send(Component body) {
		var p = Minecraft.getInstance().player;
		if (p != null) p.sendSystemMessage(Component.literal("[PFP] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(body));
	}

	private static void add(String name) {
		if (name.equalsIgnoreCase(me())) return;
		if (present.stream().noneMatch(name::equalsIgnoreCase)) present.add(name);
		if (cur != null && cur.members.stream().noneMatch(name::equalsIgnoreCase)) cur.members.add(name);
	}

	private static Session start(String leader) {
		end();
		cur = new Session();
		cur.joined = System.currentTimeMillis();
		cur.leader = leader;
		return cur;
	}

	/** "You have joined X's party!" */
	static void joined(String leader) {
		start(leader);
		add(leader);
		if (leader.equalsIgnoreCase(hoverLeader) && System.currentTimeMillis() - hoverAt < 120_000) {
			cur.type = hoverType;
			cur.info = hoverInfo;
		}
		log("Joined " + leader + "'s party", false, true);
		check(List.of(leader), true);
	}

	/** "You'll be partying with: A, B" */
	static void members(List<String> names) {
		if (cur == null) return;
		names.forEach(PartyLog::add);
		check(names, false);
		repeat(names);
	}

	/** Someone joined while we are in (or start) a party. mode may be null (invite). */
	static void memberJoin(String name, String mode) {
		if (cur == null) start(me());
		if (mode != null && cur.type == null) cur.type = mode;
		add(name);
		log(name + " joined the party", false, false);
		PfpConfig.Entry e = PfpConfig.entries.get(PfpConfig.key(name));
		if (e != null) {
			if (!cur.matches.contains(e.name())) cur.matches.add(e.name());
			log("Blacklisted player detected: " + e.name(), true, true);
		}
		repeat(List.of(name));
	}

	/** We created a Party Finder listing (we lead). */
	static void ownParty(String type) {
		start(me());
		cur.type = type;
	}

	/** Dungeon ("Mort: Good luck") or Kuudra ("Elle: ... fish up Kuudra") run started while we are in a party. */
	static void runStart(String type) {
		if (cur == null) return;
		cur.type = type;
		cur.ran = true;
		cur.result = null;
		cur.supplies = PfpConfig.KUUDRA.equals(type) ? new LinkedHashMap<>() : null;
		if (PfpConfig.DUNGEON.equals(type)) SecretTracker.start(cur);
	}

	/** Run-end marker seen (dungeon score summary / Elle's final line). */
	static void runEnd() {
		if (cur == null || !cur.ran) return;
		cur.result = "Completed";
		if (PfpConfig.DUNGEON.equals(cur.type)) SecretTracker.finish(cur);
	}

	/** "<name> recovered one of Elle's supplies!" */
	static void supply(String name) {
		if (cur != null && cur.supplies != null) cur.supplies.merge(name, 1, Integer::sum);
	}

	static void left(String name) {
		present.removeIf(name::equalsIgnoreCase);
	}

	static void kicked(String name) {
		if (cur != null && cur.kicked.stream().noneMatch(name::equalsIgnoreCase)) cur.kicked.add(name);
		log("Kicked " + name, false, true);
	}

	/** The party is over (left, disbanded, kicked, disconnected). */
	static void end() {
		if (cur != null && PfpConfig.historyOn && !cur.members.isEmpty()
			&& history.stream().noneMatch(s -> s.joined == cur.joined)) {
			cur.left = System.currentTimeMillis();
			history.add(0, cur);
			while (history.size() > MAX) history.remove(history.size() - 1);
			save();
		}
		cur = null;
		present.clear();
		alerted.clear();
	}

	private static void check(List<String> names, boolean leader) {
		List<PfpConfig.Entry> hits = new ArrayList<>();
		for (String n : names) {
			PfpConfig.Entry e = n.equalsIgnoreCase(me()) ? null : PfpConfig.entries.get(PfpConfig.key(n));
			if (e == null) continue;
			hits.add(e);
			if (cur != null && !cur.matches.contains(e.name())) cur.matches.add(e.name());
			log("Blacklisted player detected: " + e.name(), true, true);
		}
		if (hits.isEmpty() || !PfpConfig.partyCheck) return;
		if (leader) send(Component.literal("WARNING: Your current Party Leader is blacklisted!").withStyle(ChatFormatting.RED));
		send(Component.literal(hits.size() + (hits.size() == 1 ? " blacklisted player" : " blacklisted players")
			+ " detected in your party!").withStyle(ChatFormatting.RED));
		for (PfpConfig.Entry e : hits) {
			send(Component.literal("- " + e.name()).withStyle(ChatFormatting.RED)
				.append(Component.literal(": " + e.reason()).withStyle(ChatFormatting.GRAY)));
		}
	}

	/** In how many recorded parties (history, plus the running one) the player was. */
	static int seen(String name) {
		int c = (int) history.stream().filter(x -> name.equalsIgnoreCase(x.leader) || x.members.stream().anyMatch(name::equalsIgnoreCase)).count();
		if (cur != null && !history.contains(cur) && (name.equalsIgnoreCase(cur.leader) || cur.members.stream().anyMatch(name::equalsIgnoreCase))) c++;
		return c;
	}

	static int recorded() {
		return history.size() + (cur != null && !history.contains(cur) ? 1 : 0);
	}

	/** Newest session of that type in which the run start was seen, or null. */
	private static Session lastRun(String type) {
		if (cur != null && type.equals(cur.type) && cur.ran) return cur;
		for (Session x : history) if (type.equals(x.type) && x.ran) return x;
		return null;
	}

	private static List<String> players(Session x) {
		List<String> n = new ArrayList<>();
		if (x.leader != null) n.add(x.leader);
		for (String m : x.members) if (n.stream().noneMatch(m::equalsIgnoreCase)) n.add(m);
		if (n.stream().noneMatch(me()::equalsIgnoreCase)) n.add(me());
		return n;
	}

	/** Text for "!pfp supplies": supplies per player of the last Kuudra run. */
	static String suppliesText() {
		Session x = lastRun(PfpConfig.KUUDRA);
		if (x == null) return "No Kuudra run recorded yet";
		if (x.supplies == null) return "Kuudra supplies of the last run: unknown";
		StringBuilder sb = new StringBuilder("Kuudra supplies of the last run: ");
		boolean first = true;
		for (String n : players(x)) {
			Integer v = x.supplies.get(n);
			sb.append(first ? "" : ", ").append(n).append(' ').append(v == null ? 0 : v);
			first = false;
		}
		return sb.toString();
	}

	/** Text for "!pfp secrets": secrets per player of the last dungeon run, never a bare unconfirmed zero. */
	static String secretsText() {
		Session x = lastRun(PfpConfig.DUNGEON);
		if (x == null) return "No dungeon run recorded yet";
		if (x.secrets == null) return "Dungeon secrets of the last run: not available";
		StringBuilder sb = new StringBuilder("Dungeon secrets of the last run: ");
		boolean first = true;
		for (String n : players(x)) {
			Integer v = x.secrets.get(n);
			sb.append(first ? "" : ", ").append(n).append(' ').append(v == null ? "unknown" : v == 0 ? "0 (unconfirmed)" : String.valueOf(v));
			first = false;
		}
		return sb.toString();
	}

	private static void repeat(List<String> names) {
		if (!PfpConfig.repeatAlerts) return;
		for (String n : names) {
			if (n.equalsIgnoreCase(me()) || !alerted.add(n.toLowerCase())) continue;
			long c = history.stream().filter(s -> n.equalsIgnoreCase(s.leader) || s.members.stream().anyMatch(n::equalsIgnoreCase)).count();
			if (c >= REPEAT_MIN) {
				send(Component.literal(n).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(" has appeared in " + c + " of your last " + history.size() + " parties.").withStyle(ChatFormatting.GRAY)));
			}
		}
	}

	private static String strip(Component c) {
		return c.getString().replaceAll("§.", "").replace(' ', ' ').trim();
	}

	/** Party Finder item tooltip: add blacklist warnings and tags below the original lines. */
	static void tooltip(List<Component> lines) {
		if (lines.size() < 4) return;
		int members = -1;
		boolean click = false;
		String info = null, type = null;
		for (int i = 0; i < lines.size(); i++) {
			String t = strip(lines.get(i));
			if (t.equals("Members:")) members = i;
			else if (t.startsWith("Click to join")) click = true;
			else if (t.startsWith("Floor:") || t.startsWith("Tier:")) {
				info = t.substring(t.indexOf(':') + 1).trim();
				type = t.startsWith("Tier") ? PfpConfig.KUUDRA : PfpConfig.DUNGEON;
			}
		}
		if (members < 0 || !click) return;
		if (PfpConfig.debug) {
			StringBuilder sb = new StringBuilder("TOOLTIP:");
			for (Component c : lines) sb.append(" | ").append(strip(c));
			if (!sb.toString().equals(lastTip)) { lastTip = sb.toString(); debug(lastTip); }
		}

		String title = strip(lines.get(0));
		if (title.endsWith("'s Party")) {
			hoverLeader = title.substring(0, title.length() - 8);
			hoverInfo = info;
			hoverType = type;
			hoverAt = System.currentTimeMillis();
		}

		List<Component> out = new ArrayList<>();
		List<Component> tagged = new ArrayList<>();
		for (int i = members + 1; i < lines.size(); i++) {
			String t = strip(lines.get(i));
			if (t.isEmpty()) break;
			if (t.equals("Empty")) continue;
			Matcher m = NAME.matcher(t);
			if (!m.matches()) break;
			String name = m.group(1);
			PfpConfig.Entry e = PfpConfig.entries.get(PfpConfig.key(name));
			if (e != null) {
				Component line = Component.literal("Blacklisted: " + e.name()).withStyle(ChatFormatting.RED);
				if (PfpConfig.tooltipReason) line = line.copy().append(Component.literal(" - " + e.reason()).withStyle(ChatFormatting.GRAY));
				out.add(line);
			}
			String tag = PfpConfig.tagOf(name);
			if (tag != null) {
				int col = PfpConfig.tagColor(tag) & 0xFFFFFF;
				tagged.add(Component.literal(name + " (" + tag + ")").withStyle(s -> s.withColor(col)));
			}
		}
		if (out.isEmpty() && tagged.isEmpty()) return;
		lines.add(Component.empty());
		lines.addAll(out);
		if (!tagged.isEmpty()) {
			var tl = Component.literal("Tags: ").withStyle(ChatFormatting.GRAY);
			for (int i = 0; i < tagged.size(); i++) tl.append(i == 0 ? tagged.get(i) : Component.literal(", ").withStyle(ChatFormatting.GRAY).append(tagged.get(i)));
			lines.add(tl);
		}
	}

	/** Appends party/run related chat lines (and Party Finder tooltips) to config/pfp_debug.log so Hypixel formats can be verified. */
	static void debug(String line) {
		String l = line.toLowerCase();
		if (!(l.contains("party") || l.contains("elle") || l.contains("mort") || l.contains("catacombs") || l.contains("team score")
			|| l.contains("secret") || l.contains("kuudra") || l.contains("members:"))) return;
		try {
			Path f = FabricLoader.getInstance().getConfigDir().resolve("pfp_debug.log");
			if (Files.exists(f) && Files.size(f) > 512_000) return;
			Files.writeString(f, LocalTime.now().format(HM) + " " + line + "\n",
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) { }
	}

	static String title(Session s) {
		String t = PfpConfig.KUUDRA.equals(s.type) ? "Kuudra" : PfpConfig.DUNGEON.equals(s.type) ? "Dungeons" : "Party";
		return s.info == null ? t : t + " · " + s.info;
	}
}
