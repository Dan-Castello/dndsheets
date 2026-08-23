package net.hawthorn.dndsheets.api.event;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * <p>Se dispara al final de {@code SheetLoader.switchCharacter(player, characterId)}, con el personaje
 * nuevo ya activo (hoja enviada al cliente, PG restaurados). Pensado para que un mod de contenido
 * reconcilie estado que vive FUERA de la hoja pero necesita seguir al personaje activo — el caso real es
 * {@code dndsheets_species}: Origins guarda la raza/trasfondo elegidos por CUENTA de jugador, no por
 * personaje, así que sin este evento el segundo personaje de alguien heredaba en silencio la raza del
 * primero, sin selector y sin forma de cambiarla sin also cambiar la del otro.</p>
 * <p>No es cancelable: es un punto de "reaccionar a", no de bloquear el cambio de personaje (que ya pasó
 * cuando esto se dispara).</p>
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

	/** @return la hoja real del personaje que acaba de quedar activo (no una copia). */
	public JsonObject getSheet() {
		return sheet;
	}
}
