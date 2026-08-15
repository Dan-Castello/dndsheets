package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterSaveTemplateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Guarda el monstruo invocado (normalmente un NPC genérico ya armado a mano con "+ Añadir ataque", ver
 * {@link MonsterActionScreen}) como una plantilla JSON reusable en {@code monsters/dm_created.json} — así
 * un DM crea un monstruo entero (nombre/CA/PG ya puestos al invocarlo, ataques ya puestos en vivo) sin
 * escribir ni una línea de JSON, solo con "Invocar NPC genérico" + esta pantalla. Las características
 * (fue/des/con/int/sab/car) no se pueden ajustar en un NPC ya invocado hoy, así que se piden acá — quedan
 * en 10 si no se tocan, igual que {@code MonsterRegistry.spawnGeneric} las deja por defecto.</p>
 */
public class MonsterTemplateSaveScreen extends SmallFormScreen {
	private final int entityId;
	private EditBox idBox, abilitiesBox;

	private MonsterTemplateSaveScreen(int entityId, Screen parent) {
		super(Component.literal("Guardar como plantilla"), 1, parent);
		this.entityId = entityId;
	}

	public static void open(int entityId) {
		Minecraft.getInstance().setScreen(new MonsterTemplateSaveScreen(entityId, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		idBox = addField("Id (espacioDeNombres:ruta)", "", 32);
		abilitiesBox = addField("Fue,Des,Con,Int,Sab,Car (separadas por coma)", "10, 10, 10, 10, 10, 10", 32);
	}

	@Override
	protected void onConfirm() {
		String id = idBox.getValue().trim();
		if (id.isEmpty()) return;

		DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterSaveTemplateMessage(entityId, id, abilitiesBox.getValue().trim()));
	}
}
