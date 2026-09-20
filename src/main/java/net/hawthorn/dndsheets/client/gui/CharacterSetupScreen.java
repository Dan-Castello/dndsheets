package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>The four steps that turn a blank sheet into a playable character, with what's missing plainly
 * visible: race, class (via preset, which also fills in ability scores, hit die, and gear), background,
 * and skill proficiencies.</p>
 *
 * <p><b>It's a list of steps, not a wizard that locks you in.</b> Each row opens the screen that already
 * existed for that step and returns here when done, so it can be done in any order, left half-finished,
 * and picked up again another day — which is how a sheet gets filled out at a real table. A linear wizard
 * would have also required its own "back" path for every step.</p>
 *
 * <p>What it actually contributes isn't opening screens — all of them were already reachable from the
 * sheet — but <b>saying which ones are missing</b>. A new player opens their blank sheet and has no way
 * of knowing there are four things to choose, or where they are; that's the same class of failure as
 * point 40, one step up: it's not that the state is invisible, it's that the TASK is invisible.</p>
 *
 * <p>Reads from the client sheet, which is already synced, so no new message is needed: the rows repaint
 * when the sheet arrives from the server (see {@link #refreshIfOpen}).</p>
 */
public class CharacterSetupScreen extends ListPickerScreen {

	private CharacterSetupScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.character_setup.title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new CharacterSetupScreen(parent));
	}

	public static void refreshIfOpen() {
		if (Minecraft.getInstance().screen instanceof CharacterSetupScreen screen) {
			screen.rebuildWidgets();
		}
	}

	@Override
	protected void buildRows() {
		JsonObject sheet = SheetLoader.getClientSheet();

		//Race is chosen by Origins (see Modularity Map / dndsheets_species), not a picker of our own: this
		//button opens Origins' real selector and syncs on its own, a few seconds later (see
		//SpeciesCommand.choose). Without the species addon installed (core-only is also a supported
		//configuration), it falls back to the mod's own list selector — see openOriginPicker.
		addRow(step("gui.dndsheets.character_setup.race", field(sheet, "characterRace")),
			button -> openOriginPicker("dndspecies choose", net.hawthorn.dndsheets.CharacterOptionsRegistry.RACE));

		//Class is also chosen by Origins (the origins-classes:class layer, entirely replaced with the 12
		//SRD classes — see Modularity Map / dndsheets_species): same pattern as Race, and it still applies
		//the real PRESET (hit die, ability scores, starting gear, traits), not just the name.
		//Without species, the class fallback is NOT the list of names but the preset selector: it's the
		//core's real mechanism (hit die, ability scores, gear), not just a label.
		addRow(step("gui.dndsheets.character_setup.class", field(sheet, "characterClass")),
			button -> {
				if (speciesLoaded()) openOriginPicker("dndspecies chooseclass");
				else DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PRESETS));
			});

		//Background is also chosen by Origins (see Modularity Map / dndsheets_species), same pattern as Race.
		addRow(step("gui.dndsheets.character_setup.background", field(sheet, "background")),
			button -> openOriginPicker("dndspecies choosebackground", net.hawthorn.dndsheets.CharacterOptionsRegistry.BACKGROUND));

		//Subclass only appears once it's actually choosable: showing a locked step to a level-1 character
		//promises something the screen will then deny. Whether it's available or not is known by the
		//server (preset and level), so the row is offered whenever there's a class and the server decides
		//whether there's a list.
		if (!field(sheet, "characterClass").isBlank()) {
			addRow(step("gui.dndsheets.character_setup.subclass", field(sheet, "characterSubclass")),
				button -> DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.LIST_SUBCLASSES)));
		}

		int proficiencies = 0;
		for (int index = 0; index < RollIndex.SKILL_COUNT; index++) {
			if (RollIndex.isSkillProficient(sheet, index)) proficiencies++;
		}
		addRow(step("gui.dndsheets.character_setup.skills", proficiencies == 0 ? "" : Component.translatable("gui.dndsheets.character_setup.skills_marked", proficiencies).getString()),
			button -> SkillProficiencyScreen.open(this));
	}

	/** A step with its progress marker: green "✔" if already chosen, gray "○" if missing. */
	private static Component step(String nameKey, String value) {
		//translatable(nameKey), not literal: the raw KEY ("gui.dndsheets.character_setup.race") used to
		//arrive here and get painted verbatim in the row — the only place in the mod where the player
		//would read a translation key on screen.
		String name = Component.translatable(nameKey).getString();
		return value.isBlank()
			? Component.literal("○ " + name + ": —").withStyle(ChatFormatting.GRAY)
			: Component.literal("✔ ").withStyle(ChatFormatting.GREEN).append(Component.literal(name + ": " + value));
	}

	private static String field(JsonObject sheet, String key) {
		return sheet != null && sheet.has(key) && sheet.get(key).isJsonPrimitive()
			? sheet.get(key).getAsString() : "";
	}

	//Closes this screen BEFORE Origins' selector arrives: leaving it open on top would steal
	//clicks/keyboard from the selector, which stayed unusable until the sheet was closed by hand. Without
	//the species addon, the command wouldn't exist (a Brigadier error in chat and no selector): the
	//caller falls back to the mod's own list selector, which captures this screen as its parent and
	//returns to it once a choice is made.
	/** Public: also decides in the SHEET's (CharacterSheetScreen) Race/Class/Background fields,
	 *  which are the other door to this same trip to Origins and had the same hole without the addon. */
	public static boolean speciesLoaded() {
		return net.minecraftforge.fml.ModList.get().isLoaded("dndsheets_species");
	}

	private void openOriginPicker(String command, String fallbackCategory) {
		if (speciesLoaded()) {
			openOriginPicker(command);
		} else {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
				BrowseActionMessage.Action.CHARACTER_OPTIONS, fallbackCategory));
		}
	}

	private static void openOriginPicker(String command) {
		ReturnFromOrigins.expect();
		Minecraft.getInstance().player.connection.sendCommand(command);
		Minecraft.getInstance().setScreen(null);
	}

	/**
	 * <p>The trip to Origins deliberately leaves the screen as null (see {@link #openOriginPicker}); this
	 * brings the player BACK to the checklist once Origins' selector closes, instead of leaving them
	 * looking at the world wondering what's next — with three steps that kick you out, this was the point
	 * of highest drop-off in character creation. The selector is recognized by its class's package
	 * ({@code io.github.apace100} = Origins/Apoli): the core doesn't compile against Origins (that
	 * dependency belongs to the species addon), so the name is the only identifier available here.</p>
	 */
	@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
	public static class ReturnFromOrigins {
		private static boolean pending = false;

		static void expect() {
			pending = true;
		}

		@net.minecraftforge.eventbus.api.SubscribeEvent
		public static void onScreenClosed(net.minecraftforge.client.event.ScreenEvent.Closing event) {
			if (!pending) return;
			if (!event.getScreen().getClass().getName().startsWith("io.github.apace100")) return;
			//tell(), not a direct setScreen: this runs INSIDE the other screen's closing, and navigating
			//at that same instant would clobber the setScreen that's closing it. One frame later, if Origins
			//opened another screen of its own (chained flows), it gets the right of way and this retries on
			//ITS closing.
			Minecraft.getInstance().tell(() -> {
				if (!pending || Minecraft.getInstance().screen != null) return;
				pending = false; //ponytail: if the player never closes another Origins selector, the flag stays armed until the next one; accepted — clearing it properly would require tracking all navigation.
				CharacterSetupScreen.open(null);
			});
		}
	}
}
