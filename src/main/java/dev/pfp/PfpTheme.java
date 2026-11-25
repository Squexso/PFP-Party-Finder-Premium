package dev.pfp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/** Farben (ARGB) und Schrift. Ruhige dunkle Flaeche, die Farbe steckt im Text. */
final class PfpTheme {
	static final int DIM = 0x88060410;
	static final int PANEL = 0xF2151221;
	static final int ROW = 0xFF1F1A30;
	static final int FIELD = 0xFF272139;
	static final int FIELD_FOCUS = 0xFF352C57;
	static final int BTN = 0xFF2C2645;
	static final int BTN_HOT = 0xFF3B3263;
	static final int HAIR = 0x26FFFFFF;

	static final int TEXT = 0xFFF5F2FF;
	static final int TEXT_2 = 0xFFC9C3E0;
	static final int MUTED = 0xFF8D86AA;

	static final int PURPLE = 0xFFB69CFF; // accent as text and underline
	static final int ACCENT = 0xFF7C5CF0; // accent as a fill (white text on it)
	static final int ACCENT_HOT = 0xFF8F73FF;
	static final int AQUA = 0xFF5EEAD4;
	static final int GOLD = 0xFFFFB020;
	static final int GREEN = 0xFF4ADE80;
	static final int YELLOW = 0xFFFACC15;
	static final int RED = 0xFFFF6B7A;
	static final int OFF = 0xFF6D6788;
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
