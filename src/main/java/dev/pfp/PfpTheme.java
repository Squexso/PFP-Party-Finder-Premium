package dev.pfp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/** Farben (ARGB) und Schrift. Ruhige dunkle Flaeche, die Farbe steckt im Text. */
final class PfpTheme {
	static final int DIM = 0x66000000;
	static final int PANEL = 0xE6131418;
	static final int ROW = 0x14FFFFFF;
	static final int FIELD = 0x1FFFFFFF;
	static final int FIELD_FOCUS = 0x38FFFFFF;
	static final int BTN = 0x26FFFFFF;
	static final int BTN_HOT = 0x40FFFFFF;

	static final int TEXT = 0xFFF2F2F2;
	static final int TEXT_2 = 0xFFB4B8BF;
	static final int MUTED = 0xFF80858D;

	static final int PURPLE = 0xFFB98AFF;
	static final int AQUA = 0xFF55FFFF;
	static final int GOLD = 0xFFFFAA00;
	static final int GREEN = 0xFF55FF7F;
	static final int YELLOW = 0xFFFFD84D;
	static final int RED = 0xFFFF5F5F;
	static final int OFF = 0xFF6B7078;
	static final int ON_GREEN = 0xFF07260F;

	private static final FontDescription REGULAR = new FontDescription.Resource(Identifier.fromNamespaceAndPath("pfp", "poppins"));
	private static final FontDescription MEDIUM = new FontDescription.Resource(Identifier.fromNamespaceAndPath("pfp", "poppins_medium"));

	private PfpTheme() { }

	static net.minecraft.network.chat.Style style() {
		return net.minecraft.network.chat.Style.EMPTY.withFont(REGULAR);
	}

	static MutableComponent text(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(REGULAR));
	}

	static MutableComponent medium(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(MEDIUM));
	}

	static int width(Component c) {
		return Minecraft.getInstance().font.width(c);
	}

	/** Schneidet den Text mit "..." ab, damit er in maxW passt. */
	static Component fit(String s, int maxW) {
		if (width(text(s)) <= maxW) return text(s);
		String cut = s;
		while (cut.length() > 1 && width(text(cut + "...")) > maxW) cut = cut.substring(0, cut.length() - 1);
		return text(cut + "...");
	}

	static void draw(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
		g.text(Minecraft.getInstance().font, c, x, y, color, false);
	}

	static void drawRight(GuiGraphicsExtractor g, Component c, int right, int y, int color) {
		draw(g, c, right - width(c), y, color);
	}

	/** Flaeche mit leicht abgerundeten Ecken (2px). */
	static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 2, y, x + w - 2, y + 1, color);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, color);
		g.fill(x, y + 2, x + w, y + h - 2, color);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
	}

	static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x + 1, y, x + 5, y + 6, color);
		g.fill(x, y + 1, x + 6, y + 5, color);
	}
}
