package dev.pfp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class PfpClient implements ClientModInitializer {
	/** "Party Finder > Name joined the dungeon group! (...)" / "... joined the group! (...)" (Kuudra). */
	private static final Pattern PF_JOIN = Pattern.compile("^Party Finder > (?:\\[[^\\]]+\\] )?(\\w{1,16}) joined the .*?group!");
	/** "[MVP+] Name joined the party." (Einladungen) */
	private static final Pattern PARTY_JOIN = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\w{1,16}) joined the party\\.");

	/** "Party > [MVP+] Name: !pfp" */
	private static final Pattern PARTY_AD = Pattern.compile("^\\s*Party\\s*>\\s*(?:\\[[^\\]]+\\]\\s*)*(\\w{1,16})\\s*:\\s*!pfp(?:\\s+(\\w{1,16}(?:\\s+list)?))?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final int AD_COOLDOWN = 60; // Ticks (3 s)
	private static int lastAd = -AD_COOLDOWN;

	/** Fixed reply to "!pfp" (not editable). */
	static final String AD_TEXT = "look for PFP (26.2) by Squexso on Github";

	private static boolean openGui = false;
	private static net.minecraft.client.KeyMapping coordsKey;

	/** Sendet "/pc [PFP] text"; "[PFP]" steht immer vorne, Zeilenumbrueche fliegen raus, Laenge begrenzt. */
	static void partyMsg(String text) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		String t = text.replaceAll("[\\r\\n]+", " ").trim();
		if (t.regionMatches(true, 0, "[PFP]", 0, 5)) t = t.substring(5).trim();
		String msg = "[PFP] " + t;
		if (msg.length() > 250) msg = msg.substring(0, 250);
		player.connection.sendCommand("pc " + msg);
		awaitEcho = msg;
		echoRetries = 0;
		echoDeadline = tickNow + ECHO_WAIT;
		if (PfpConfig.debug) PartyLog.debug("sent party message: " + msg);
	}

	/** Hypixel sometimes drops a command: we expect our own message to come back in party chat, otherwise it is sent again. */
	private static final int ECHO_WAIT = 14; // ticks
	private static String awaitEcho;
	private static int echoDeadline, echoRetries;

	static void announceAdded(String name, String reason) {
		if (PfpConfig.announceAdd) partyMsg(PfpConfig.format(PfpConfig.addMsg, name, reason));
	}

	private static final int CHUNK = 220; // characters per party message
	private static final int MAX_CHUNKS = 4;
	private static final int CHUNK_GAP = 24; // ticks between messages of one answer
	private static final List<String> OUT = new ArrayList<>();
	private static int outAt;

	/** Answer to "!pfp", "!pfp list", "!pfp full list" or "!pfp <name>" in party chat. */
	private static void answer(String arg) {
		PfpConfig.purge();
		String a = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
		if (a.isEmpty()) {
			partyMsg(AD_TEXT);
		} else if (a.equals("list")) {
			int n = PfpConfig.entries.size();
			partyMsg(n + (n == 1 ? " player is" : " players are") + " on my PFP list");
		} else if (a.equals("full list")) {
			if (PfpConfig.entries.isEmpty()) { partyMsg("My PFP list is empty"); return; }
			StringBuilder cur = new StringBuilder("PFP list: ");
			List<String> parts = new ArrayList<>();
			for (PfpConfig.Entry e : PfpConfig.entries.values()) {
				if (cur.length() + e.name().length() + 2 > CHUNK) { parts.add(cur.toString()); cur = new StringBuilder(); }
				cur.append(cur.isEmpty() ? "" : ", ").append(e.name());
			}
			parts.add(cur.toString());
			if (parts.size() > MAX_CHUNKS) {
				parts = new ArrayList<>(parts.subList(0, MAX_CHUNKS));
				parts.set(MAX_CHUNKS - 1, parts.get(MAX_CHUNKS - 1) + " ...");
			}
			partyMsg(parts.get(0));
			OUT.clear();
			OUT.addAll(parts.subList(1, parts.size()));
			outAt = tickNow + CHUNK_GAP;
		} else {
			PfpConfig.Entry e = PfpConfig.entries.get(PfpConfig.key(a));
			if (e == null) { partyMsg(arg.trim() + " is not on my PFP list"); return; }
			long d = PfpConfig.daysLeft(e);
			partyMsg(e.name() + ": " + e.reason() + " (" + PfpConfig.modeLabel(e.mode() == null ? PfpConfig.BOTH : e.mode())
				+ (d >= 0 ? ", " + d + (d == 1 ? " day left" : " days left") : ", permanent") + ")");
		}
	}

	private static final int KICK_DELAY = 12; // Ticks (0,6 s)
	private static final int RETRY_GAP = 30; // Ticks (1,5 s)
	private static final int MAX_TRIES = 3;
	private static int tickNow = 0;

	private static final class PendingKick {
		final String name;
		int sendAt;
		int triesLeft;
		PendingKick(String name, int sendAt, int triesLeft) {
			this.name = name;
			this.sendAt = sendAt;
			this.triesLeft = triesLeft;
		}
	}

	private static final List<PendingKick> PENDING = new ArrayList<>();

	private static void tickKicks(Minecraft client) {
		tickNow++;
		var player = client.player;
		if (player == null) {
			PENDING.clear();
			WAITING.clear();
			isLeader = null;
			PartyLog.end();
			return;
		}
		// No answer to "/p list" in time: try anyway (a failed kick shows the popup).
		if (!WAITING.isEmpty() && tickNow >= checkUntil) {
			resolveCheck(true);
			isLeader = null; // a guess, don't remember it
		}
		if (!OUT.isEmpty() && tickNow >= outAt) {
			partyMsg(OUT.remove(0));
			outAt = tickNow + CHUNK_GAP;
		}
		if (awaitEcho != null && tickNow >= echoDeadline) {
			if (echoRetries++ < 2) {
				player.connection.sendCommand("pc " + awaitEcho);
				echoDeadline = tickNow + ECHO_WAIT;
				if (PfpConfig.debug) PartyLog.debug("no echo, resent party message: " + awaitEcho);
			} else {
				awaitEcho = null;
			}
		}
		PENDING.removeIf(k -> {
			if (tickNow < k.sendAt) return false;
			if (awaitEcho != null && tickNow < k.sendAt + 40) return false; // kick after the message was seen
			player.connection.sendCommand("p kick " + k.name);
			k.triesLeft--;
			k.sendAt = tickNow + RETRY_GAP;
			return k.triesLeft <= 0;
		});
	}

	/** "Party Leader: [MVP+] Name ●" from "/p list". */
	private static final Pattern LEADER = Pattern.compile("^Party Leader:\\s*(?:\\[[^\\]]+\\]\\s*)*(\\w{1,16})");
	/** Newer list format: "● [MVP+] Name (Leader)". */
	private static final Pattern LEADER_BULLET = Pattern.compile("^\\W*(?:\\[[^\\]]+\\]\\s*)*(\\w{1,16})\\s*\\(Leader\\)");
	private static final int CHECK_TIMEOUT = 40; // ticks to wait for the list
	private static final List<PfpConfig.Entry> WAITING = new ArrayList<>();
	private static int checkUntil = 0;
	private static int swallowUntil = 0;

	/** Whether we lead the current party: null = unknown. Tracked from Hypixel's chat messages. */
	private static Boolean isLeader = null;
	private static final Pattern P_JOINED = Pattern.compile("^You have joined (?:\\[[^\\]]+\\] )?(\\w{1,16})'?s? party!");
	private static final Pattern P_WITH = Pattern.compile("^You'll be partying with: (.*)$");
	private static final Pattern P_LEFT = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\w{1,16}) (?:has left the party|has been removed from the party|was removed from your party because they disconnected)");
	private static final Pattern RANKED = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)?(\\w{1,16})");
	private static final Pattern P_INVITED = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\w{1,16}) invited .*? to (?:your|the) party!");
	private static final Pattern P_TRANSFER = Pattern.compile("^The party was transferred to (?:\\[[^\\]]+\\] )?(\\w{1,16}) (?:by|because) ");
	private static final Pattern P_GONE = Pattern.compile("^(?:You left the party|You have been kicked from the party|You are not currently in a party|The party was disbanded|.* has disbanded the party)");

	private static final Pattern QUEUED = Pattern.compile("^Party Finder > Your party has been queued in the dungeon finder!$");
	private static final Pattern DUNGEON_START = Pattern.compile("^\\[NPC] Mort: Good luck\\.$");
	private static final Pattern DUNGEON_END = Pattern.compile("^\\s*Team Score: \\d+ \\(.{1,2}\\)");
	private static final Pattern KUUDRA_START = Pattern.compile("^\\[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!$");
	private static final Pattern KUUDRA_END = Pattern.compile("^\\[NPC] Elle: Good job everyone\\. A hard fought battle come to an end\\.");
	private static final Pattern SUPPLY = Pattern.compile("^(?:\\[[^\\]]*\\]\\s?)?(\\w{1,16}) recovered one of Elle's supplies! \\(\\d+/\\d+\\)");

	private static void trackParty(String text) {
		String me = Minecraft.getInstance().getUser().getName();
		Matcher m;
		if (PfpConfig.debug) PartyLog.debug(text);
		if (QUEUED.matcher(text).find()) {
			isLeader = true;
			PartyLog.ownParty(PfpConfig.DUNGEON);
		} else if (DUNGEON_START.matcher(text).find()) {
			PartyLog.runStart(PfpConfig.DUNGEON);
		} else if (KUUDRA_START.matcher(text).find()) {
			PartyLog.runStart(PfpConfig.KUUDRA);
		} else if (DUNGEON_END.matcher(text).find() || KUUDRA_END.matcher(text).find()) {
			PartyLog.runEnd();
		} else if ((m = SUPPLY.matcher(text)).find()) {
			PartyLog.supply(m.group(1));
		} else if ((m = P_JOINED.matcher(text)).find()) {
			isLeader = false;
			PartyLog.joined(m.group(1));
		} else if ((m = P_WITH.matcher(text)).find()) {
			List<String> names = new ArrayList<>();
			for (String part : m.group(1).split(",")) {
				Matcher n = RANKED.matcher(part.trim());
				if (n.find()) names.add(n.group(1));
			}
			PartyLog.members(names);
		} else if ((m = P_LEFT.matcher(text)).find()) {
			PartyLog.left(m.group(1));
		} else if ((m = P_INVITED.matcher(text)).find()) {
			if (m.group(1).equalsIgnoreCase(me) || m.group(1).equalsIgnoreCase("You")) isLeader = true;
		} else if ((m = P_TRANSFER.matcher(text)).find()) {
			isLeader = m.group(1).equalsIgnoreCase(me);
		} else if (P_GONE.matcher(text).find()) {
			isLeader = null;
			PartyLog.end();
		}
	}

	static void sendCoords() {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		p.connection.sendCommand("pc x: " + p.getBlockX() + " y: " + p.getBlockY() + " z: " + p.getBlockZ());
	}

	static void rebindCoords(int code) {
		coordsKey.setKey(code < 0 ? com.mojang.blaze3d.platform.InputConstants.UNKNOWN
			: com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(code));
		net.minecraft.client.KeyMapping.resetMapping();
		Minecraft.getInstance().options.save();
	}

	static String coordsKeyName() {
		return coordsKey == null ? "?" : coordsKey.getTranslatedKeyMessage().getString();
	}

	private static final int STACK_LOOKBACK = 12; // how many recent chat lines are searched for a repeat
	private static final Pattern COUNT_SUFFIX = Pattern.compile(" \\(x(\\d+)\\)$");

	/** @return true when the message repeats a recent chat line; that line is replaced by one with a higher counter. */
	@SuppressWarnings("unchecked")
	private static boolean stack(Component message) {
		String text = clean(message.getString());
		if (text.isBlank() || text.startsWith("-----")) return false;
		var chat = Minecraft.getInstance().gui.hud.getChat();
		try {
			var f = chat.getClass().getDeclaredField("allMessages");
			f.setAccessible(true);
			var all = (List<net.minecraft.client.multiplayer.chat.GuiMessage>) f.get(chat);
			int n = Math.min(all.size(), STACK_LOOKBACK);
			for (int i = 0; i < n; i++) {
				String line = clean(all.get(i).content().getString());
				int count = 1;
				Matcher m = COUNT_SUFFIX.matcher(line);
				if (m.find()) {
					count = Integer.parseInt(m.group(1));
					line = line.substring(0, m.start());
				}
				if (!line.equals(text)) continue;
				all.remove(i);
				chat.rescaleChat();
				chat.addServerSystemMessage(message.copy()
					.append(Component.literal(" (x" + (count + 1) + ")").withStyle(ChatFormatting.GRAY)));
				return true;
			}
		} catch (ReflectiveOperationException | RuntimeException ex) {
			return false;
		}
		return false;
	}

	/** An error in our chat handlers must never reach the game (it would drop the connection as a protocol error). */
	private static void guarded(ClientReceiveMessageEvents.Game h) {
		ClientReceiveMessageEvents.GAME.register((m, o) -> {
			try {
				h.onReceiveGameMessage(m, o);
			} catch (Throwable t) {
				org.slf4j.LoggerFactory.getLogger("pfp").warn("chat handler failed", t);
			}
		});
	}

	private static void guardedAllow(ClientReceiveMessageEvents.AllowGame h) {
		ClientReceiveMessageEvents.ALLOW_GAME.register((m, o) -> {
			try {
				return h.allowReceiveGameMessage(m, o);
			} catch (Throwable t) {
				org.slf4j.LoggerFactory.getLogger("pfp").warn("chat filter failed", t);
				return true;
			}
		});
	}

	/** Reads every class of this mod now, so replacing the jar while the game runs cannot break lazy class loading later. */
	private static void loadAllClasses() {
		try {
			var container = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("pfp");
			if (container.isEmpty()) return;
			for (java.nio.file.Path root : container.get().getRootPaths()) {
				try (var files = java.nio.file.Files.walk(root)) {
					for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
						String n = root.relativize(f).toString().replace('\\', '/');
						if (!n.endsWith(".class") || !n.startsWith("dev/pfp/")) continue;
						Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, PfpClient.class.getClassLoader());
					}
				}
			}
		} catch (Throwable t) {
			org.slf4j.LoggerFactory.getLogger("pfp").warn("could not preload classes", t);
		}
	}

	private static String clean(String s) {
		return s.replace(' ', ' ').replaceAll("§.", "");
	}

	/** Blacklisted player joined: ask Hypixel who leads the party before doing anything. */
	private static void queueCheck(PfpConfig.Entry e) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		if (isLeader != null) { // known from chat: no need to ask
			WAITING.add(e);
			resolveCheck(isLeader);
			return;
		}
		if (WAITING.isEmpty()) {
			player.connection.sendCommand("p list");
			checkUntil = tickNow + CHECK_TIMEOUT;
		}
		WAITING.add(e);
	}

	private static void resolveCheck(boolean leader) {
		isLeader = leader;
		List<PfpConfig.Entry> list = new ArrayList<>(WAITING);
		WAITING.clear();
		for (PfpConfig.Entry e : list) {
			if (leader) {
				// Message first, then kick a moment later (Hypixel drops a second command in the same tick).
				partyMsg(PfpConfig.format(PfpConfig.kickMsg, e.name(), e.reason()));
				PartyLog.kicked(e.name());
				PENDING.add(new PendingKick(e.name(), tickNow + KICK_DELAY, MAX_TRIES));
			} else {
				notLeader(e.name(), e.reason());
			}
		}
	}

	/** Result of the requirement check: missing = null (unknown), "" (fine) or the list of missing items. */
	static void requirementResult(String name, String missing) {
		if (missing == null) {
			SecretTracker.chat("Could not check " + name + " (inventory API off or lookup failed), not kicked.", ChatFormatting.YELLOW);
		} else if (!missing.isEmpty()) {
			if (isLeader == null) { // unknown: ask "/p list" first, then kick or warn
				queueCheck(new PfpConfig.Entry(name, "does not meet the requirement: " + missing, System.currentTimeMillis(), null, null));
			} else if (isLeader) {
				partyMsg(name + " does not meet the requirement: " + missing);
				PartyLog.kicked(name);
				PENDING.add(new PendingKick(name, tickNow + KICK_DELAY, MAX_TRIES));
			} else {
				SecretTracker.chat(name + " does not meet the requirement: " + missing + " (you are not the leader or it is unknown, nobody kicked)", ChatFormatting.RED);
			}
		}
	}

	private static void notLeader(String name, String reason) {
		var mc = Minecraft.getInstance();
		net.minecraft.client.gui.components.toasts.SystemToast.add(mc.gui.toastManager(),
			net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
			Component.literal("PFP: " + name + " is blacklisted"),
			Component.literal("You are not party leader (" + reason + ")"));
	}

	/** Hypixel meldet den Kick -> nicht weiter wiederholen. */
	private static void cancelIfKicked(String text) {
		String t = text.toLowerCase(Locale.ROOT);
		if (t.contains("not this party's leader") || t.contains("not the party leader") || t.contains("you are not in a party")) {
			for (PendingKick k : PENDING) {
				PfpConfig.Entry e = PfpConfig.entries.get(PfpConfig.key(k.name));
				notLeader(k.name, e == null ? "" : e.reason());
			}
			PENDING.clear();
			return;
		}
		PENDING.removeIf(k -> {
			String n = k.name.toLowerCase(Locale.ROOT);
			return t.contains(n + " has been removed from the party")
				|| t.contains(n + " has left the party")
				|| t.contains(n + " is not in your party")
				|| t.contains(n + " is not in the party");
		});
	}

	@Override
	public void onInitializeClient() {
		loadAllClasses();
		PfpConfig.load();
		PartyLog.load();
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
			if (PfpConfig.enabled && PfpConfig.tooltipWarn) PartyLog.tooltip(lines);
		});

		coordsKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
			new net.minecraft.client.KeyMapping("key.pfp.coords", -1 /* unbound by default */,
				net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("pfp", "main"))));

		// Der Chat schliesst sich nach dem Befehl, daher GUI erst im naechsten Tick oeffnen.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openGui) {
				openGui = false;
				client.setScreenAndShow(new PfpScreen());
			}
			tickKicks(client);
			while (coordsKey.consumeClick()) {
				sendCoords();
			}
		});

		guarded((message, overlay) -> {
			if (!overlay) trackParty(clean(message.getString()));
		});

		guarded((message, overlay) -> {
			if (overlay || !PfpConfig.enabled) return;
			String text = clean(message.getString());
			if (awaitEcho != null && text.startsWith("Party >") && text.endsWith(awaitEcho)) awaitEcho = null;
			cancelIfKicked(text);
			Matcher ad = PARTY_AD.matcher(text);
			if (PfpConfig.adEnabled && ad.find() && tickNow - lastAd >= AD_COOLDOWN) {
				lastAd = tickNow;
				answer(ad.group(2));
				return;
			}
			String mode = null; // unbekannt (Einladung)
			Matcher m = PF_JOIN.matcher(text);
			if (m.find()) {
				mode = text.contains("dungeon group") ? PfpConfig.DUNGEON : PfpConfig.KUUDRA;
				isLeader = true; // only the leader of a Party Finder group sees "X joined the group!"
			} else {
				if (PfpConfig.pfOnly) return;
				m = PARTY_JOIN.matcher(text);
				if (!m.find()) return;
			}
			PartyLog.memberJoin(m.group(1), mode);
			GearCheck.check(m.group(1));
			PfpConfig.purge();
			PfpConfig.Entry e = PfpConfig.entries.get(PfpConfig.key(m.group(1)));
			var player = Minecraft.getInstance().player;
			if (player == null) return;
			if (e == null || !PfpConfig.applies(e, mode)) return;
			queueCheck(e);
		});

		// Identical consecutive chat lines become one line with "(x2)", "(x3)", ...
		guardedAllow((message, overlay) -> {
			if (overlay) return true;
			// our own party message coming back (must be seen here: a stacked duplicate never reaches the GAME event)
			if (awaitEcho != null) {
				String t = clean(message.getString());
				if (t.startsWith("Party >") && t.endsWith(awaitEcho)) awaitEcho = null;
			}
			if (!PfpConfig.stackChat) return true;
			return !stack(message);
		});

		// While waiting for the "/p list" answer, hide that list from the chat.
		guardedAllow((message, overlay) -> {
			if (overlay) return true;
			if (SecretTracker.capture(clean(message.getString()))) return false;
			if (WAITING.isEmpty() && tickNow >= swallowUntil) return true;
			String text = clean(message.getString());
			Matcher lm = LEADER.matcher(text);
			boolean isLeaderLine = lm.find();
			if (!isLeaderLine) {
				lm = LEADER_BULLET.matcher(text);
				isLeaderLine = lm.find();
			}
			if (isLeaderLine && !WAITING.isEmpty()) {
				swallowUntil = tickNow + 10;
				resolveCheck(lm.group(1).equalsIgnoreCase(Minecraft.getInstance().getUser().getName()));
				return false;
			}
			if (text.contains("You are not currently in a party")) {
				WAITING.clear();
				return false;
			}
			return !(text.startsWith("-----") || text.isBlank() || text.startsWith("Party Members")
				|| text.startsWith("Party Moderators") || text.startsWith("Party Leader") || text.startsWith("[Warp]")
				|| (!Character.isLetterOrDigit(text.charAt(0)) && text.charAt(0) != '[')); // member lines start with a bullet
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
			ClientCommands.literal("pfp")
				.executes(c -> { openGui = true; return 1; })
				.then(addNode("add", PfpConfig.BOTH))
				.then(addNode("dungeon", PfpConfig.DUNGEON))
				.then(addNode("kuudra", PfpConfig.KUUDRA))
				.then(ClientCommands.literal("remove")
					.then(ClientCommands.argument("name", StringArgumentType.word()).executes(PfpClient::remove)))
				.then(ClientCommands.literal("list").executes(PfpClient::show))
				.then(ClientCommands.literal("apikey")
					.then(ClientCommands.argument("key", StringArgumentType.word()).executes(c -> {
						String k = StringArgumentType.getString(c, "key");
						PfpConfig.apiKey = k.equalsIgnoreCase("clear") ? "" : k;
						PfpConfig.save();
						return say(c, PfpConfig.apiKey.isEmpty() ? "API key removed" : "API key saved (it is never shown again)", ChatFormatting.GREEN);
					})))
				.then(ClientCommands.literal("debug")
					.executes(c -> { PfpConfig.debug = !PfpConfig.debug; PfpConfig.save(); return say(c, PfpConfig.debug ? "Debug log on: config/pfp_debug.log" : "Debug log off", ChatFormatting.GREEN); })
					.then(ClientCommands.literal("on").executes(c -> { PfpConfig.debug = true; PfpConfig.save(); return say(c, "Debug log on: config/pfp_debug.log", ChatFormatting.GREEN); }))
					.then(ClientCommands.literal("off").executes(c -> { PfpConfig.debug = false; PfpConfig.save(); return say(c, "Debug log off", ChatFormatting.GREEN); })))
				.then(ClientCommands.literal("tag")
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.then(ClientCommands.argument("tag", StringArgumentType.word())
							.suggests((c, b) -> { for (String t : PfpConfig.TAGS) b.suggest(t); b.suggest("none"); return b.buildFuture(); })
							.executes(PfpClient::tag))))
				.then(ClientCommands.literal("msg")
					.then(msgNode("kick", t -> PfpConfig.kickMsg = t))
					.then(msgNode("add", t -> PfpConfig.addMsg = t)))
				.then(ClientCommands.literal("coords").executes(c -> { sendCoords(); return 1; }))
				.then(ClientCommands.literal("gui").executes(c -> { openGui = true; return 1; }))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> addNode(String literal, String mode) {
		return ClientCommands.literal(literal)
			.then(ClientCommands.argument("name", StringArgumentType.word())
				.executes(c -> add(c, "blacklisted", mode))
				.then(ClientCommands.argument("reason", StringArgumentType.greedyString())
					.executes(c -> add(c, StringArgumentType.getString(c, "reason"), mode))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> msgNode(String literal, java.util.function.Consumer<String> setter) {
		return ClientCommands.literal(literal)
			.then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes(c -> {
				setter.accept(StringArgumentType.getString(c, "text"));
				PfpConfig.save();
				return say(c, "Text saved (\"[PFP]\" is always added in front)", ChatFormatting.GREEN);
			}));
	}

	private static int add(CommandContext<FabricClientCommandSource> c, String reason, String mode) {
		String name = StringArgumentType.getString(c, "name");
		PfpConfig.Parsed p = PfpConfig.parse(reason);
		String r = p.reason().isEmpty() ? "blacklisted" : p.reason();
		PfpConfig.add(name, r, mode, p.days());
		announceAdded(name, r);
		String dur = p.days() == null ? "" : p.days() == 0 ? ", permanent" : ", " + p.days() + " D";
		return say(c, name + " added (" + PfpConfig.modeLabel(mode) + dur + "): " + r, ChatFormatting.GREEN);
	}

	private static int tag(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "name");
		String t = StringArgumentType.getString(c, "tag");
		if (t.equalsIgnoreCase("none")) {
			PfpConfig.tags.remove(PfpConfig.key(name));
			PfpConfig.save();
			return say(c, "Tag removed from " + name, ChatFormatting.GREEN);
		}
		for (String known : PfpConfig.TAGS) {
			if (known.equalsIgnoreCase(t)) {
				PfpConfig.tags.put(PfpConfig.key(name), known);
				PfpConfig.save();
				return say(c, name + " tagged " + known, ChatFormatting.GREEN);
			}
		}
		return say(c, "Unknown tag. Use: " + String.join(", ", PfpConfig.TAGS) + ", none", ChatFormatting.RED);
	}

	private static int remove(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "name");
		boolean ok = PfpConfig.remove(name);
		return say(c, ok ? name + " removed" : name + " is not on the list", ok ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private static int show(CommandContext<FabricClientCommandSource> c) {
		if (PfpConfig.entries.isEmpty()) return say(c, "The list is empty", ChatFormatting.GRAY);
		PfpConfig.entries.values().forEach(e -> say(c, e.name() + " [" + PfpConfig.modeLabel(e.mode()) + "] - " + e.reason(), ChatFormatting.AQUA));
		return PfpConfig.entries.size();
	}

	private static int say(CommandContext<FabricClientCommandSource> c, String msg, ChatFormatting color) {
		c.getSource().sendFeedback(Component.literal("[PFP] " + msg).withStyle(color));
		return 1;
	}
}
