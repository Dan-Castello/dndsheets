package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>La ficha de una entrada del compendio: el texto que compuso el servidor, tal cual. El cliente no
 * decide qué campos enseña ni cómo — eso vive en {@code CompendiumQuery}, junto a los registros que
 * conocen la forma de cada tipo de contenido.</p>
 *
 * <p>La primera línea se trata como título y el resto como cuerpo, porque así es como el servidor las
 * escribe. Las líneas largas se parten al ancho del diálogo en vez de salirse por el borde.</p>
 */
public class CompendiumEntryScreen extends ModalDialogScreen {

	private static final int WIDTH = 300;
	private static final int HEIGHT = 200;
	private static final int PADDING = 12;

	private final String text;
	private final Screen parent;
	private List<net.minecraft.util.FormattedCharSequence> lines = List.of();
	private String heading = "";

	private CompendiumEntryScreen(String text, Screen parent) {
		super(Component.translatable("gui.dndsheets.compendium.title"), WIDTH, HEIGHT);
		this.text = text;
		this.parent = parent;
	}

	public static void open(String text) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new CompendiumEntryScreen(text, minecraft.screen));
	}

	@Override
	protected void init() {
		String[] parts = text.split("\n", 2);
		heading = parts[0];

		//El ajuste de línea se hace en init() y no en render(): render corre 60 veces por segundo y partir
		//el texto en cada fotograma sería trabajo repetido para un resultado que no cambia.
		lines = new ArrayList<>();
		if (parts.length > 1) {
			for (String paragraph : parts[1].split("\n")) {
				if (paragraph.isBlank()) {
					lines.add(Component.empty().getVisualOrderText());
					continue;
				}
				lines.addAll(this.font.split(Component.literal(paragraph), WIDTH - PADDING * 2));
			}
		}

		addModalButton(WIDTH - 60 - PADDING, HEIGHT - 24, 60, 16, Component.translatable("gui.dndsheets.common.close"), b -> this.onClose());
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		renderPanel(guiGraphics);
		int x = dialogLeft() + PADDING;
		int y = dialogTop() + PADDING;

		guiGraphics.drawString(this.font, heading, x, y, GuiStyle.TITLE_COLOR);
		y += 14;

		for (net.minecraft.util.FormattedCharSequence line : lines) {
			//Se corta al llegar al botón de cerrar en vez de escribir por encima de él: una ficha larga se
			//queda a medias, que es preferible a texto ilegible encima de un control.
			if (y > dialogTop() + HEIGHT - 34) break;
			guiGraphics.drawString(this.font, line, x, y, GuiStyle.MUTED_COLOR);
			y += 10;
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
