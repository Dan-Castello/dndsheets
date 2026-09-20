package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.EncounterBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p>Encounter designer: the party is built by picking monsters from a list and, with each click, the
 * screen reports how hard the fight is for the connected party ("Medium", "Deadly"). Before, an
 * encounter was typed by hand into a text box ({@code ContentTypeForms.encounterFields}) with no
 * feedback on whether it was four goblins or four dragons: composing was already possible,
 * <em>calibrating</em> wasn't.</p>
 *
 * <p>The bestiary, each monster's XP cost, and the party's thresholds all arrive at once with the
 * screen (see {@code BrowseActionMessage.DESIGN_ENCOUNTER}): {@link EncounterBudget}'s calculation is
 * arithmetic over those numbers, so adding a goblin doesn't need to ask the server anything.</p>
 *
 * <p><b>Save doesn't save here.</b> Clicking it opens the usual content form with the composition
 * already filled in: the id, the name, and writing to {@code dm_created.json} still live in
 * {@link ContentFormScreen}/{@code ContentEntrySaveMessage}, which already handled that correctly for
 * all five content types. This screen only adds the part that was missing, choosing with judgment.</p>
 */
public class EncounterDesignerScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> names;
	private final int[] xp;
	private final int[] thresholds;
	private final int partySize;
	//monster id -> how many. Insertion-ordered: the list reads in the order the DM built it in.
	private final Map<String, Integer> chosen = new LinkedHashMap<>();

	private EncounterDesignerScreen(List<String> ids, List<Component> names, JsonObject payload, Screen parent) {
		super(Component.translatable("gui.dndsheets.encounter_designer.title"), parent);
		this.ids = ids;
		this.names = names;
		this.xp = intArray(payload, "xp", ids.size());
		this.thresholds = intArray(payload, "t", 4);
		this.partySize = payload.has("p") ? payload.get("p").getAsInt() : 0;
	}

	public static void open(List<String> ids, List<Component> names, String payloadJson) {
		JsonObject payload;
		try {
			payload = JsonParser.parseString(payloadJson).getAsJsonObject();
		} catch (RuntimeException e) {
			return; //Corrupted or cross-version payload: without a budget there's no designer to show.
		}
		Minecraft.getInstance().setScreen(
			new EncounterDesignerScreen(ids, names, payload, Minecraft.getInstance().screen));
	}

	private static int[] intArray(JsonObject payload, String key, int size) {
		int[] values = new int[size];
		if (!payload.has(key)) return values;
		List<JsonElement> elements = payload.getAsJsonArray(key).asList();
		for (int i = 0; i < Math.min(size, elements.size()); i++) values[i] = elements.get(i).getAsInt();
		return values;
	}

	//Room for the verdict line, which is what gets checked between clicks.
	@Override
	protected int listTop() {
		return super.listTop() + 12;
	}

	@Override
	protected void buildRows() {
		for (Map.Entry<String, Integer> member : chosen.entrySet()) {
			String id = member.getKey();
			int count = member.getValue();
			int index = ids.indexOf(id);
			Component name = index < 0 ? Component.literal(id) : names.get(index);
			addRow(Component.translatable("gui.dndsheets.encounter_designer.row", name, count, xpOf(id) * count),
				//Click adds one and shift+click removes one (hitting zero drops it): one button per row
				//instead of three, which in a fixed-width list is the difference between reading the name and not.
				b -> {
					int updated = count + (hasShiftDown() ? -1 : 1);
					if (updated <= 0) chosen.remove(id);
					else chosen.put(id, updated);
					rebuildWidgets();
				});
		}

		addRow(Component.translatable("gui.dndsheets.encounter_designer.add"),
			b -> MonsterPickScreen.open(this, ids, names, id -> {
				chosen.merge(id, 1, Integer::sum);
				rebuildWidgets();
			}));

		if (!chosen.isEmpty()) {
			addRow(Component.translatable("gui.dndsheets.encounter_designer.save"), b -> save());
		}

		//Editing or deleting what's already saved used to only be possible from "Create content", which is
		//where nobody's going to look for it: whoever just designed an encounter is here, not three menus away.
		addRow(Component.translatable("gui.dndsheets.encounter_designer.manage"),
			b -> net.hawthorn.dndsheets.DndsheetsMod.PACKET_HANDLER.sendToServer(
				new net.hawthorn.dndsheets.network.BrowseActionMessage(
					net.hawthorn.dndsheets.network.BrowseActionMessage.Action.CONTENT_ENTRIES,
					ContentType.ENCOUNTER.name())));
	}

	@Override
	protected Component emptyMessage() {
		return null; //Never empty: "+ Add monster" is always there.
	}

	/** The composition in the same syntax as the JSON and the form: "dndsheets:goblin x4, dndsheets:wolf x2". */
	private String composition() {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> member : chosen.entrySet()) {
			if (text.length() > 0) text.append(", ");
			text.append(member.getKey());
			if (member.getValue() > 1) text.append(" x").append(member.getValue());
		}
		return text.toString();
	}

	private void save() {
		ContentFormScreen.open(ContentType.ENCOUNTER,
			Component.translatable("gui.dndsheets.encounter_designer.save").getString(),
			ContentTypeForms.encounterFields(), Map.of("monsters", composition()),
			ContentTypeForms::encounterToJson);
	}

	private int xpOf(String monsterId) {
		int index = ids.indexOf(monsterId);
		return index < 0 ? 0 : xp[index];
	}

	private int totalXp() {
		int total = 0;
		for (Map.Entry<String, Integer> member : chosen.entrySet()) total += xpOf(member.getKey()) * member.getValue();
		return total;
	}

	private int totalMonsters() {
		int total = 0;
		for (int count : chosen.values()) total += count;
		return total;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);

		int rating = EncounterBudget.rate(totalXp(), totalMonsters(), partySize, thresholds);
		Component summary;
		if (chosen.isEmpty()) {
			//Before picking anything, the line explains how to use the screen; the verdict for an empty
			//encounter ("Trivial") isn't information, it's noise.
			summary = Component.translatable("gui.dndsheets.encounter_designer.hint");
		} else if (rating < 0) {
			summary = Component.translatable("gui.dndsheets.encounter_designer.no_party", totalXp());
		} else {
			summary = Component.translatable("gui.dndsheets.encounter_designer.summary",
				net.hawthorn.dndsheets.network.BrowseActionMessage.difficultyName(rating), totalXp(), partySize);
		}
		guiGraphics.drawCenteredString(this.font, summary, this.width / 2, super.listTop(), GuiStyle.SUBTITLE_COLOR);
	}

	/** The bestiary to pick one from; returns to the designer with the pick (and whatever was already chosen). */
	private static class MonsterPickScreen extends ListPickerScreen {
		private final List<String> ids;
		private final List<Component> names;
		private final Consumer<String> onPick;

		private MonsterPickScreen(Screen parent, List<String> ids, List<Component> names, Consumer<String> onPick) {
			super(Component.translatable("gui.dndsheets.encounter_designer.pick"), parent);
			this.ids = ids;
			this.names = names;
			this.onPick = onPick;
		}

		static void open(Screen parent, List<String> ids, List<Component> names, Consumer<String> onPick) {
			Minecraft.getInstance().setScreen(new MonsterPickScreen(parent, ids, names, onPick));
		}

		@Override
		protected void buildRows() {
			for (int i = 0; i < ids.size(); i++) {
				String id = ids.get(i);
				addRow(i < names.size() ? names.get(i) : Component.literal(id), b -> {
					onPick.accept(id);
					this.onClose(); //Returns to the designer, which keeps what was already chosen.
				});
			}
		}
	}
}
