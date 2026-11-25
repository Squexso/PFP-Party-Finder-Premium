package dev.pfp;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;

/**
 * Requirement check for players who join the party: every comma separated item in the requirement setting (e.g. "Term, Juju")
 * must be in the player's inventory, armor, equipment or ender chest (read from the Hypixel SkyBlock API through the proxy).
 * If the data cannot be read (inventory API off, lookup failed) the result is unknown and nobody is kicked.
 */
final class GearCheck {
	private static final Map<String, String> ALIAS = Map.of("term", "TERMINATOR", "hype", "HYPERION", "juju", "JUJU_SHORTBOW", "bonemerang", "BONE_BOOMERANG");

	private GearCheck() { }

	static void check(String name) {
		String req = PfpConfig.requirement.trim();
		if (req.isEmpty() || name.equalsIgnoreCase(PartyLog.me())) return;
		CompletableFuture.runAsync(() -> {
			Set<String> ids = SecretTracker.gear(name);
			String missing = ids == null ? null : missing(req, ids);
			if (PfpConfig.debug) PartyLog.debug("gear check " + name + " req=" + req + " ids=" + (ids == null ? "unknown" : ids.size()) + " missing=" + missing);
			Minecraft.getInstance().execute(() -> PfpClient.requirementResult(name, missing));
		});
	}

	/** Comma separated list of requirements the player lacks, "" when all are present. */
	static String missing(String req, Set<String> ids) {
		StringBuilder out = new StringBuilder();
		for (String raw : req.split(",")) {
			String t = raw.trim();
			if (t.isEmpty()) continue;
			String want = ALIAS.getOrDefault(t.toLowerCase(Locale.ROOT), t.toUpperCase(Locale.ROOT).replace(' ', '_'));
			if (ids.stream().noneMatch(id -> id.contains(want))) out.append(out.isEmpty() ? "" : ", ").append(t);
		}
		return out.toString();
	}
}
