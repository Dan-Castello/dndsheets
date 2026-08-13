package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TraitGrantMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Último paso de conceder un rasgo desde el Panel de DM: la lista (ids + nombres) la manda el
 * servidor en {@code TraitListMessage} (pedida por {@code TraitListRequestMessage} tras elegir el
 * jugador objetivo en {@link PlayerPickerScreen}), porque el registro de rasgos solo vive en memoria del
 * servidor — mismo patrón que {@link PresetScreen}.</p>
 */
public class TraitGrantScreen extends ListPickerScreen {
	private final String targetUuid;
	private final List<String> ids;
	private final List<String> names;

	private TraitGrantScreen(String targetUuid, List<String> ids, List<String> names) {
		super(Component.literal("Elige un rasgo para conceder"));
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new TraitGrantScreen(targetUuid, ids, names));
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < names.size(); i++) {
			String traitId = ids.get(i);
			addRow(Component.literal(names.get(i)), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new TraitGrantMessage(targetUuid, traitId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return names.isEmpty() ? Component.literal("No hay rasgos cargados (/dndtraits load).") : null;
	}
}
