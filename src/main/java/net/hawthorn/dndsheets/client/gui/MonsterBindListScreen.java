package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterBindMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Elegir qué bloque de estadísticas se le pega a la criatura que el DM acaba de tocar con la Vara de
 * DM. El bestiario viaja en el mismo {@link MonsterBindMessage} que abre esta pantalla —resuelto en el
 * servidor, que es donde de verdad vive el registro— y no se lee del cliente: un DM que sea un proceso
 * aparte (invitado por LAN) lo vería siempre vacío.</p>
 *
 * <p>Lo único más que hay que llevar hasta aquí es a QUIÉN se lo estamos pegando, y eso viaja en el mismo
 * mensaje que luego vuelve con la respuesta.</p>
 */
public class MonsterBindListScreen extends ListPickerScreen {

	private final int entityId;
	private final List<String> ids;

	private MonsterBindListScreen(int entityId, List<String> ids) {
		super(Component.translatable("gui.dndsheets.monster_bind.title"));
		this.entityId = entityId;
		this.ids = ids;
	}

	public static void open(int entityId, List<String> ids) {
		Minecraft.getInstance().setScreen(new MonsterBindListScreen(entityId, ids));
	}

	//330 monstruos del SRD: sin buscador esto es una lista por la que se baja con la rueda hasta rendirse.
	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String monsterId : ids) {
			addRow(Component.literal(monsterId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterBindMessage(entityId, monsterId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.monster_spawn.empty") : null;
	}
}
