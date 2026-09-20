package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.resources.language.I18n;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Adds a level to a trait's dice-by-level table (unarmedDiceByLevel or sneakAttackDiceByLevel, see
//TraitRegistry.parseLevelDice) — opened from TraitEditScreen.
public class TierAddScreen extends SmallFormScreen {
	private final JsonObject entry;
	private final String field;
	private EditBox levelBox, diceBox;

	private TierAddScreen(JsonObject entry, String field, Screen parent) {
		super(Component.translatable("gui.dndsheets.tier_add.title"), 1, parent);
		this.entry = entry;
		this.field = field;
	}

	public static void open(JsonObject entry, String field) {
		Minecraft.getInstance().setScreen(new TierAddScreen(entry, field, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		levelBox = addField(I18n.get("gui.dndsheets.form.level"), "1", 2);
		diceBox = addField(I18n.get("gui.dndsheets.form.dice"), "1d6", 10);
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
