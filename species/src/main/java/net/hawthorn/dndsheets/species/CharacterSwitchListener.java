package net.hawthorn.dndsheets.species;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.api.event.CharacterSwitchedEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Reconcilia Origins con el personaje que acaba de quedar activo (ver {@code CharacterSwitchedEvent}
 * en el core). Origins guarda una sola elección por CUENTA de jugador para cada capa — no sabe que
 * dndsheets tiene varios personajes — así que sin esto, el segundo personaje de alguien heredaba en
 * silencio la raza/trasfondo/clase del primero, sin selector propio y sin forma de cambiarla sin also
 * cambiar la del otro (el mismo origen "vivía" en un solo sitio compartido).</p>
 *
 * <p>La ficha de cada personaje ya guarda qué origen se le aplicó ({@code appliedRaceId}/
 * {@code appliedBackgroundId}/{@code appliedPresetId} — este último hace doble uso porque el id de
 * origen de clase y el id de preset son el mismo string a propósito, ver {@code SpeciesCommand}), así
 * que la ficha es la fuente de verdad real; esto solo empuja ese valor DE VUELTA a Origins para que su
 * selector muestre lo que corresponde a quien está activo ahora, no lo último que alguien haya elegido.</p>
 *
 * <p>Si el personaje nuevo todavía no tiene nada aplicado en alguna capa, no se toca esa capa — Origins
 * se queda mostrando lo del personaje anterior hasta que el jugador elija algo con {@code /dndspecies
 * choose*} y sincronice; es preferible a "limpiar" la elección, porque Origins no tiene un método público
 * para dejar una capa sin elegir.</p>
 */
@Mod.EventBusSubscriber
public class CharacterSwitchListener {
	private static final ResourceLocation RACE_LAYER = new ResourceLocation("dndsheets_species", "race");
	private static final ResourceLocation BACKGROUND_LAYER = new ResourceLocation("dndsheets_species", "background");
	private static final ResourceLocation CLASS_LAYER = new ResourceLocation("origins-classes", "class");

	@SubscribeEvent
	public static void onCharacterSwitched(CharacterSwitchedEvent event) {
		JsonObject sheet = event.getSheet();
		pushIfPresent(sheet, "appliedRaceId", RACE_LAYER, event);
		pushIfPresent(sheet, "appliedBackgroundId", BACKGROUND_LAYER, event);
		pushIfPresent(sheet, "appliedPresetId", CLASS_LAYER, event);
	}

	private static void pushIfPresent(JsonObject sheet, String field, ResourceLocation layerId, CharacterSwitchedEvent event) {
		if (!sheet.has(field)) return;
		String originId = sheet.get(field).getAsString();
		if (originId.isBlank()) return;
		OriginBridge.pushOriginId(event.getPlayer(), layerId, new ResourceLocation("dndsheets_species", originId));
	}
}
