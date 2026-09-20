package net.hawthorn.dndsheets.client.procedures;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.CharacterSheetScreen;
import net.hawthorn.dndsheets.client.gui.components.RollScrollWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import net.hawthorn.dndsheets.SheetLoader;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public class CharacterSheetLoadProcedure {

	public static void execute(HashMap<String, Object> guistate, CharacterSheetScreen screen) {
		if (guistate == null)
 {
			DndsheetsMod.LOGGER.warn("CharacterSheetLoadProcedure.execute called without a guistate.");
			return;
		}
		if (SheetLoader.getClientSheet() == null ) {
			DndsheetsMod.LOGGER.warn("The client has no sheet loaded; the GUI may look wrong.");
			return;
		}
		JsonObject sheet = SheetLoader.getClientSheet();
		SheetLoader.validateSheet(sheet);
		if (guistate.get("text:charactername") instanceof EditBox _tf && sheet.has("characterName")) {
			String charName = sheet.get("characterName").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:characterclass") instanceof EditBox _tf && sheet.has("characterClass")) {
			String charName = sheet.get("characterClass").getAsString();
			_tf.setValue(charName);
			//Without this, the ghost suggestion ("Guerrero 1"/"Fighter 1", set once when the field is built in
			//CharacterSheetScreen) keeps drawing right after the actual value chosen
			//in the selector — before, insertText()/moveCursorTo() cleared it when typing by hand, but those
			//overrides no longer exist because the field stopped accepting free text.
			if (!charName.isEmpty()) _tf.setSuggestion(null);
		}
		if (guistate.get("text:characterrace") instanceof EditBox _tf && sheet.has("characterRace")) {
			String charName = sheet.get("characterRace").getAsString();
			_tf.setValue(charName);
			if (!charName.isEmpty()) _tf.setSuggestion(null);
		}
		if (guistate.get("text:background") instanceof EditBox _tf && sheet.has("background")) {
			String charName = sheet.get("background").getAsString();
			_tf.setValue(charName);
			if (!charName.isEmpty()) _tf.setSuggestion(null);
		}
		if (guistate.get("text:hitpoints") instanceof EditBox _tf && sheet.has("hitPoints")) {
			String charName = sheet.get("hitPoints").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:hitpoints_max") instanceof EditBox _tf && sheet.has("hitPointsMax")) {
			String charName = sheet.get("hitPointsMax").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:hitpoints_temp") instanceof EditBox _tf && sheet.has("hitPointsTemp")) {
			String charName = sheet.get("hitPointsTemp").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:armorclass") instanceof EditBox _tf && sheet.has("armorClass")) {
			String charName = sheet.get("armorClass").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:level") instanceof EditBox _tf && sheet.has("level")) {
			String charName = sheet.get("level").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:speed") instanceof EditBox _tf && sheet.has("speed")) {
			String charName = sheet.get("speed").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:hitdice_types") instanceof EditBox _tf && sheet.has("hitDiceTypes")) {
			String charName = sheet.get("hitDiceTypes").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:hitdice") instanceof EditBox _tf && sheet.has("hitDice")) {
			String charName = sheet.get("hitDice").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:proficiency") instanceof EditBox _tf && sheet.has("proficiencyBonus")) {
			String charName = sheet.get("proficiencyBonus").getAsString();
			_tf.setValue(charName);
		}

		if (guistate.get("text:strength") instanceof EditBox _tf && sheet.has("strength")) {
			String charName = sheet.get("strength").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:dexterity") instanceof EditBox _tf && sheet.has("dexterity")) {
			String charName = sheet.get("dexterity").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:constitution") instanceof EditBox _tf && sheet.has("constitution")) {
			String charName = sheet.get("constitution").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:intelligence") instanceof EditBox _tf && sheet.has("intelligence")) {
			String charName = sheet.get("intelligence").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:wisdom") instanceof EditBox _tf && sheet.has("wisdom")) {
			String charName = sheet.get("wisdom").getAsString();
			_tf.setValue(charName);
		}
		if (guistate.get("text:charisma") instanceof EditBox _tf && sheet.has("charisma")) {
			String charName = sheet.get("charisma").getAsString();
			_tf.setValue(charName);
		}

		if (guistate.get("scrolllist:attack_rolls") instanceof RollScrollWidget _tf && sheet.has("attacks")) {
			//This sheet may not be the first one to arrive while the character sheet stays open (changing
			//race, applying a preset, resting, leveling up...): without clearing first, each arrival stacked
			//attack rows on top of the previous ones instead of replacing them. See CharacterSheetScreen#clearScrollList.
			screen.clearScrollList(_tf);
			autoPopulateWeapons(sheet);

			JsonArray arr = sheet.getAsJsonArray("attacks");
			for (int i = 0; i < arr.size(); i++) {
				JsonObject rollForm = arr.get(i).getAsJsonObject();
				screen.addToScrollList(_tf, rollForm, 3, i, CharacterSheetScreen.PanelStatus.ATTACKS);
			}

		}
	}

	/**
	 * <p>Adds to the Attacks tab any weapon (recognized in the config) the player
	 * carries in their inventory that doesn't already have an entry there, with the default damage from
	 * dndsheets-common.toml. Doesn't touch existing entries, so any manual adjustment
	 * (including the die itself) is preserved between openings of the sheet.</p>
	 */
	private static void autoPopulateWeapons(JsonObject sheet) {
		if (Minecraft.getInstance().player == null) return;
		Inventory inventory = Minecraft.getInstance().player.getInventory();

		JsonArray attacks = sheet.getAsJsonArray("attacks");
		Set<String> knownItemIds = new LinkedHashSet<>();
		for (int i = 0; i < attacks.size(); i++) {
			JsonObject form = attacks.get(i).getAsJsonObject();
			if (form.has("itemId")) knownItemIds.add(form.get("itemId").getAsString());
		}

		Set<String> seenThisScan = new LinkedHashSet<>();
		for (ItemStack stack : inventory.items) {
			addWeaponIfNew(attacks, stack, knownItemIds, seenThisScan);
		}
		addWeaponIfNew(attacks, inventory.offhand.get(0), knownItemIds, seenThisScan);
	}

	private static void addWeaponIfNew(JsonArray attacks, ItemStack stack, Set<String> knownItemIds, Set<String> seenThisScan) {
		if (stack.isEmpty()) return;
		String itemId = Config.weaponIdOf(stack); //Honors the NBT tag {dndsheets:{weapon:"..."}} if the item carries it.
		if (knownItemIds.contains(itemId) || seenThisScan.contains(itemId)) return;

		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(itemId);
		if (weaponDefault == null) return; //Not a recognized weapon, leave it out of the list.
		seenThisScan.add(itemId);

		JsonObject rollForm = new JsonObject();
		rollForm.addProperty("name", stack.getHoverName().getString());
		rollForm.addProperty("itemId", itemId);

		JsonObject roll = new JsonObject();
		roll.addProperty("context", "Damage");
		roll.addProperty("expression", weaponDefault.dice() + " + $" + weaponDefault.ability());

		JsonArray rollGroup = new JsonArray();
		rollGroup.add(roll);
		JsonArray rollSet = new JsonArray();
		rollSet.add(rollGroup);
		rollForm.add("rolls", rollSet);

		attacks.add(rollForm);
	}
}
