package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Lista vertical de botones de ancho completo con scroll automático si no caben todos en el alto
 * disponible — usada por las pantallas de elegir-uno-de-varios del Panel de DM (jugador, preset, rasgo,
 * acción de monstruo, ataque personalizado a quitar). Antes de esto, cada pantalla centraba su lista a
 * mano con {@code (alto - total) / 2} sin ningún tope: con suficientes filas ese cálculo se volvía
 * negativo y empujaba los botones fuera de pantalla, sin ninguna forma de llegar a ellos (ver
 * AUDIT_UX.md).</p>
 *
 * <p>Los botones deben registrarse en la pantalla con {@code Screen#addWidget} (NO
 * {@code addRenderableWidget}, para que la pantalla no los dibuje por su cuenta — este widget ya se
 * encarga) y en este widget con {@link #addRow}. Mismo patrón de scissor/scroll que ya usa
 * {@link RollScrollWidget} para la pestaña de Ataques, simplificado para una sola fila de un botón.</p>
 */
public class ButtonListWidget extends AbstractScrollWidget {
	private final List<Button> rows = new ArrayList<>();
	private final int rowHeight;

	public ButtonListWidget(int x, int y, int width, int height, int rowHeight) {
		super(x, y, width, height, Component.empty());
		this.rowHeight = rowHeight;
	}

	public void addRow(Button button) {
		rows.add(button);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	@Override
	protected int getInnerHeight() {
		return rows.size() * rowHeight;
	}

	@Override
	protected double scrollRate() {
		return rowHeight / 2.0;
	}

	@Override
	protected boolean scrollbarVisible() {
		return getInnerHeight() > this.height;
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (!this.visible) return;
		this.renderBackground(guiGraphics);
		guiGraphics.enableScissor(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1);
		renderContents(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.disableScissor();
		this.renderDecorations(guiGraphics);
	}

	@Override
	protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		int scroll = (int) this.scrollAmount();
		for (int i = 0; i < rows.size(); i++) {
			Button button = rows.get(i);
			int rowY = this.getY() + i * rowHeight - scroll;
			button.setX(this.getX());
			button.setY(rowY);
			boolean rowVisible = rowY + rowHeight >= this.getY() && rowY <= this.getY() + this.height;
			button.visible = rowVisible;
			button.active = rowVisible;
			if (rowVisible) button.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false; //Los clics van a los botones hijos (registrados aparte en la pantalla), no al contenedor.
	}
}
