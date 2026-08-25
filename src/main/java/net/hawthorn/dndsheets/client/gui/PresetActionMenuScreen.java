package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.PresetListRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Menú fijo de dos filas que cuelga del botón "Presets" de la ficha: "Aplicar preset" (el
 * comportamiento de siempre, reemplaza clase/dado de golpe/características) y "Multiclasear" (sube un
 * nivel en una clase nueva sin tocar el resto). No cabía un quinto botón de página en {@code
 * CharacterSheetScreen} — la fila de abajo ya llena los 350px de ancho con cuatro (ver el comentario de
 * {@code BOTTOM_BUTTON_WIDTH} ahí) —, así que el "Presets" ya existente se convierte en la puerta de las
 * dos acciones en vez de sumar un botón más.</p>
 *
 * <p>Las dos filas piden la MISMA lista de presets al servidor ({@code PresetListRequestMessage}) y
 * abren la MISMA {@link PresetScreen}; lo único que cambia es el flag {@code multiclass} que viaja de
 * ida y vuelta con la petición, así que esa pantalla sabe qué mensaje mandar al elegir una fila.</p>
 */
public class PresetActionMenuScreen extends ListPickerScreen {
	private PresetActionMenuScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.preset.menu_title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new PresetActionMenuScreen(parent));
	}

	@Override
	protected void buildRows() {
		addRow(Component.translatable("gui.dndsheets.preset.menu_apply"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage()));
		addRow(Component.translatable("gui.dndsheets.preset.menu_multiclass"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage(true)));
	}
}
