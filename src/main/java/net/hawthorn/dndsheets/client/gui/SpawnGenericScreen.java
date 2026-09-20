package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SpawnGenericMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Blank NPC from the DM Panel (GUI equivalent of {@code /dndmonsters spawn generic}): no
 * attacks, meant to be filled in later from the DM Wand's menu ("+ Add attack", see
 * {@link AddMonsterAttackScreen}). Spawns at the DM's position.</p>
 */
public class SpawnGenericScreen extends SmallFormScreen {
	private EditBox nameBox;
	private EditBox baseEntityBox;
	private EditBox acBox;
	private EditBox hpBox;

	private SpawnGenericScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.spawn_generic.title"), 2, parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new SpawnGenericScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		nameBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.name"), "NPC", 40);
		baseEntityBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.base_entity"), "minecraft:villager", 64);
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
