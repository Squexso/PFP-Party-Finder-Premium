package dev.pfp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;

public class PfpScreen extends Screen {
	private static final int PAD = 20;
	private static final int HDR = 28;
	private static final int ROW_H = 28;
	private static final int ROW_PITCH = 32;
	private static final int HEAD_H = 82;
	private static final int FOOT_H = 70;

	private static int filter = 0; // 0 alle, 1 Dungeons, 2 Kuudra
	private static String addMode = PfpConfig.BOTH;

	private int PW = 420;
	private int IW = PW - 2 * PAD;
	private int page;
	private int rows = 4;
	private String nameText = "";
	private String reasonText = "";
	private EditBox nameBox, reasonBox;
	private boolean listening = false;
	private static int tab = 1; // 0 Overview, 1 Blacklist, 2 History, 3 Gear, 4 Settings
	private static int hFilter = 0; // 0 all, 1 dungeons, 2 kuudra
	private static int hSel = -1; // selected history row (index into the filtered list), -1 = list
	private int hPage;
	private final List<EditBox> textBoxes = new ArrayList<>();

	private int x0, y0, panelH, listTop;

	public PfpScreen() {
		super(Component.literal("PFP"));
		PfpConfig.purge();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (listening) {
			int k = event.key();
			listening = false;
			if (k != 256) PfpClient.rebindCoords(k == 259 || k == 261 ? -1 : k); // Esc cancels, Backspace/Delete unbinds
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	private static int gMode = 0; // gear tab: 0 list, 1 inventory, 2 presets
	private static String gearText = "";

	private record Slot(int x, int y, int w, int h, PfpConfig.Req req, ItemStack stack) { }

	private final List<Slot> slots = new ArrayList<>();
	private static final java.util.Map<String, ItemStack> ICONS = new java.util.HashMap<>();

	private static final String[][] PRESETS = {
		{"Terminator", "TERMINATOR", "minecraft:bow"}, {"Juju Shortbow", "JUJU_SHORTBOW", "minecraft:bow"},
		{"Last Breath", "LAST_BREATH", "minecraft:bow"}, {"Hyperion", "HYPERION", "minecraft:iron_sword"},
		{"Livid Dagger", "LIVID_DAGGER", "minecraft:iron_sword"}, {"Giants Sword", "GIANTS_SWORD", "minecraft:iron_sword"},
		{"Claymore", "CLAYMORE", "minecraft:iron_sword"}, {"Bonemerang", "BONE_BOOMERANG", "minecraft:bone"},
		{"Spirit Mask", "SPIRIT_MASK", "minecraft:player_head"}, {"Bonzo Mask", "BONZO_MASK", "minecraft:player_head"},
	};

	private static ItemStack icon(String id) {
		return ICONS.computeIfAbsent(id, k -> {
			var ident = net.minecraft.resources.Identifier.tryParse(k);
			ItemStack st = ident == null ? ItemStack.EMPTY : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident));
			return st.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.PAPER) : st;
		});
	}

	private static boolean hasReq(String id) {
		for (PfpConfig.Req r : PfpConfig.reqs) if (r.id.equalsIgnoreCase(id)) return true;
		return false;
	}

	private void addReq(PfpConfig.Req r) {
		if (hasReq(r.id)) return;
		PfpConfig.reqs.add(r);
		PfpConfig.save();
	}

	private static final String[] TAB_NAMES = {"Overview", "Blacklist", "History", "Gear", "Settings"};
	private static final int[] TAB_W = {54, 56, 48, 40, 52};
	private static final int H_PITCH = 36;
	private int hRows = 4;
	private boolean compact;

	private record Hit(int x, int y, int w, int h, String name) { }

	private final List<Hit> hits = new ArrayList<>();

	private FlatButton toggle(int x, int y, int w, String label, java.util.function.BooleanSupplier on, Runnable flip) {
		return addRenderableWidget(new FlatButton(x, y, w, 20, label, FlatButton.Style.NORMAL, on, () -> { flip.run(); PfpConfig.save(); }));
	}

	@Override
	protected void init() {
		PW = Math.min(460, width - 12);
		IW = PW - 2 * PAD;
		rows = Math.max(2, Math.min(8, (height - 16 - HDR - HEAD_H - FOOT_H) / ROW_PITCH));
		hRows = Math.max(2, Math.min(4, (height - 16 - HDR - 52 - 40) / H_PITCH));
		compact = height < 334 + HDR; // small screens / large GUI scale: tighter settings layout, no hints
		panelH = switch (tab) {
			case 0 -> Math.min(262, height - 16 - HDR);
			case 2 -> 52 + hRows * H_PITCH + 36;
			case 3 -> Math.min(300, height - 16 - HDR);
			case 4 -> compact ? 230 : 318;
			default -> HEAD_H + FOOT_H + rows * ROW_PITCH - 4;
		};
		x0 = (width - PW) / 2;
		y0 = Math.max(8 + HDR, (height - panelH - HDR) / 2 + HDR);
		listTop = y0 + HEAD_H;
		int left = x0 + PAD;
		textBoxes.clear();

		int tw = IW / TAB_NAMES.length;
		for (int i = 0; i < TAB_NAMES.length; i++) {
			int idx = i;
			addRenderableWidget(new FlatButton(left + i * tw, y0 + 8, tw - 2, 22, TAB_NAMES[i],
				tab == i ? FlatButton.Style.TAB_ON : FlatButton.Style.TAB, null, () -> { tab = idx; hSel = -1; rebuildWidgets(); }));
		}

		if (tab == 0) return;

		if (tab == 2) {
			if (hSel >= 0) {
				addRenderableWidget(new FlatButton(left + IW - 60, y0 + 40, 60, 20, "Back", FlatButton.Style.NORMAL, null, () -> { hSel = -1; rebuildWidgets(); }));
				return;
			}
			String[] fl = {"All", "Dungeons", "Kuudra"};
			for (int i = 0; i < 3; i++) {
				int idx = i;
				addRenderableWidget(new FlatButton(left + i * 66, y0 + 40, 62, 20, fl[i],
					hFilter == i ? FlatButton.Style.TAB_ON : FlatButton.Style.NORMAL, null, () -> { hFilter = idx; hPage = 0; rebuildWidgets(); }));
			}
			int pages = Math.max(1, (history().size() + hRows - 1) / hRows);
			hPage = Math.max(0, Math.min(hPage, pages - 1));
			FlatButton hp = new FlatButton(left + IW - 46, y0 + panelH - 28, 20, 18, "<", FlatButton.Style.NORMAL, null, () -> { hPage--; rebuildWidgets(); });
			FlatButton hn = new FlatButton(left + IW - 20, y0 + panelH - 28, 20, 18, ">", FlatButton.Style.NORMAL, null, () -> { hPage++; rebuildWidgets(); });
			hp.active = hPage > 0;
			hn.active = hPage < pages - 1;
			addRenderableWidget(hp);
			addRenderableWidget(hn);
			return;
		}

		if (tab == 3) {
			int bw = IW - 66;
			textBoxes.add(field(left, y0 + 38, bw, "Add item by name or id (e.g. Term)", 40, gearText, v -> gearText = v));
			addRenderableWidget(new FlatButton(left + bw + 6, y0 + 39, 60, 20, "Add", FlatButton.Style.NORMAL, null, () -> {
				String t = gearText.trim();
				if (!t.isEmpty()) { addReq(GearCheck.req(t)); gearText = ""; }
				rebuildWidgets();
			}));
			String[] gm = {"My list", "Inventory", "Presets"};
			for (int i = 0; i < 3; i++) {
				int idx = i;
				addRenderableWidget(new FlatButton(left + i * 84, y0 + 66, 80, 20, gm[i],
					gMode == i ? FlatButton.Style.TAB_ON : FlatButton.Style.NORMAL, null, () -> { gMode = idx; rebuildWidgets(); }));
			}
			if (gMode == 0) {
				int max = Math.max(1, (panelH - 92 - 30) / 22);
				for (int i = 0; i < Math.min(max, PfpConfig.reqs.size()); i++) {
					PfpConfig.Req r = PfpConfig.reqs.get(i);
					addRenderableWidget(new FlatButton(left + IW - 22, y0 + 92 + i * 22 + 1, 20, 18, "x", FlatButton.Style.NORMAL, null, () -> {
						PfpConfig.reqs.remove(r);
						PfpConfig.save();
						rebuildWidgets();
					}));
				}
			}
			return;
		}

		if (tab == 4) {
			int f1 = compact ? 46 : 52;
			int f2 = compact ? 84 : 96;
			textBoxes.add(field(left, y0 + f1, IW, "Kick message", 150, PfpConfig.kickMsg, v -> { PfpConfig.kickMsg = v; PfpConfig.save(); }));
			textBoxes.add(field(left, y0 + f2, IW, "Added message", 150, PfpConfig.addMsg, v -> { PfpConfig.addMsg = v; PfpConfig.save(); }));
			int bw2 = (IW - 6) / 2;
			int xr = left + bw2 + 6;
			int wr = IW - bw2 - 6;
			int y = y0 + (compact ? 116 : 138);
			int pitch = compact ? 22 : 24;
			toggle(left, y, bw2, "Announce adds", () -> PfpConfig.announceAdd, () -> PfpConfig.announceAdd = !PfpConfig.announceAdd);
			toggle(xr, y, wr, "!pfp replies", () -> PfpConfig.adEnabled, () -> PfpConfig.adEnabled = !PfpConfig.adEnabled);
			y += pitch;
			toggle(left, y, bw2, "Tooltip warning", () -> PfpConfig.tooltipWarn, () -> PfpConfig.tooltipWarn = !PfpConfig.tooltipWarn);
			toggle(xr, y, wr, "Show reason", () -> PfpConfig.tooltipReason, () -> PfpConfig.tooltipReason = !PfpConfig.tooltipReason);
			y += pitch;
			toggle(left, y, bw2, "Party check", () -> PfpConfig.partyCheck, () -> PfpConfig.partyCheck = !PfpConfig.partyCheck);
			toggle(xr, y, wr, "Repeat alerts", () -> PfpConfig.repeatAlerts, () -> PfpConfig.repeatAlerts = !PfpConfig.repeatAlerts);
			y += pitch;
			toggle(left, y, bw2, "Record history", () -> PfpConfig.historyOn, () -> PfpConfig.historyOn = !PfpConfig.historyOn);
			toggle(xr, y, wr, "Stack chat lines", () -> PfpConfig.stackChat, () -> PfpConfig.stackChat = !PfpConfig.stackChat);
			y += pitch;
			toggle(left, y, bw2, "Secret tracker", () -> PfpConfig.secretTracker, () -> PfpConfig.secretTracker = !PfpConfig.secretTracker);
			toggle(xr, y, wr, "Debug log", () -> PfpConfig.debug, () -> PfpConfig.debug = !PfpConfig.debug);
			y += pitch;
			addRenderableWidget(new FlatButton(left, y, IW, 20,
				listening ? "Press a key ..." : "Coords key: " + PfpClient.coordsKeyName(),
				listening ? FlatButton.Style.TAB_ON : FlatButton.Style.NORMAL, null, () -> { listening = true; rebuildWidgets(); }));
			return;
		}

		int addY = y0 + 42;
		int nameW = Math.min(96, IW * 27 / 100), modeW = 64, addW = 54;
		int reasonW = IW - nameW - modeW - addW - 18;
		nameBox = field(left, addY, nameW, "Name", 16, nameText, s -> nameText = s);
		reasonBox = field(left + nameW + 6, addY, reasonW, "Reason  7D | inf", 100, reasonText, s -> reasonText = s);
		addRenderableWidget(new FlatButton(left + nameW + reasonW + 12, addY, modeW, 22, PfpConfig.modeLabel(addMode),
			FlatButton.Style.NORMAL, null, () -> {
				addMode = PfpConfig.BOTH.equals(addMode) ? PfpConfig.DUNGEON : PfpConfig.DUNGEON.equals(addMode) ? PfpConfig.KUUDRA : PfpConfig.BOTH;
				rebuildWidgets();
			}));
		addRenderableWidget(new FlatButton(left + IW - addW, addY, addW, 22, "Add",
			FlatButton.Style.PRIMARY, null, this::addEntry));

		List<PfpConfig.Entry> all = visible();
		int pages = Math.max(1, (all.size() + rows - 1) / rows);
		page = Math.max(0, Math.min(page, pages - 1));
		int sy = listTop + rows * ROW_PITCH - 4 + 12;
		int navY = sy + 28;
		FlatButton next = new FlatButton(left + IW - 22, navY, 22, 20, ">",
			FlatButton.Style.NORMAL, null, () -> { page++; rebuildWidgets(); });
		FlatButton prev = new FlatButton(left + IW - 22 - 36 - 22, navY, 22, 20, "<",
			FlatButton.Style.NORMAL, null, () -> { page--; rebuildWidgets(); });
		prev.active = page > 0;
		next.active = page < pages - 1;
		addRenderableWidget(prev);
		addRenderableWidget(next);
		addRenderableWidget(new FlatButton(left, navY, 78, 20,
			filter == 0 ? "All modes" : filter == 1 ? "Dungeons" : "Kuudra",
			FlatButton.Style.NORMAL, null, () -> { filter = (filter + 1) % 3; page = 0; rebuildWidgets(); }));

		for (int i = 0; i < rows; i++) {
			int idx = page * rows + i;
			if (idx >= all.size()) break;
			String n = all.get(idx).name();
			addRenderableWidget(new FlatButton(left + IW - 22, listTop + i * ROW_PITCH, 22, ROW_H, "x",
				FlatButton.Style.DANGER, null, () -> { PfpConfig.remove(n); rebuildWidgets(); }));
		}

		int bw = (IW - 6) / 2;
		addRenderableWidget(new FlatButton(left, sy, bw, 22, "Mod enabled",
			FlatButton.Style.NORMAL, () -> PfpConfig.enabled, () -> {
				PfpConfig.enabled = !PfpConfig.enabled;
				PfpConfig.save();
			}));
		addRenderableWidget(new FlatButton(left + bw + 6, sy, IW - bw - 6, 22, "Party Finder only",
			FlatButton.Style.NORMAL, () -> PfpConfig.pfOnly, () -> {
				PfpConfig.pfOnly = !PfpConfig.pfOnly;
				PfpConfig.save();
			}));
		addRenderableWidget(new FlatButton(left + 78 + 6, navY, IW - 78 - 6 - 22 - 36 - 22 - 6 - 6, 20, expLabel(),
			FlatButton.Style.NORMAL, null, () -> {
				PfpConfig.cycleExpiry();
				rebuildWidgets();
			}));
	}

	private EditBox field(int x, int y, int w, String hint, int max, String value, Consumer<String> onChange) {
		EditBox box = new EditBox(font, x + 8, y + 7, w - 16, 10, Component.literal(hint));
		box.setBordered(false);
		box.setMaxLength(max);
		box.setTextColor(PfpTheme.TEXT);
		box.addFormatter((s, off) -> FormattedCharSequence.forward(s, PfpTheme.style()));
		box.setHint(PfpTheme.text(hint).withStyle(s -> s.withColor(PfpTheme.MUTED & 0xFFFFFF)));
		box.setValue(value);
		box.setResponder(onChange);
		addRenderableWidget(box);
		return box;
	}

	private void addEntry() {
		String n = nameText.trim();
		if (!n.matches("\\w{1,16}")) return;
		PfpConfig.Parsed p = PfpConfig.parse(reasonText);
		String r = p.reason().isEmpty() ? "blacklisted" : p.reason();
		PfpConfig.add(n, r, addMode, p.days());
		PfpClient.announceAdded(n, r);
		nameText = "";
		reasonText = "";
		rebuildWidgets();
	}

	private static List<PfpConfig.Entry> visible() {
		List<PfpConfig.Entry> out = new ArrayList<>();
		for (PfpConfig.Entry e : PfpConfig.entries.values()) {
			String m = e.mode() == null ? PfpConfig.BOTH : e.mode();
			if (filter == 0 || m.equals(PfpConfig.BOTH)
				|| (filter == 1 && m.equals(PfpConfig.DUNGEON)) || (filter == 2 && m.equals(PfpConfig.KUUDRA))) out.add(e);
		}
		return out;
	}

	private static int modeColor(String mode) {
		return PfpConfig.KUUDRA.equals(mode) ? 0xFFFF8A4D : PfpConfig.DUNGEON.equals(mode) ? PfpTheme.AQUA : PfpTheme.TEXT_2;
	}

	private static String expLabel() {
		return "Auto-remove: " + (PfpConfig.expireDays == 0 ? "never" : PfpConfig.expireDays + " days");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, PfpTheme.DIM);
		PfpTheme.round(g, x0 - 1, y0 - HDR - 1, PW + 2, panelH + HDR + 2, PfpTheme.HAIR);
		PfpTheme.round(g, x0, y0 - HDR, PW, panelH + HDR, PfpTheme.PANEL);
		Component logo = PfpTheme.medium("PFP");
		PfpTheme.draw(g, logo, x0 + PAD, y0 - HDR + 9, PfpTheme.PURPLE);
		PfpTheme.draw(g, PfpTheme.text("Party Finder Premium"), x0 + PAD + PfpTheme.width(logo) + 6, y0 - HDR + 9, PfpTheme.MUTED);
		Component st = PfpTheme.text(PfpConfig.enabled ? "Protection on" : "Protection off");
		PfpTheme.drawRight(g, st, x0 + PW - PAD, y0 - HDR + 9, PfpConfig.enabled ? PfpTheme.GREEN : PfpTheme.RED);
		PfpTheme.dot(g, x0 + PW - PAD - PfpTheme.width(st) - 11, y0 - HDR + 11, PfpConfig.enabled ? PfpTheme.GREEN : PfpTheme.RED);
		g.fill(x0 + PAD, y0 + 32, x0 + PW - PAD, y0 + 33, PfpTheme.HAIR);
		hits.clear();
		if (tab == 0) { drawOverview(g); return; }
		if (tab == 2) { drawHistory(g, mouseX, mouseY); return; }
		if (tab == 3) { drawGear(g, mouseX, mouseY); return; }
		if (tab == 4) { drawSettings(g); return; }

		int left = x0 + PAD;
		int right = left + IW;
		List<PfpConfig.Entry> all = visible();
		int pages = Math.max(1, (all.size() + rows - 1) / rows);

		int navY = listTop + rows * ROW_PITCH - 4 + 12 + 28;
		Component pageText = PfpTheme.text((page + 1) + "/" + pages);
		PfpTheme.draw(g, pageText, right - 22 - 18 - PfpTheme.width(pageText) / 2, navY + 6, PfpTheme.TEXT_2);

		drawField(g, nameBox);
		drawField(g, reasonBox);

		if (all.isEmpty()) {
			int cy = listTop + (rows * ROW_PITCH - 4) / 2 - 12;
			Component a = PfpTheme.medium("Nobody on the list yet");
			Component b = PfpTheme.text("Enter name, reason and mode above");
			PfpTheme.draw(g, a, x0 + (PW - PfpTheme.width(a)) / 2, cy, PfpTheme.TEXT_2);
			PfpTheme.draw(g, b, x0 + (PW - PfpTheme.width(b)) / 2, cy + 13, PfpTheme.MUTED);
		}
		int rowW = IW - 26;
		for (int i = 0; i < rows; i++) {
			int idx = page * rows + i;
			if (idx >= all.size()) break;
			PfpConfig.Entry e = all.get(idx);
			int y = listTop + i * ROW_PITCH;
			PfpTheme.round(g, left, y, rowW, ROW_H, PfpTheme.ROW);

			long d = PfpConfig.daysLeft(e);
			int status = d < 0 ? PfpTheme.AQUA : d <= 1 ? PfpTheme.RED : d <= 3 ? PfpTheme.YELLOW : PfpTheme.GREEN;
			g.fill(left, y + 5, left + 3, y + ROW_H - 5, status);

			int tailW = 0;
			if (d < 0 && e.days() != null) {
				Component tail = PfpTheme.text("permanent");
				PfpTheme.drawRight(g, tail, left + rowW - 10, y + 5, PfpTheme.TEXT_2);
				tailW = PfpTheme.width(tail) + 8;
			}
			if (d >= 0) {
				Component tail = PfpTheme.medium(d + (d == 1 ? " day left" : " days left"));
				PfpTheme.drawRight(g, tail, left + rowW - 10, y + 5, status);
				tailW = PfpTheme.width(tail) + 8;
			}
			Component nm = PfpTheme.medium(e.name());
			PfpTheme.draw(g, nm, left + 14, y + 4, PfpTheme.TEXT);
			String md = e.mode() == null ? PfpConfig.BOTH : e.mode();
			PfpTheme.draw(g, PfpTheme.text(PfpConfig.modeLabel(md)), left + 14 + PfpTheme.width(nm) + 8, y + 4, modeColor(md));
				String tag = PfpConfig.tagOf(e.name());
				int hitW = PfpTheme.width(nm) + 8 + PfpTheme.width(PfpTheme.text(PfpConfig.modeLabel(md)));
				if (tag != null) {
					PfpTheme.draw(g, PfpTheme.text(tag), left + 14 + hitW + 8, y + 4, PfpConfig.tagColor(tag));
					hitW += 8 + PfpTheme.width(PfpTheme.text(tag));
				}
				hits.add(new Hit(left + 14, y + 3, hitW, 12, e.name()));
			PfpTheme.draw(g, PfpTheme.fit(e.reason(), rowW - 14 - 10 - tailW), left + 14, y + 16, PfpTheme.TEXT_2);
		}

	}

	private static List<PartyLog.Session> history() {
		List<PartyLog.Session> out = new ArrayList<>();
		for (PartyLog.Session x : PartyLog.history) {
			if (hFilter == 0 || (hFilter == 1 && PfpConfig.DUNGEON.equals(x.type)) || (hFilter == 2 && PfpConfig.KUUDRA.equals(x.type))) out.add(x);
		}
		return out;
	}

	private static String hm(long t) {
		return java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
	}

	/** Per-player result of this session: Kuudra supplies or dungeon secrets. Unknown when it could not be read. */
	private static String extra(PartyLog.Session s, String name) {
		if (PfpConfig.KUUDRA.equals(s.type) && s.ran) {
			if (s.supplies == null) return "supplies unknown";
			Integer v = s.supplies.get(name);
			return (v == null ? 0 : v) + " supplies";
		}
		if (PfpConfig.DUNGEON.equals(s.type) && s.ran && s.secrets != null) {
			Integer v = s.secrets.get(name);
			return v == null ? "secrets unknown" : v == 0 ? "0 secrets (unconfirmed)" : v + " secrets";
		}
		return null;
	}

	private static void cycleTag(String name, boolean remove) {
		String cur = PfpConfig.tagOf(name);
		int i = -1;
		for (int j = 0; j < PfpConfig.TAGS.length; j++) if (PfpConfig.TAGS[j].equalsIgnoreCase(cur)) i = j;
		if (remove || i == PfpConfig.TAGS.length - 1) PfpConfig.tags.remove(PfpConfig.key(name));
		else PfpConfig.tags.put(PfpConfig.key(name), PfpConfig.TAGS[i + 1]);
		PfpConfig.save();
	}

	private static int seen(String name) {
		int c = 0;
		for (PartyLog.Session x : PartyLog.history) {
			if (name.equalsIgnoreCase(x.leader) || x.members.stream().anyMatch(name::equalsIgnoreCase)) c++;
		}
		return c;
	}

	/** Name, optional tag and optional blacklist reason on one row. Returns the next y. */
	private int playerRow(GuiGraphicsExtractor g, int x, int y, int w, String name, boolean leader, String extra) {
		PfpConfig.Entry e = PfpConfig.entries.get(PfpConfig.key(name));
		Component nm = PfpTheme.medium(name);
		PfpTheme.draw(g, nm, x, y, e != null ? PfpTheme.RED : PfpTheme.TEXT);
		int cx = x + PfpTheme.width(nm) + 6;
		if (leader) {
			Component l = PfpTheme.text("leader");
			PfpTheme.draw(g, l, cx, y, PfpTheme.PURPLE);
			cx += PfpTheme.width(l) + 6;
		}
		String tag = PfpConfig.tagOf(name);
		if (tag != null) {
			Component t = PfpTheme.text(tag);
			PfpTheme.draw(g, t, cx, y, PfpConfig.tagColor(tag));
			cx += PfpTheme.width(t) + 6;
		}
		int n = seen(name);
		if (n >= 2) {
			Component t = PfpTheme.text("seen " + n + "x");
			PfpTheme.draw(g, t, cx, y, PfpTheme.MUTED);
			cx += PfpTheme.width(t) + 6;
		}
		if (extra != null) {
			Component t = PfpTheme.text(extra);
			PfpTheme.draw(g, t, cx, y, extra.contains("nknown") || extra.contains("unconfirmed") ? PfpTheme.YELLOW : PfpTheme.TEXT_2);
			cx += PfpTheme.width(t) + 6;
		}
		hits.add(new Hit(x, y - 1, Math.max(40, cx - x), 12, name));
		if (e != null) PfpTheme.drawRight(g, PfpTheme.fit(e.reason(), Math.max(40, w / 2)), x + w, y, PfpTheme.RED);
		return y + 14;
	}

	private void drawOverview(GuiGraphicsExtractor g) {
		int l = x0 + PAD;
		int y = y0 + 40;
		PfpTheme.draw(g, PfpTheme.medium("Current party"), l, y, PfpTheme.TEXT);
		PartyLog.Session s = PartyLog.cur;
		if (s == null) {
			PfpTheme.draw(g, PfpTheme.text("Not in a party. Detected from chat when you join or invite."), l, y + 16, PfpTheme.MUTED);
		} else {
			PfpTheme.drawRight(g, PfpTheme.text(PartyLog.title(s)), l + IW, y, PfpTheme.TEXT_2);
			y += 18;
			if (s.leader != null && PfpConfig.entries.containsKey(PfpConfig.key(s.leader))) {
				PfpTheme.draw(g, PfpTheme.medium("Party leader is blacklisted"), l, y, PfpTheme.RED);
				y += 14;
			}
			int shown = 0;
			String me = net.minecraft.client.Minecraft.getInstance().getUser().getName();
			if (s.leader != null && !s.leader.equalsIgnoreCase(me)) {
				y = playerRow(g, l, y, IW, s.leader, true, null);
				shown++;
			}
			for (String m : PartyLog.present) {
				if (m.equalsIgnoreCase(s.leader)) continue;
				if (shown++ >= 4) break;
				y = playerRow(g, l, y, IW, m, false, null);
			}
			if (shown == 0) PfpTheme.draw(g, PfpTheme.text("Nobody else in the party yet"), l, y, PfpTheme.MUTED);
		}
		int ay = y0 + 138;
		PfpTheme.draw(g, PfpTheme.medium("Recent activity"), l, ay, PfpTheme.TEXT);
		ay += 16;
		if (PartyLog.activity.isEmpty()) PfpTheme.draw(g, PfpTheme.text("Nothing yet"), l, ay, PfpTheme.MUTED);
		for (PartyLog.Act a : PartyLog.activity) {
			if (ay > y0 + panelH - 26) break;
			PfpTheme.draw(g, PfpTheme.text(PartyLog.actTime(a)), l, ay, PfpTheme.MUTED);
			PfpTheme.draw(g, PfpTheme.fit(a.text, IW - 70), l + 66, ay, a.warn ? PfpTheme.RED : PfpTheme.TEXT_2);
			ay += 13;
		}
		PfpTheme.draw(g, PfpTheme.fit("Click a name to change its tag, right-click removes it", IW), l, y0 + panelH - 14, PfpTheme.MUTED);
	}

	private void drawHistory(GuiGraphicsExtractor g, int mx, int my) {
		int l = x0 + PAD;
		List<PartyLog.Session> list = history();
		if (hSel >= 0) {
			if (hSel >= list.size()) { hSel = -1; return; }
			PartyLog.Session s = list.get(hSel);
			PfpTheme.draw(g, PfpTheme.medium(PartyLog.title(s)), l, y0 + 44, PfpTheme.TEXT);
			int y = y0 + 68;
			String when = "Result: " + (s.result == null ? "Unknown" : s.result) + " - " + hm(s.joined) + (s.left > 0 ? " - " + hm(s.left) + " (" + Math.max(1, (s.left - s.joined) / 60000) + " min)" : "");
			PfpTheme.draw(g, PfpTheme.text(when), l, y, PfpTheme.TEXT_2);
			y += 18;
			String me = net.minecraft.client.Minecraft.getInstance().getUser().getName();
			if (extra(s, me) != null) y = playerRow(g, l, y, IW, me, s.leader != null && s.leader.equalsIgnoreCase(me), extra(s, me));
			if (s.leader != null && !s.leader.equalsIgnoreCase(me)) y = playerRow(g, l, y, IW, s.leader, true, extra(s, s.leader));
			for (String m : s.members) {
				if (m.equalsIgnoreCase(s.leader)) continue;
				if (y > y0 + panelH - 40) break;
				y = playerRow(g, l, y, IW, m, false, extra(s, m));
			}
			if (!s.kicked.isEmpty()) {
				PfpTheme.draw(g, PfpTheme.fit("Kicked by PFP: " + String.join(", ", s.kicked), IW), l, y + 4, PfpTheme.TEXT_2);
			}
			return;
		}
		PfpTheme.draw(g, PfpTheme.text("Open a party to see details and tags"), l, y0 + panelH - 25, PfpTheme.MUTED);
		int pages = Math.max(1, (list.size() + hRows - 1) / hRows);
		Component pt = PfpTheme.text((hPage + 1) + "/" + pages);
		PfpTheme.draw(g, pt, l + IW - 33 - PfpTheme.width(pt) / 2 - 20, y0 + panelH - 25, PfpTheme.TEXT_2);
		if (list.isEmpty()) {
			Component a = PfpTheme.medium("No parties recorded yet");
			PfpTheme.draw(g, a, x0 + (PW - PfpTheme.width(a)) / 2, y0 + 110, PfpTheme.TEXT_2);
			return;
		}
		for (int i = 0; i < hRows; i++) {
			int idx = hPage * hRows + i;
			if (idx >= list.size()) break;
			PartyLog.Session s = list.get(idx);
			int y = y0 + 68 + i * H_PITCH;
			boolean hot = mx >= l && mx < l + IW && my >= y && my < y + H_PITCH - 4;
			PfpTheme.round(g, l, y, IW, H_PITCH - 4, hot ? PfpTheme.BTN : PfpTheme.ROW);
			PfpTheme.draw(g, PfpTheme.medium(PartyLog.title(s)), l + 8, y + 4, PfpTheme.TEXT);
			PfpTheme.draw(g, PfpTheme.text((s.members.size() + 1) + " players · " + hm(s.joined) + (s.result == null ? "" : " · " + s.result)), l + 8, y + 17, PfpTheme.TEXT_2);
			if (!s.matches.isEmpty()) PfpTheme.drawRight(g, PfpTheme.medium(s.matches.size() + " blacklisted"), l + IW - 8, y + 4, PfpTheme.RED);
			if (!s.kicked.isEmpty()) PfpTheme.drawRight(g, PfpTheme.text(s.kicked.size() + " kicked"), l + IW - 8, y + 17, PfpTheme.MUTED);
		}
	}

	private void drawSettings(GuiGraphicsExtractor g) {
		int l = x0 + PAD;
		String[] labels = {"When someone is kicked", "When someone is added"};
		for (int i = 0; i < 2; i++) {
			EditBox box = textBoxes.get(i);
			PfpTheme.draw(g, PfpTheme.fit(labels[i], IW), l, box.getY() - 19, PfpTheme.TEXT_2);
			drawField(g, box);
		}
		if (compact) return;
		PfpTheme.draw(g, PfpTheme.fit("[PFP] " + PfpConfig.format(PfpConfig.kickMsg, "Name", "Reason"), IW), l, y0 + 288, PfpTheme.PURPLE);
		String key = PfpConfig.apiKey.isBlank() ? "no API key (/pfp apikey <key>)" : "API key set";
		PfpTheme.draw(g, PfpTheme.fit("{name} {reason} are replaced, [PFP] always first. Secret tracker: " + key, IW), l, y0 + 300, PfpTheme.MUTED);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean dbl) {
		if (tab != 4 && (event.button() == 0 || event.button() == 1)) {
			for (Hit h : hits) {
				if (event.x() >= h.x() && event.x() < h.x() + h.w() && event.y() >= h.y() && event.y() < h.y() + h.h()) {
					cycleTag(h.name(), event.button() == 1);
					return true;
				}
			}
		}
		if (tab == 3 && gMode != 0 && event.button() == 0) {
			for (Slot sl : slots) {
				if (event.x() >= sl.x() && event.x() < sl.x() + sl.w() && event.y() >= sl.y() && event.y() < sl.y() + sl.h()) {
					PfpConfig.Req r = sl.req();
					if (hasReq(r.id)) PfpConfig.reqs.removeIf(q -> q.id.equalsIgnoreCase(r.id));
					else PfpConfig.reqs.add(r);
					PfpConfig.save();
					return true;
				}
			}
		}
		if (tab == 2 && hSel < 0 && event.button() == 0) {
			int l = x0 + PAD;
			List<PartyLog.Session> list = history();
			for (int i = 0; i < hRows; i++) {
				int idx = hPage * hRows + i;
				int y = y0 + 68 + i * H_PITCH;
				if (idx < list.size() && event.x() >= l && event.x() < l + IW && event.y() >= y && event.y() < y + H_PITCH - 4) {
					hSel = idx;
					rebuildWidgets();
					return true;
				}
			}
		}
		return super.mouseClicked(event, dbl);
	}

	private void drawGear(GuiGraphicsExtractor g, int mx, int my) {
		int l = x0 + PAD;
		drawField(g, textBoxes.get(0));
		slots.clear();
		int top = y0 + 92;
		if (gMode == 0) {
			if (PfpConfig.reqs.isEmpty()) {
				PfpTheme.draw(g, PfpTheme.fit("No items yet. Type a name above, pick one from your inventory or use a preset.", IW), l, top + 4, PfpTheme.MUTED);
			}
			int max = Math.max(1, (panelH - 92 - 30) / 22);
			for (int i = 0; i < Math.min(max, PfpConfig.reqs.size()); i++) {
				PfpConfig.Req r = PfpConfig.reqs.get(i);
				int y = top + i * 22;
				PfpTheme.round(g, l, y, IW - 26, 20, PfpTheme.FIELD);
				g.item(icon(r.icon), l + 4, y + 2);
				Component nm = PfpTheme.medium(r.name);
				PfpTheme.draw(g, nm, l + 26, y + 6, PfpTheme.TEXT);
				PfpTheme.draw(g, PfpTheme.fit(r.id, IW - 26 - 40 - PfpTheme.width(nm)), l + 32 + PfpTheme.width(nm), y + 6, PfpTheme.MUTED);
			}
		} else if (gMode == 1) {
			PfpTheme.draw(g, PfpTheme.text("Click an item in your inventory to require it"), l, top, PfpTheme.TEXT_2);
			var player = net.minecraft.client.Minecraft.getInstance().player;
			if (player != null) {
				var inv = player.getInventory().getNonEquipmentItems();
				for (int i = 0; i < 36; i++) {
					int row = i < 9 ? 3 : (i - 9) / 9;
					int col = i % 9;
					int x = l + col * 20, y = top + 14 + row * 20;
					ItemStack st = inv.get(i);
					PfpTheme.round(g, x, y, 18, 18, PfpTheme.FIELD);
					if (st.isEmpty()) continue;
					var cd = st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
					String id = cd == null ? "" : cd.copyTag().getStringOr("id", "");
					g.item(st, x + 1, y + 1);
					if (id.isEmpty()) { g.fill(x + 1, y + 1, x + 17, y + 17, 0x99000000); continue; }
					PfpConfig.Req r = new PfpConfig.Req();
					r.id = id;
					r.name = st.getHoverName().getString();
					r.icon = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
					slots.add(new Slot(x, y, 18, 18, r, st));
					if (hasReq(id)) PfpTheme.draw(g, PfpTheme.text("+"), x + 11, y + 8, PfpTheme.GREEN);
				}
			}
		} else {
			PfpTheme.draw(g, PfpTheme.text("Click a preset to add or remove it"), l, top, PfpTheme.TEXT_2);
			int cw = (IW - 6) / 2;
			for (int i = 0; i < PRESETS.length; i++) {
				int x = l + (i % 2) * (cw + 6), y = top + 14 + (i / 2) * 22;
				String[] p = PRESETS[i];
				boolean on = hasReq(p[1]);
				PfpTheme.round(g, x, y, cw, 20, on ? PfpTheme.FIELD_FOCUS : PfpTheme.FIELD);
				g.item(icon(p[2]), x + 4, y + 2);
				PfpTheme.draw(g, PfpTheme.text(p[0]), x + 26, y + 6, on ? PfpTheme.GREEN : PfpTheme.TEXT);
				PfpConfig.Req r = new PfpConfig.Req();
				r.name = p[0];
				r.id = p[1];
				r.icon = p[2];
				slots.add(new Slot(x, y, cw, 20, r, null));
			}
		}
		for (Slot sl : slots) {
			if (sl.stack() != null && mx >= sl.x() && mx < sl.x() + sl.w() && my >= sl.y() && my < sl.y() + sl.h()) {
				g.setTooltipForNextFrame(font, sl.stack(), mx, my);
			}
		}
		PfpTheme.draw(g, PfpTheme.fit("Joiners missing one of these are announced in party chat and kicked (leader only).", IW), l, y0 + panelH - 14, PfpTheme.MUTED);
	}

	private void drawField(GuiGraphicsExtractor g, EditBox box) {
		int x = box.getX() - 8, y = box.getY() - 7, w = box.getWidth() + 16;
		PfpTheme.round(g, x, y, w, 22, box.isFocused() ? PfpTheme.FIELD_FOCUS : PfpTheme.FIELD);
	}
}
