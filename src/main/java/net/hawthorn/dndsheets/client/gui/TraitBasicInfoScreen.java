package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.Combatant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Nombre + característica del golpe desarmado de un rasgo — el id no se toca (ver TraitEditScreen). Las
//listas de nivel/dado se editan aparte, en TraitEditScreen/TierAddScreen.
public class TraitBasicInfoScreen extends SmallFormScreen {

	private final JsonObject entry;
	private EditBox nameBox;
	private CycleField abilityField;

	private TraitBasicInfoScreen(JsonObject entry, Screen parent) {
		super(Component.translatable("gui.dndsheets.trait_basic.title", entry.get("id").getAsString()), 1, parent);
		this.entry = entry;
	}

	public static void open(JsonObject entry) {
		Minecraft.getInstance().setScreen(new TraitBasicInfoScreen(entry, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		String name = entry.has("name") ? entry.get("name").getAsString() : entry.get("id").getAsString();
		nameBox = addField("Nombre", name, 64);

		String currentAbility = entry.has("unarmedAbility") ? entry.get("unarmedAbility").getAsString() : "str";
		int index = 0;
		for (int i = 0; i < Combatant.ABILITIES.length; i++) if (Combatant.ABILITIES[i].equalsIgnoreCase(currentAbility)) index = i;
		abilityField = addCycleButton("Característica (golpe desarmado)", Combatant.ABILITIES, Combatant.ABILITIES, index);
	}

	@Override
	protected void onConfirm() {
		JsonObject updated = entry.deepCopy();
		String name = nameBox.getValue().trim();
		if (name.isEmpty()) updated.remove("name"); else updated.addProperty("name", name);
		updated.addProperty("unarmedAbility", abilityField.value());
		TraitEditScreen.save(updated);
	}
}
