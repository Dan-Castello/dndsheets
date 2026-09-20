package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.hawthorn.dndsheets.Combatant;
import net.hawthorn.dndsheets.DamageTypes;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.AddCustomAttackMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Form to give a new attack to ONE monster already summoned (see
 * {@link net.hawthorn.dndsheets.MonsterRegistry#addCustomAttack}), opened from the
 * "+ Add attack" button of {@link MonsterActionScreen}. Attack/damage ability and damage type are chosen
 * with cycle buttons instead of free text, so as not to depend on remembering to type "str"/"dex" right
 * — name and dice stay as text because they don't have a fixed catalog of options.</p>
 */
public class AddMonsterAttackScreen extends SmallFormScreen {
	//Only for display on the cycle button (see SmallFormScreen.addCycleButton) — the real value that gets
	//saved/sent to the server is still Combatant.ABILITIES' short code, which is what the rest of the
	//code expects (MonsterRegistry.abilityModifier looks up by lowercase "str"/"dex"/...).
	private static String[] abilityLabels() {
		return java.util.stream.Stream.of("str", "dex", "con", "int", "wis", "cha")
			.map(a -> I18n.get("gui.dndsheets.character_sheet.ability_" + a)).toArray(String[]::new);
	}

	private final int entityId;
	private EditBox nameBox;
	private EditBox diceBox;
	private CycleField toHit;
	private CycleField damageAbility;
	private CycleField damageType;

	private AddMonsterAttackScreen(int entityId, Screen parent) {
		super(Component.translatable("gui.dndsheets.add_attack.title"), 3, parent);
		this.entityId = entityId;
	}

	public static void open(int entityId) {
		Minecraft.getInstance().setScreen(new AddMonsterAttackScreen(entityId, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		nameBox = addField(I18n.get("gui.dndsheets.form.name"), I18n.get("gui.dndsheets.form.attack"), 40);
		diceBox = addField(I18n.get("gui.dndsheets.form.dice"), "1d6", 20);
		toHit = addCycleButton(I18n.get("gui.dndsheets.form.attack_with"), Combatant.ABILITIES, abilityLabels());
		damageAbility = addCycleButton(I18n.get("gui.dndsheets.form.damage_with"), Combatant.ABILITIES, abilityLabels());
		damageType = addCycleButton(I18n.get("gui.dndsheets.form.type"), DamageTypes.CANONICAL);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().isBlank() ? I18n.get("gui.dndsheets.form.attack") : nameBox.getValue();
		String dice = diceBox.getValue().isBlank() ? "1d6" : diceBox.getValue();
		DndsheetsMod.PACKET_HANDLER.sendToServer(new AddCustomAttackMessage(entityId, name, toHit.value(), dice, damageAbility.value(), damageType.value()));
	}
}
