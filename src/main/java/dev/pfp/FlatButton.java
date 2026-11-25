package dev.pfp;

import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Flat button. With a BooleanSupplier it is drawn as a switch (label left, switch right). */
final class FlatButton extends AbstractButton {
	enum Style { PRIMARY, NORMAL, DANGER, TAB, TAB_ON }

	private final Style style;
	private final String label;
	private final Runnable action;
	private final BooleanSupplier state;

	FlatButton(int x, int y, int w, int h, String label, Style style, BooleanSupplier state, Runnable action) {
		super(x, y, w, h, Component.literal(label));
		this.label = label;
		this.style = style;
		this.state = state;
		this.action = action;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		action.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean hot = isHoveredOrFocused() && active;

		if (style == Style.TAB || style == Style.TAB_ON) {
			boolean on = style == Style.TAB_ON;
			if (on) PfpTheme.round(g, x, y, w, h, 0x22B69CFF);
			else if (hot) PfpTheme.round(g, x, y, w, h, 0x14FFFFFF);
			Component t = PfpTheme.medium(label);
			PfpTheme.draw(g, t, x + (w - PfpTheme.width(t)) / 2, y + (h - 9) / 2 - 1, on ? PfpTheme.PURPLE : hot ? PfpTheme.TEXT : PfpTheme.MUTED);
			if (on) g.fill(x + 6, y + h - 2, x + w - 6, y + h, PfpTheme.PURPLE);
			return;
		}

		int bg, fg;
		switch (style) {
			case PRIMARY -> {
				bg = hot ? PfpTheme.ACCENT_HOT : PfpTheme.ACCENT;
				fg = 0xFFFFFFFF;
			}
			case DANGER -> {
				bg = hot ? 0x44FF6B7A : 0x00000000;
				fg = hot ? PfpTheme.RED : PfpTheme.MUTED;
			}
			default -> {
				bg = hot ? PfpTheme.BTN_HOT : PfpTheme.BTN;
				fg = PfpTheme.TEXT;
			}
		}
		if (!active) {
			bg = PfpTheme.ROW;
			fg = PfpTheme.OFF;
		}
		if (bg != 0) PfpTheme.round(g, x, y, w, h, bg);

		int ty = y + (h - 9) / 2;
		if (state != null) { // switch
			boolean on = state.getAsBoolean();
			PfpTheme.draw(g, PfpTheme.fit(label, w - 10 - 38), x + 10, ty, fg);
			int sx = x + w - 32, sy = y + (h - 12) / 2;
			PfpTheme.round(g, sx, sy, 24, 12, on ? PfpTheme.ACCENT : 0xFF443D63);
			int kx = on ? sx + 14 : sx + 2;
			PfpTheme.round(g, kx, sy + 2, 8, 8, on ? 0xFFFFFFFF : PfpTheme.MUTED);
			return;
		}
		Component text = PfpTheme.medium(label);
		PfpTheme.draw(g, text, x + (w - PfpTheme.width(text)) / 2, ty, fg);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}
}
