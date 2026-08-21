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
 * negativo y empujaba los botones fuera de pantalla, sin ninguna forma de llegar a ellos.</p>
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

	//Usado por ListPickerScreen para filtrar por texto de búsqueda: los botones que salen de la lista
	//visible no se destruyen (siguen registrados en Screen#children para que un futuro replaceRows los
	//pueda traer de vuelta), pero hay que apagarles visible/active a mano — renderContents solo lo hace
	//para los que YA están dentro del rango de scroll de la lista actual.
	public void replaceRows(List<Button> newRows) {
		for (Button button : rows) {
			if (!newRows.contains(button)) {
				button.visible = false;
				button.active = false;
			}
		}
		rows.clear();
		rows.addAll(newRows);
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
		if (rows.isEmpty()) return;
		int scroll = (int) this.scrollAmount();
		//Rango de filas realmente visibles calculado directo (no recorriendo todas para comparar límites):
		//antes se llamaba setX/setY en CADA botón de la lista en CADA frame, visible o no — con listas
		//largas (muchos jugadores conectados, muchos monstruos/rasgos cargados) eso se notaba al desplazar.
		//Ahora solo se posiciona/renderiza lo que cae dentro del rango, con un margen de una fila de cada
		//lado para que no haga "pop" justo en el borde del recorte (scissor).
		int first = Math.max(0, scroll / rowHeight - 1);
		int last = Math.min(rows.size() - 1, (scroll + this.height) / rowHeight + 1);

		for (int i = 0; i < rows.size(); i++) {
			Button button = rows.get(i);
			boolean rowVisible = i >= first && i <= last;
			button.visible = rowVisible;
			button.active = rowVisible;
			if (!rowVisible) continue;
			button.setX(this.getX());
			button.setY(this.getY() + i * rowHeight - scroll);
			button.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false; //Los clics van a los botones hijos (registrados aparte en la pantalla), no al contenedor.
	}
}
