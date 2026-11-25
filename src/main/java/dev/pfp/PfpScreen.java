package dev.pfp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class PfpScreen extends Screen {
	private static final int PAD = 16;
	private static final int ROW_H = 26;
	private static final int ROW_PITCH = 30;
	private static final int HEAD_H = 78;
	private static final int FOOT_H = 50;

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
	private static int tab = 1; // 0 Overview, 1 Blacklist, 2 History, 3 Settings
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

	private static final String[] TAB_NAMES = {"Overview", "Blacklist", "History", "Settings"};
	private static final int[] TAB_W = {54, 56, 48, 52};
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
		PW = Math.min(420, width - 16);
		IW = PW - 2 * PAD;
		rows = Math.max(2, Math.min(8, (height - 16 - HEAD_H - FOOT_H) / ROW_PITCH));
		hRows = Math.max(2, Math.min(4, (height - 16 - 52 - 40) / H_PITCH));
		compact = height < 334; // small screens / large GUI scale: tighter settings layout, no hints
		panelH = switch (tab) {
			case 0 -> Math.min(262, height - 16);
			case 2 -> 52 + hRows * H_PITCH + 36;
			case 3 -> compact ? 230 : 318;
			default -> HEAD_H + FOOT_H + rows * ROW_PITCH - 4;
		};
		x0 = (width - PW) / 2;
		y0 = Math.max(8, (height - panelH) / 2);
		listTop = y0 + HEAD_H;
		int left = x0 + PAD;
		textBoxes.clear();

		int tx = left;
		for (int i = 0; i < TAB_NAMES.length; i++) {
			int idx = i;
			addRenderableWidget(new FlatButton(tx, y0 + 12, TAB_W[i], 18, TAB_NAMES[i],
				tab == i ? FlatButton.Style.TAB_ON : FlatButton.Style.NORMAL, null, () -> { tab = idx; hSel = -1; rebuildWidgets(); }));
			tx += TAB_W[i] + 3;
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
		int reasonW = IW - 92 - 6 - 6 - 76 - 6 - 84;
		nameBox = field(left, addY, 92, "Name", 16, nameText, s -> nameText = s);
		reasonBox = field(left + 98, addY, reasonW, "Reason  7D | inf", 100, reasonText, s -> reasonText = s);
		addRenderableWidget(new FlatButton(left + IW - 84 - 6 - 76, addY, 76, 22, PfpConfig.modeLabel(addMode),
			FlatButton.Style.NORMAL, null, () -> {
				addMode = PfpConfig.BOTH.equals(addMode) ? PfpConfig.DUNGEON : PfpConfig.DUNGEON.equals(addMode) ? PfpConfig.KUUDRA : PfpConfig.BOTH;
				rebuildWidgets();
			}));
		addRenderableWidget(new FlatButton(left + IW - 84, addY, 84, 22, "Add",
			FlatButton.Style.PRIMARY, null, this::addEntry));

		List<PfpConfig.Entry> all = visible();
		int pages = Math.max(1, (all.size() + rows - 1) / rows);
		page = Math.max(0, Math.min(page, pages - 1));
		int navY = y0 + 12;
		FlatButton next = new FlatButton(left + IW - 20, navY, 20, 18, ">",
			FlatButton.Style.NORMAL, null, () -> { page++; rebuildWidgets(); });
		FlatButton prev = new FlatButton(left + IW - 20 - 44 - 20, navY, 20, 18, "<",
			FlatButton.Style.NORMAL, null, () -> { page--; rebuildWidgets(); });
		prev.active = page > 0;
		next.active = page < pages - 1;
		addRenderableWidget(prev);
		addRenderableWidget(next);
		addRenderableWidget(new FlatButton(left + IW - 20 - 44 - 20 - 6 - 70, navY, 70, 18,
			filter == 0 ? "All" : filter == 1 ? "Dungeons" : "Kuudra",
			FlatButton.Style.NORMAL, null, () -> { filter = (filter + 1) % 3; page = 0; rebuildWidgets(); }));

		for (int i = 0; i < rows; i++) {
			int idx = page * rows + i;
			if (idx >= all.size()) break;
			String n = all.get(idx).name();
			addRenderableWidget(new FlatButton(left + IW - 22, listTop + i * ROW_PITCH, 22, ROW_H, "x",
				FlatButton.Style.DANGER, null, () -> { PfpConfig.remove(n); rebuildWidgets(); }));
		}

		int sy = listTop + rows * ROW_PITCH - 4 + 12;
		int bw = (IW - 12) / 3;
		addRenderableWidget(new FlatButton(left, sy, bw, 22, "Mod enabled",
			FlatButton.Style.NORMAL, () -> PfpConfig.enabled, () -> {
				PfpConfig.enabled = !PfpConfig.enabled;
				PfpConfig.save();
			}));
		addRenderableWidget(new FlatButton(left + bw + 6, sy, bw, 22, "Party Finder only",
			FlatButton.Style.NORMAL, () -> PfpConfig.pfOnly, () -> {
				PfpConfig.pfOnly = !PfpConfig.pfOnly;
				PfpConfig.save();
			}));
		addRenderableWidget(new FlatButton(left + 2 * (bw + 6), sy, IW - 2 * (bw + 6), 22, expLabel(),
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
		PfpTheme.round(g, x0, y0, PW, panelH, PfpTheme.PANEL);
		hits.clear();
		if (tab == 0) { drawOverview(g); return; }
		if (tab == 2) { drawHistory(g, mouseX, mouseY); return; }
		if (tab == 3) { drawSettings(g); return; }

		int left = x0 + PAD;
		int right = left + IW;
		List<PfpConfig.Entry> all = visible();
		int pages = Math.max(1, (all.size() + rows - 1) / rows);

		Component pageText = PfpTheme.text((page + 1) + "/" + pages);
		PfpTheme.draw(g, pageText, right - 20 - 22 - PfpTheme.width(pageText) / 2, y0 + 15, PfpTheme.TEXT_2);

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
			PfpTheme.dot(g, left + 8, y + 10, status);

			int tailW = 0;
			if (d < 0 && e.days() != null) {
				Component tail = PfpTheme.text("permanent");
				PfpTheme.drawRight(g, tail, left + rowW - 10, y + 4, PfpTheme.TEXT_2);
				tailW = PfpTheme.width(tail) + 8;
			}
			if (d >= 0) {
				Component tail = PfpTheme.medium(d + (d == 1 ? " day left" : " days left"));
				PfpTheme.drawRight(g, tail, left + rowW - 10, y + 4, status);
				tailW = PfpTheme.width(tail) + 8;
			}
			Component nm = PfpTheme.medium(e.name());
			PfpTheme.draw(g, nm, left + 22, y + 3, PfpTheme.TEXT);
			String md = e.mode() == null ? PfpConfig.BOTH : e.mode();
			PfpTheme.draw(g, PfpTheme.text(PfpConfig.modeLabel(md)), left + 22 + PfpTheme.width(nm) + 8, y + 3, modeColor(md));
				String tag = PfpConfig.tagOf(e.name());
				int hitW = PfpTheme.width(nm) + 8 + PfpTheme.width(PfpTheme.text(PfpConfig.modeLabel(md)));
				if (tag != null) {
					PfpTheme.draw(g, PfpTheme.text(tag), left + 22 + hitW + 8, y + 3, PfpConfig.tagColor(tag));
					hitW += 8 + PfpTheme.width(PfpTheme.text(tag));
				}
				hits.add(new Hit(left + 22, y + 2, hitW, 12, e.name()));
			PfpTheme.draw(g, PfpTheme.fit(e.reason(), rowW - 22 - 10 - tailW), left + 22, y + 14, PfpTheme.TEXT_2);
		}

		int fy = listTop + rows * ROW_PITCH - 4 + 12 + 22 + 6;
		PfpTheme.draw(g, PfpTheme.text("Esc to close"), left, fy, PfpTheme.MUTED);
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
			PfpTheme.draw(g, PfpTheme.text(labels[i]), l, box.getY() - 19, PfpTheme.TEXT_2);
			drawField(g, box);
		}
		if (compact) return;
		PfpTheme.draw(g, PfpTheme.fit("[PFP] " + PfpConfig.format(PfpConfig.kickMsg, "Name", "Reason"), IW), l, y0 + 288, PfpTheme.PURPLE);
		String key = PfpConfig.apiKey.isBlank() ? "no API key (/pfp apikey <key>)" : "API key set";
		PfpTheme.draw(g, PfpTheme.fit("{name} {reason} are replaced, [PFP] always first. Secret tracker: " + key, IW), l, y0 + 300, PfpTheme.MUTED);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean dbl) {
		if (tab != 3 && (event.button() == 0 || event.button() == 1)) {
			for (Hit h : hits) {
				if (event.x() >= h.x() && event.x() < h.x() + h.w() && event.y() >= h.y() && event.y() < h.y() + h.h()) {
					cycleTag(h.name(), event.button() == 1);
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

	private void drawField(GuiGraphicsExtractor g, EditBox box) {
		int x = box.getX() - 8, y = box.getY() - 7, w = box.getWidth() + 16;
		PfpTheme.round(g, x, y, w, 22, box.isFocused() ? PfpTheme.FIELD_FOCUS : PfpTheme.FIELD);
	}
}
