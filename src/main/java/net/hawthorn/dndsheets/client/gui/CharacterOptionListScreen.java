package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.SheetServerMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>The core mod's own Race/Background/Class selector: the list is sent by the server in the
 * CHARACTER_OPTION case of {@code BrowseListMessage} (requested with
 * {@code BrowseActionMessage.CHARACTER_OPTIONS}), because the options registry only lives in the
 * server's memory — same pattern as {@link PresetScreen}/{@link TraitGrantScreen}. With the species addon
 * installed this selector isn't used (Origins picks and the addon writes the sheet); it's the core-only
 * FALLBACK path — see {@code CharacterSetupScreen.openOriginPicker}. Choosing an option writes the value
 * directly to the client sheet and syncs it, without going through the server to validate it (unlike a
 * preset, this doesn't touch stats): "characterClass"/"characterRace"/"background" are already
 * free-editing fields for the player per {@code SheetServerMessage.PLAYER_EDITABLE_KEYS}, choosing from a
 * fixed list instead of typing them by hand doesn't change that contract.</p>
 *
 * <p>Returns to the SAME screen that requested the list both when an option is chosen and when
 * "&lt; Back" or Escape is pressed — it's passed as {@code parent} to {@link ListPickerScreen}, captured
 * in the message handler at the exact instant right before navigating, when the active screen is the one
 * that made the request.</p>
 */
public class CharacterOptionListScreen extends ListPickerScreen {
	private final String category;
	private final List<String> options;

	private CharacterOptionListScreen(Screen returnTo, String category, List<String> options) {
		super(Component.translatable(titleFor(category)), returnTo);
		this.category = category;
		this.options = options;
	}

	public static void open(Screen returnTo, String category, List<String> options) {
		Minecraft.getInstance().setScreen(new CharacterOptionListScreen(returnTo, category, options));
	}

	private static String titleFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.CLASS -> "gui.dndsheets.option_list.title_class";
			case CharacterOptionsRegistry.RACE -> "gui.dndsheets.option_list.title_race";
			case CharacterOptionsRegistry.BACKGROUND -> "gui.dndsheets.option_list.title_background";
			default -> "gui.dndsheets.option_list.title_default";
		};
	}

	/**
	 * <p>Which sheet field the chosen value goes to. <b>Deliberately exhaustive</b>: when this switch had a
	 * {@code default -> "background"}, choosing a RACE would write the value into the background. The race
	 * never changed — there was nothing visibly broken to look at, it simply didn't happen — and on top of
	 * that it silently overwrote whatever background was already set. A new category with no case here
	 * returns null and writes nothing, which is noticeable; before, it landed in the wrong field, which
	 * wasn't.</p>
	 */
	private static String sheetFieldFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.CLASS -> "characterClass";
			case CharacterOptionsRegistry.RACE -> "characterRace";
			case CharacterOptionsRegistry.BACKGROUND -> "background";
			default -> null;
		};
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String option : options) {
			addRow(Component.literal(option), b -> {
				JsonObject sheet = SheetLoader.getClientSheet();
				String field = sheetFieldFor(category);
				if (sheet != null && field != null) {
					sheet.addProperty(field, option);
					DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetServerMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
				}
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return options.isEmpty() ? Component.translatable("gui.dndsheets.character_option.empty") : null;
	}
}
