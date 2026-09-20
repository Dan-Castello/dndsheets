package net.hawthorn.dndsheets.api.event;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * <p>Fired at the end of {@code SheetLoader.switchCharacter(player, characterId)}, with the new character
 * already active (sheet sent to the client, HP restored). Meant for a content mod to reconcile state that
 * lives OUTSIDE the sheet but needs to track the active character — the real-world case is
 * {@code dndsheets_species}: Origins stores the chosen race/background per player ACCOUNT, not per
 * character, so without this event a player's second character silently inherited the first one's race,
 * with no selector and no way to change it without also changing the other one's.</p>
 * <p>Not cancelable: this is a "react to" point, not a place to block the character switch (which has
 * already happened by the time this fires).</p>
 */
public class CharacterSwitchedEvent extends Event {
	private final ServerPlayer player;
	private final String characterId;
	private final JsonObject sheet;

	public CharacterSwitchedEvent(ServerPlayer player, String characterId, JsonObject sheet) {
		this.player = player;
		this.characterId = characterId;
		this.sheet = sheet;
	}

	public ServerPlayer getPlayer() {
		return player;
	}

	public String getCharacterId() {
		return characterId;
	}

	/** @return the actual sheet of the character that just became active (not a copy). */
	public JsonObject getSheet() {
		return sheet;
	}
}
