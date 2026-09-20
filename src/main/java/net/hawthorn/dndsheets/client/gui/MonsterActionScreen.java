package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterActionChooseMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Menu the DM Wand opens for the DM on right-clicking a monster summoned via
 * {@code /dndmonsters spawn}: a list of its attacks and spells. Picking one resolves the attack/spell
 * against the player closest to the monster (see {@link net.hawthorn.dndsheets.MonsterActionManager}).
 * Below the list, two buttons to edit THIS instance's attacks without commands or JSON — see
 * {@link AddMonsterAttackScreen}/{@link ManageCustomAttacksScreen}.</p>
 */
public class MonsterActionScreen extends ListPickerScreen {
	private final int entityId;
	private final List<String> actionNames;
	private final List<String> customAttackNames;

	private MonsterActionScreen(int entityId, List<String> actionNames, List<String> customAttackNames) {
		super(Component.translatable("gui.dndsheets.monster_action.title"));
		this.entityId = entityId;
		this.actionNames = actionNames;
		this.customAttackNames = customAttackNames;
	}

	public static void open(int entityId, List<String> actionNames, List<String> customAttackNames) {
		Minecraft.getInstance().setScreen(new MonsterActionScreen(entityId, actionNames, customAttackNames));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < actionNames.size(); i++) {
			int actionIndex = i;
			//Picking the action opens the player selector (the same component the DM Panel already uses
			//for "who") before sending the message — previously this always resolved against the player
			//closest to the monster, without letting you choose who to actually target.
			addRow(net.hawthorn.dndsheets.ContentNames.of(actionNames.get(i)), b ->
				PlayerPickerScreen.open(Component.translatable("gui.dndsheets.monster_action.pick_target"), uuid ->
					DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterActionChooseMessage(entityId, actionIndex, uuid))
				)
			);
		}

		addRow(Component.translatable("gui.dndsheets.monster_action.add_attack"), b -> AddMonsterAttackScreen.open(entityId));
		addRow(Component.translatable("gui.dndsheets.monster_action.manage"), b -> ManageCustomAttacksScreen.open(entityId, customAttackNames));
		//Creating a reusable monster in JSON without writing it by hand: summoning a generic NPC + giving
		//it attacks with the above are already 100% GUI — this was the only missing piece, to save the
		//result as a template (see MonsterTemplateSaveScreen/MonsterRegistry.toJson).
		addRow(Component.translatable("gui.dndsheets.monster_action.save_template"), b -> MonsterTemplateSaveScreen.open(entityId));
	}
}
