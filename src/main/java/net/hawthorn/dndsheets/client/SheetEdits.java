package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.SheetServerMessage;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;

/**
 * <p>Writes a roll formula into the client sheet and sends the sheet to the server: the one path the formula editor uses.
 * (It replaces {@code CharacterSheetSaveProcedure}, which also carried the old sheet's field-by-field save.)</p>
 */
public final class SheetEdits {
	private SheetEdits() {
	}

	/** A basic formula (check, save or skill): {@code category} 0..2. */
	public static void saveFormula(String expression, int category, int index) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;
		if (RollIndex.Category.fromInt(category).isAdvanced()) {
			DndsheetsMod.LOGGER.warn("Attempted to save an advanced roll without a subindex; operation not allowed.");
			return;
		}
		new RollIndex(category, index).saveInSheet(sheet, expression);
		send(sheet);
	}

	/** An advanced formula (an attack's damage or attack group): {@code category} 3. */
	public static void saveAdvanced(List<AbstractMap.SimpleEntry<String, String>> info, int category, int index, int subIndex) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;
		if (!RollIndex.Category.fromInt(category).isAdvanced()) {
			DndsheetsMod.LOGGER.warn("Attempted to save a basic roll with the advanced-roll method.");
			return;
		}
		new RollIndex(category, index, subIndex).saveInSheet(sheet, info);
		send(sheet);
	}

	private static void send(JsonObject sheet) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetServerMessage(sheet.toString().getBytes(StandardCharsets.UTF_8)));
	}
}
