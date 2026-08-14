package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SpawnGenericMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>NPC en blanco desde el Panel de DM (equivalente en GUI a {@code /dndmonsters spawn generic}): sin
 * ataques, pensado para rellenarlo después desde el menú de la Vara de DM ("+ Añadir ataque", ver
 * {@link AddMonsterAttackScreen}). Se invoca en la posición del DM.</p>
 */
public class SpawnGenericScreen extends SmallFormScreen {
	private EditBox nameBox;
	private EditBox baseEntityBox;
	private EditBox acBox;
	private EditBox hpBox;

	private SpawnGenericScreen(Screen parent) {
		super(Component.literal("NPC genérico"), 2, parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new SpawnGenericScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		nameBox = addField("Nombre", "NPC", 40);
		baseEntityBox = addField("Entidad base", "minecraft:villager", 64);
		acBox = addField("CA", "10", 3);
		hpBox = addField("PG", "10", 4);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().isBlank() ? "NPC" : nameBox.getValue();
		String baseEntity = baseEntityBox.getValue().isBlank() ? "minecraft:villager" : baseEntityBox.getValue();
		int ac = parseIntOr(acBox.getValue(), 10);
		int hp = Math.max(1, parseIntOr(hpBox.getValue(), 10));
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SpawnGenericMessage(name, baseEntity, ac, hp));
	}
}
