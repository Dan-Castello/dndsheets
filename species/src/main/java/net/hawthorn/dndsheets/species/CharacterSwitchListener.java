package net.hawthorn.dndsheets.species;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.api.event.CharacterSwitchedEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Reconciles Origins with the character that just became active (see {@code CharacterSwitchedEvent}
 * in the core). Origins stores a single choice per player ACCOUNT for each layer — it doesn't know
 * dndsheets has multiple characters — so without this, someone's second character would silently
 * inherit the race/background/class of the first, with no selector of its own and no way to change it
 * without also changing the other's (the same origin "lived" in a single shared slot).</p>
 *
 * <p>Each character's sheet already stores which origin was applied to it ({@code appliedRaceId}/
 * {@code appliedBackgroundId}/{@code appliedPresetId} — the latter does double duty because the class
 * origin id and the preset id are the same string by design, see {@code SpeciesCommand}), so the sheet
 * is the real source of truth; this just pushes that value BACK to Origins so its selector shows what
 * corresponds to whoever is active now, not the last thing anyone chose.</p>
 *
 * <p>If the new character doesn't have anything applied yet in some layer, that layer is left untouched —
 * Origins keeps showing the previous character's choice until the player picks something with
 * {@code /dndspecies choose*} and it syncs; that's preferable to "clearing" the choice, because Origins
 * has no public method to leave a layer unchosen.</p>
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
