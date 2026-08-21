package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.Combatant;
import net.hawthorn.dndsheets.DamageTypes;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.AddCustomAttackMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Formulario para darle un ataque nuevo a UN monstruo ya invocado (ver
 * {@link net.hawthorn.dndsheets.MonsterRegistry#addCustomAttack}), abierto desde el botón
 * "+ Añadir ataque" de {@link MonsterActionScreen}. Habilidad de ataque/daño y tipo de daño se eligen
 * con botones cíclicos en vez de texto libre, para no depender de acordarse de escribir "str"/"dex" bien
 * — nombre y dado siguen siendo texto porque no tienen un catálogo fijo de opciones.</p>
 */
public class AddMonsterAttackScreen extends SmallFormScreen {
	//Solo para mostrar en el botón cíclico (ver SmallFormScreen.addCycleButton) — el valor real que se
	//guarda/manda al servidor sigue siendo el código corto de Combatant.ABILITIES, ese es el que espera el resto del
	//código (MonsterRegistry.abilityModifier busca por "str"/"dex"/... en minúsculas).
	private static final String[] ABILITY_LABELS = {"Fuerza", "Destreza", "Constitución", "Inteligencia", "Sabiduría", "Carisma"};

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
		nameBox = addField("Nombre", "Ataque", 40);
		diceBox = addField("Dado", "1d6", 20);
		toHit = addCycleButton("Ataque con", Combatant.ABILITIES, ABILITY_LABELS);
		damageAbility = addCycleButton("Daño con", Combatant.ABILITIES, ABILITY_LABELS);
		damageType = addCycleButton("Tipo", DamageTypes.CANONICAL);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().isBlank() ? "Ataque" : nameBox.getValue();
		String dice = diceBox.getValue().isBlank() ? "1d6" : diceBox.getValue();
		DndsheetsMod.PACKET_HANDLER.sendToServer(new AddCustomAttackMessage(entityId, name, toHit.value(), dice, damageAbility.value(), damageType.value()));
	}
}
