package net.hawthorn.dndsheets.species;

import net.hawthorn.dndsheets.TraitRegistry;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * <p>Entry point of the species addon. It needs neither its own network channel nor JSON-based content
 * registration for racial traits: they're descriptive (no automated mechanic, same criterion that
 * {@link TraitRegistry} documents for class traits — "one more field here, not a generic engine"),
 * so they're registered directly in code, once, when the mod is built.</p>
 *
 * <p>{@code /dndspecies sync} (see {@code command.RaceCommand}) reuses the core's {@code SheetLoader}/
 * {@code DndsheetsMod.sendSheetFieldUpdate} (hard dependency) instead of inventing its own sheet-write
 * path — invariant 4 of PROJECT_CONTEXT.md: every sheet mutation persists through the same existing
 * path.</p>
 */
@Mod("dndsheets_species")
public class DndsheetsSpeciesMod {
	public static final String MODID = "dndsheets_species";

	public DndsheetsSpeciesMod() {
		registerRaceTraits();
		registerBackgroundTraits();
	}

	private static void trait(String id, String name) {
		TraitRegistry.register(new TraitRegistry.Trait(MODID + ":" + id, name, null, List.of(), List.of()));
	}

	private static void registerRaceTraits() {
		trait("fey_ancestry", "Fey Ancestry");
		trait("trance", "Trance");
		trait("dwarven_resilience", "Dwarven Resilience");
		trait("stonecunning", "Stonecunning");
		trait("lucky", "Lucky");
		trait("brave", "Brave");
		trait("draconic_ancestry", "Draconic Ancestry");
		trait("gnome_cunning", "Gnome Cunning");
		trait("skill_versatility", "Skill Versatility");
		trait("relentless_endurance", "Relentless Endurance");
		trait("savage_attacks", "Savage Attacks");
		trait("hellish_resistance", "Hellish Resistance");
		trait("infernal_legacy", "Infernal Legacy");
	}

	private static void registerBackgroundTraits() {
		trait("shelter_of_the_faithful", "Shelter of the Faithful");
		trait("false_identity", "False Identity");
		trait("criminal_contact", "Criminal Contact");
		trait("by_popular_demand", "By Popular Demand");
		trait("rustic_hospitality", "Rustic Hospitality");
		trait("guild_membership", "Guild Membership");
		trait("discovery", "Discovery");
		trait("position_of_privilege", "Position of Privilege");
		trait("wanderer", "Wanderer");
		trait("researcher", "Researcher");
		trait("ships_passage", "Ship's Passage");
		trait("military_rank", "Military Rank");
		trait("city_secrets", "City Secrets");
	}
}
