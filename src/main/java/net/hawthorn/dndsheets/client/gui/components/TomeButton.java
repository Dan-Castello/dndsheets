package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * <p>Fila de lista con la identidad del mod: una tira de pergamino sobre el cuero del panel, con filete
 * de latón a la izquierda y biselado de Minecraft. Reemplaza al botón gris de piedra de vanilla, que
 * sobre un panel de cuero se lee como un widget prestado de otra interfaz.</p>
 *
 * <p>Vive aquí y no en cada pantalla porque {@code ListPickerScreen.addRow} y {@code SmallFormScreen}
 * son los dos únicos sitios que crean filas: cambiarlo aquí repinta por dentro las más de cuarenta
 * pantallas que cuelgan de ellos, igual que {@code GuiStyle} hizo con sus marcos.</p>
 *
 * <p>El estado de foco/hover no se marca solo con un cambio de color de fondo: también se enciende el
 * filete de latón y se aclara el texto. Un cambio de un solo tono sobre un fondo oscuro es justo lo que
 * no se distingue con brillo de monitor bajo.</p>
 */
public class TomeButton extends Button {

	//Pergamino apagado en reposo, encendido al pasar por encima. Más oscuro que la hoja de personaje: es
	//una tira sobre cuero, no la hoja entera, y compite con menos superficie.
	private static final int FILL_IDLE = 0xF2241C13;
	private static final int FILL_HOVER = 0xF2382B1B;
	private static final int BEVEL_LIGHT = 0xFF5A4830;
	private static final int BEVEL_DARK = 0xFF0B0906;
	private static final int RAIL_IDLE = 0xFF6B5636;
	private static final int RAIL_HOVER = 0xFFC9A227;
	private static final int TEXT_IDLE = 0xFFCBBA97;
	private static final int TEXT_HOVER = 0xFFF0E2C0;
	private static final int TEXT_DISABLED = 0xFF6E6455;

	private static final int RAIL_WIDTH = 2;

	public TomeButton(int x, int y, int width, int height, Component message, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
	}

	/** Mismo punto de entrada que {@code Button.builder(...)}, para que el sitio que la crea no cambie de forma. */
	public static TomeButton of(Component message, OnPress onPress, int x, int y, int width, int height) {
		return new TomeButton(x, y, width, height, message, onPress);
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		int left = this.getX();
		int top = this.getY();
		int right = left + this.width;
		int bottom = top + this.height;

		boolean active = this.isHoveredOrFocused() && this.active;
		int alpha = Mth.ceil(this.alpha * 255.0F) << 24;

		guiGraphics.fill(left, top, right, bottom, (active ? FILL_HOVER : FILL_IDLE));
		//Bisel de Minecraft, igual que GuiStyle: es lo que hace que la fila pertenezca al juego.
		guiGraphics.fill(left, top, right - 1, top + 1, BEVEL_LIGHT);
		guiGraphics.fill(left, top, left + 1, bottom - 1, BEVEL_LIGHT);
		guiGraphics.fill(left + 1, bottom - 1, right, bottom, BEVEL_DARK);
		guiGraphics.fill(right - 1, top + 1, right, bottom, BEVEL_DARK);

		//Filete de latón a la izquierda: es la marca de "esto es una fila del tomo", y al encenderse da un
		//segundo indicio de foco además del fondo.
		guiGraphics.fill(left + 1, top + 1, left + 1 + RAIL_WIDTH, bottom - 1, active ? RAIL_HOVER : RAIL_IDLE);

		int color = !this.active ? TEXT_DISABLED : (active ? TEXT_HOVER : TEXT_IDLE);
		Minecraft minecraft = Minecraft.getInstance();
		//El texto se centra en el hueco QUE QUEDA tras el filete, no en el botón entero: centrarlo en el
		//botón lo dejaría visiblemente descuadrado hacia la izquierda.
		int textLeft = left + 1 + RAIL_WIDTH;
		guiGraphics.drawCenteredString(minecraft.font, this.getMessage(),
			textLeft + (right - textLeft) / 2, top + (this.height - 8) / 2, color | alpha);
	}
}
