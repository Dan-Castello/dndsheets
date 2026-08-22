package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.network.MonsterBindMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * <p>Elegir qué bloque de estadísticas se le pega a la criatura que el DM acaba de tocar con la Vara de
 * DM. La lista sale del registro del CLIENTE y no de un mensaje del servidor: los monstruos cargados ya
 * están ahí (es lo mismo que hace {@link MonsterSpawnListScreen}), así que abrirlo no cuesta un viaje de
 * ida y vuelta.</p>
 *
 * <p>Lo único que hay que llevar hasta aquí es a QUIÉN se lo estamos pegando, y eso viaja en el mismo
 * {@link MonsterBindMessage} que luego vuelve con la respuesta.</p>
 */
public class MonsterBindListScreen extends ListPickerScreen {

	private final int entityId;

	private MonsterBindListScreen(int entityId) {
		super(Component.translatable("gui.dndsheets.monster_bind.title"));
		this.entityId = entityId;
	}

	public static void open(int entityId) {
		Minecraft.getInstance().setScreen(new MonsterBindListScreen(entityId));
	}

	//330 monstruos del SRD: sin buscador esto es una lista por la que se baja con la rueda hasta rendirse.
	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String monsterId : MonsterRegistry.ids()) {
			addRow(Component.literal(monsterId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterBindMessage(entityId, monsterId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return MonsterRegistry.ids().isEmpty() ? Component.translatable("gui.dndsheets.monster_spawn.empty") : null;
	}
}
