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
	//Hueco entre filas. La ALTURA la pone cada botón (getHeight), no esta lista: así una cabecera de
	//sección puede medir la mitad que una fila sin que este widget tenga que saber qué es una cabecera.
	//Cuando todas las filas medían lo mismo, las cinco cabeceras del Panel de DM costaban cinco filas de
	//lista y lo empujaban a hacer scroll con diecisiete acciones que, por alto, sí cabían.
	private final int spacing;

	public ButtonListWidget(int x, int y, int width, int height, int spacing) {
		super(x, y, width, height, Component.empty());
		this.spacing = spacing;
	}

	private int stepOf(Button row) {
		return row.getHeight() + spacing;
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
		int total = 0;
		for (Button row : rows) total += stepOf(row);
		return total;
	}

	//Media fila por muesca de rueda, tomando la primera como referencia: con filas de dos altos distintos
	//no hay "la" altura, y el paso del scroll no necesita ser exacto, solo cómodo.
	@Override
	protected double scrollRate() {
		return rows.isEmpty() ? 12 : stepOf(rows.get(0)) / 2.0;
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
		//Se sigue posicionando y dibujando SOLO lo que cae dentro del recorte (antes se llamaba setX/setY
		//en cada botón de cada frame, visible o no, y con listas largas se notaba al desplazar). Lo que
		//cambia con alturas por fila es que el rango ya no sale de una división: se acumula el alto al
		//recorrer, que es el mismo recorrido que este bucle hacía igualmente.
		int offset = 0;
		for (Button button : rows) {
			int step = stepOf(button);
			int top = offset - scroll;
			offset += step;
			//Un paso de margen por arriba y por abajo, para que no haga "pop" justo en el borde.
			boolean rowVisible = top + step >= -step && top <= this.height + step;
			button.visible = rowVisible;
			button.active = rowVisible;
			if (!rowVisible) continue;
			button.setX(this.getX());
			button.setY(this.getY() + top);
			button.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false; //Los clics van a los botones hijos (registrados aparte en la pantalla), no al contenedor.
	}
}
