package net.hawthorn.dndsheets.species;

import net.hawthorn.dndsheets.TraitRegistry;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * <p>Punto de entrada del addon de especies. No necesita canal de red propio ni registro de contenido
 * vía JSON para los rasgos raciales: son descriptivos (sin mecánica automatizada, mismo criterio que
 * {@link TraitRegistry} documenta para los rasgos de clase — "un campo más aquí, no un motor genérico"),
 * así que se registran directo en código, una vez, al construir el mod.</p>
 *
 * <p>{@code /dndspecies sync} (ver {@code command.RaceCommand}) reusa {@code SheetLoader}/{@code
 * DndsheetsMod.sendSheetFieldUpdate} del core (dependencia dura) en vez de inventar su propio camino de
 * escritura a la ficha — invariante 4 de PROJECT_CONTEXT.md: toda mutación de hoja persiste por el mismo
 * camino que ya existe.</p>
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
		trait("fey_ancestry", "Ascendencia Feérica");
		trait("trance", "Trance");
		trait("dwarven_resilience", "Resiliencia Enana");
		trait("stonecunning", "Conocimiento Pétreo");
		trait("lucky", "Suerte");
		trait("brave", "Coraje");
		trait("draconic_ancestry", "Ascendencia Dracónica");
		trait("gnome_cunning", "Astucia Gnoma");
		trait("skill_versatility", "Versatilidad de Habilidad");
		trait("relentless_endurance", "Resistencia Implacable");
		trait("savage_attacks", "Ataques Salvajes");
		trait("hellish_resistance", "Resistencia Infernal");
		trait("infernal_legacy", "Legado Infernal");
	}

	private static void registerBackgroundTraits() {
		trait("shelter_of_the_faithful", "Refugio del Fiel");
		trait("false_identity", "Identidad Falsa");
		trait("criminal_contact", "Contacto Criminal");
		trait("by_popular_demand", "Por Petición Popular");
		trait("rustic_hospitality", "Hospitalidad Rústica");
		trait("guild_membership", "Membresía del Gremio");
		trait("discovery", "Descubrimiento");
		trait("position_of_privilege", "Posición Privilegiada");
		trait("wanderer", "Vagabundo");
		trait("researcher", "Investigador");
		trait("ships_passage", "Pasaje de Barco");
		trait("military_rank", "Rango Militar");
		trait("city_secrets", "Contactos en la Ciudad");
	}
}
