package net.hawthorn.dndsheets.client.gui.components;

import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * <p>Cabecera de sección dentro de una lista: el rótulo en latón entre dos filetes, sin fondo de fila.
 * Divide un menú largo (el Panel de DM llegó a 17 filas planas indistinguibles) en bloques con nombre,
 * sin inventar un segundo contenedor: {@code ButtonListWidget} solo sabe de {@code Button}, así que la
 * cabecera ES un botón — uno que no responde al clic ni al teclado y se pinta como rótulo. Ver
 * {@code ListPickerScreen.addHeader}, que además la excluye del buscador.</p>
 */
public class SectionHeader extends Button {

	public SectionHeader(Component label, int width, int height) {
		super(0, 0, width, height, label, b -> {}, DEFAULT_NARRATION);
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		Font font = Minecraft.getInstance().font;
		int centerX = this.getX() + this.width / 2;
		int ruleY = this.getY() + this.height / 2;
		int halfText = font.width(this.getMessage()) / 2;

		GuiStyle.rule(guiGraphics, this.getX() + 6, centerX - halfText - 6, ruleY);
		GuiStyle.rule(guiGraphics, centerX + halfText + 6, this.getX() + this.width - 6, ruleY);
		guiGraphics.drawCenteredString(font, this.getMessage(), centerX, this.getY() + (this.height - 8) / 2, GuiStyle.ACCENT_COLOR);
	}

	//Un rótulo, no un control: nada de clic, sonido ni foco de teclado (ButtonListWidget reactiva
	//"active" cada frame para las filas visibles, así que el bloqueo tiene que ser por estos métodos y
	//no por active=false).
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
	}

	@Override
	public boolean isFocused() {
		return false;
	}
}
