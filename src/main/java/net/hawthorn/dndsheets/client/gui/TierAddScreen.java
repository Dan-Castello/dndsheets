package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Añade un nivel de la tabla de dados de un rasgo (unarmedDiceByLevel o sneakAttackDiceByLevel, ver
//TraitRegistry.parseLevelDice) — abierto desde TraitEditScreen.
public class TierAddScreen extends SmallFormScreen {
	private final JsonObject entry;
	private final String field;
	private EditBox levelBox, diceBox;

	private TierAddScreen(JsonObject entry, String field, Screen parent) {
		super(Component.literal("Añadir nivel"), 1, parent);
		this.entry = entry;
		this.field = field;
	}

	public static void open(JsonObject entry, String field) {
		Minecraft.getInstance().setScreen(new TierAddScreen(entry, field, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		levelBox = addField("Nivel", "1", 2);
		diceBox = addField("Dado", "1d6", 10);
	}

	@Override
	protected void onConfirm() {
		String dice = diceBox.getValue().trim();
		if (dice.isEmpty()) return;

		JsonObject updated = entry.deepCopy();
		JsonArray tiers = updated.has(field) ? updated.getAsJsonArray(field) : new JsonArray();
		JsonObject tier = new JsonObject();
		tier.addProperty("level", parseIntOr(levelBox.getValue(), 1));
		tier.addProperty("dice", dice);
		tiers.add(tier);
		updated.add(field, tiers);

		TraitEditScreen.save(updated);
	}
}
