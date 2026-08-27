package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MulticlassMessage;
import net.hawthorn.dndsheets.network.PresetApplyMessage;
import net.hawthorn.dndsheets.network.PresetApplyToMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Selector de presets de clase: la lista (ids + nombres) la manda el servidor en
 * {@code BrowseListMessage} kind PRESET (pedida con {@code BrowseActionMessage.LIST_PRESETS} al pulsar "Presets" en la
 * hoja, o por un DM tras elegir a otro jugador en {@link PlayerPickerScreen},
 * porque el registro de presets solo vive en memoria del servidor. Elegir uno rellena la clase, el dado
 * de golpe y las características de la hoja — hay que cerrarla y reabrirla para verlo. {@code
 * targetUuid} vacío significa "aplícalo a mi propia hoja".</p>
 *
 * <p>{@code multiclass} es un modo distinto de la MISMA lista, elegido en {@code PresetActionMenuScreen}
 * ("Multiclasear" en vez de "Aplicar preset"): en vez de reemplazar clase/dado de golpe/características
 * (lo que hace {@code PresetApplyMessage}), sube un nivel EN la clase elegida ({@code
 * MulticlassMessage}, mismo camino que ya usaba {@code /dndsheet multiclass}). Solo tiene sentido self
 * ({@code targetUuid} vacío) — el botón que lo dispara vive en la propia ficha, no en el Panel de DM.</p>
 */
public class PresetScreen extends ListPickerScreen {
	private final String targetUuid;
	private final boolean multiclass;
	private final List<String> ids;
	private final List<String> names;

	private PresetScreen(String targetUuid, boolean multiclass, List<String> ids, List<String> names, Screen parent) {
		super(Component.translatable(multiclass ? "gui.dndsheets.multiclass.title" : "gui.dndsheets.preset.title"), parent);
		this.targetUuid = targetUuid;
		this.multiclass = multiclass;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, boolean multiclass, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new PresetScreen(targetUuid, multiclass, ids, names, Minecraft.getInstance().screen));
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
				if (multiclass) {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new MulticlassMessage(presetId));
				} else if (targetUuid.isEmpty()) {
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
		return names.isEmpty() ? Component.translatable("gui.dndsheets.preset.empty") : null;
	}
}
