package dev.pfp;

import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Weicher Button: halbtransparente Flaeche, kein Rahmen. */
final class FlatButton extends AbstractButton {
	enum Style { PRIMARY, NORMAL, DANGER, TAB_ON }

	private final Style style;
	private final String label;
	private final Runnable action;
	private final BooleanSupplier dot;

	FlatButton(int x, int y, int w, int h, String label, Style style, BooleanSupplier dot, Runnable action) {
		super(x, y, w, h, Component.literal(label));
		this.label = label;
		this.style = style;
		this.dot = dot;
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

		int bg, fg;
		switch (style) {
			case PRIMARY -> {
				bg = hot ? 0xFF7DFFA0 : PfpTheme.GREEN;
				fg = PfpTheme.ON_GREEN;
			}
			case TAB_ON -> {
				bg = PfpTheme.BTN_HOT;
				fg = PfpTheme.PURPLE;
			}
			case DANGER -> {
				bg = hot ? 0x33FF5F5F : 0x00000000;
				fg = hot ? PfpTheme.RED : PfpTheme.MUTED;
			}
			default -> {
				bg = hot ? PfpTheme.BTN_HOT : PfpTheme.BTN;
				fg = PfpTheme.TEXT;
			}
		}
		if (!active) {
			bg = 0x10FFFFFF;
			fg = PfpTheme.OFF;
		}
		if (bg != 0) PfpTheme.round(g, x, y, w, h, bg);

		Component text = PfpTheme.medium(label);
		int textW = PfpTheme.width(text);
		int dotW = dot != null ? 12 : 0;
		int startX = x + (w - textW - dotW) / 2;
		int ty = y + (h - 9) / 2;
		if (dot != null) {
			PfpTheme.dot(g, startX, ty + 2, dot.getAsBoolean() ? PfpTheme.GREEN : PfpTheme.OFF);
			startX += dotW;
		}
		PfpTheme.draw(g, text, startX, ty, fg);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}
}
