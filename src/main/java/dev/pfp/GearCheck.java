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
	static final Map<String, String> ALIAS = Map.of("term", "TERMINATOR", "hype", "HYPERION", "juju", "JUJU_SHORTBOW", "bonemerang", "BONE_BOOMERANG");

	private GearCheck() { }

	static void check(String name) {
		if (PfpConfig.reqs.isEmpty() || name.equalsIgnoreCase(PartyLog.me())) return;
		java.util.List<PfpConfig.Req> req = new java.util.ArrayList<>(PfpConfig.reqs);
		CompletableFuture.runAsync(() -> {
			Set<String> ids = SecretTracker.gear(name);
			String missing = ids == null ? null : missing(req, ids);
			if (PfpConfig.debug) PartyLog.debug("gear check " + name + " req=" + req.size() + " ids=" + (ids == null ? "unknown" : ids.size()) + " missing=" + missing);
			Minecraft.getInstance().execute(() -> PfpClient.requirementResult(name, missing));
		});
	}

	/** Names of the required items the player lacks, "" when all are present. */
	static String missing(java.util.List<PfpConfig.Req> req, Set<String> ids) {
		StringBuilder out = new StringBuilder();
		for (PfpConfig.Req r : req) {
			if (ids.stream().noneMatch(id -> id.contains(r.id))) out.append(out.isEmpty() ? "" : ", ").append(r.name);
		}
		return out.toString();
	}

	/** A requirement typed as text, e.g. "Term" or "JUJU_SHORTBOW". */
	static PfpConfig.Req req(String text) {
		PfpConfig.Req r = new PfpConfig.Req();
		r.name = text;
		r.id = ALIAS.getOrDefault(text.toLowerCase(Locale.ROOT), text.toUpperCase(Locale.ROOT).replace(' ', '_'));
		r.icon = "minecraft:paper";
		return r;
	}
}
