package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.PresetApplyMessage;
import net.hawthorn.dndsheets.network.PresetApplyToMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Selector de presets de clase: la lista (ids + nombres) la manda el servidor en
 * {@code PresetListMessage} (pedida por {@code PresetListRequestMessage} al pulsar "Presets" en la
 * hoja, o por un DM tras elegir a otro jugador en {@link PlayerPickerScreen} — ver AUDIT_UX.md, DM #2),
 * porque el registro de presets solo vive en memoria del servidor. Elegir uno rellena la clase, el dado
 * de golpe y las características de la hoja — hay que cerrarla y reabrirla para verlo. {@code
 * targetUuid} vacío significa "aplícalo a mi propia hoja".</p>
 */
public class PresetScreen extends ListPickerScreen {
	private final String targetUuid;
	private final List<String> ids;
	private final List<String> names;

	private PresetScreen(String targetUuid, List<String> ids, List<String> names, Screen parent) {
		super(Component.literal("Elige un preset de clase"), parent);
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new PresetScreen(targetUuid, ids, names, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < names.size(); i++) {
			String presetId = ids.get(i);
			addRow(Component.literal(names.get(i)), b -> {
				if (targetUuid.isEmpty()) {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetApplyMessage(presetId));
				} else {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetApplyToMessage(targetUuid, presetId));
				}
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return names.isEmpty() ? Component.literal("No hay presets cargados (pide al DM /dndpresets load).") : null;
	}
}
