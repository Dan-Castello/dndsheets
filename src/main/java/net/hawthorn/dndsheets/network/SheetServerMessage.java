
package net.hawthorn.dndsheets.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class SheetServerMessage {
	//Únicos campos que la propia hoja del jugador (CharacterSheetSaveProcedure/RollIndex) escribe alguna
	//vez. Todo lo demás (oro, espacios de conjuro, afinidades de daño, ventaja, nivel de personaje, pacto
	//del brujo, recursos de clase, salvaciones de muerte...) lo escribe el SERVIDOR por su cuenta a través
	//de comandos/mensajes ya gateados con permiso de operador (SheetGoldMessage, SheetSlotsMessage,
	//SheetDamageAffinityMessage, /dndsheet...) — sin esta lista, un cliente modificado podía mandar de
	//vuelta el JSON completo que el servidor le dio, con esos campos alterados, y pisaba el candado de
	//esos otros mensajes por completo.
	private static final Set<String> PLAYER_EDITABLE_KEYS = Set.of(
		"characterName", "characterClass", "characterRace", "background",
		"hitPoints", "hitPointsMax", "hitPointsTemp", "armorClass", "level", "speed",
		"hitDiceTypes", "hitDice", "proficiencyBonus",
		"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"
	);

	//La FÓRMULA de una tirada (no solo su resultado) es poder real: "1d20 + 999" siempre acierta. Antes
	//cualquier jugador podía reescribirla desde su propia hoja (checks/saves/skills/attacks viajaban en
	//PLAYER_EDITABLE_KEYS sin más control) — ahora solo un operador puede tocarlas. "attacks" es la
	//excepción a medias: CharacterSheetLoadProcedure.autoPopulateWeapons SÍ necesita poder seguir
	//añadiendo, del lado del jugador normal, una entrada nueva por cada arma reconocida que lleve encima
	//(con la expresión por defecto de la config, no una inventada) — ver mergeAttacks.
	private static final Set<String> OP_ONLY_ROLL_KEYS = Set.of("checks", "saves", "skills", "attacks");

	byte[] data;

	public SheetServerMessage(byte[] data) {
		this.data = data;
	}

	public SheetServerMessage(FriendlyByteBuf buffer) {
		this.data = buffer.readByteArray();
	}

	public static void buffer(SheetServerMessage message, FriendlyByteBuf buffer) {
		buffer.writeByteArray(message.data);
	}

	public static void handler(SheetServerMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			handle(context.getSender(), message.data);
		});
		context.setPacketHandled(true);
	}

	public static void handle(Player entity, byte[] data) {
		String uuid = entity.getStringUUID();
		JsonObject incoming;
		try {
			incoming = JsonParser.parseString(new String(data)).getAsJsonObject();
		} catch (JsonSyntaxException | IllegalStateException e) {
			//Payload de un cliente (cualquiera, no solo op) que no es JSON válido o no es un objeto: se
			//descarta el mensaje en vez de tumbar el hilo principal del servidor con una excepción sin capturar.
			DndsheetsMod.LOGGER.warn("Descartado SheetServerMessage con JSON inválido de {}: {}", uuid, e.toString());
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(uuid);
		if (sheet == null) return; //No debería pasar: SheetLoader.clientJoinedServer ya le da una hoja a todo jugador conectado.

		for (String key : PLAYER_EDITABLE_KEYS) {
			if (incoming.has(key)) sheet.add(key, incoming.get(key));
		}

		boolean isOp = entity.hasPermissions(2);
		for (String key : OP_ONLY_ROLL_KEYS) {
			if (!incoming.has(key)) continue;
			if (isOp) {
				sheet.add(key, incoming.get(key));
			} else if ("attacks".equals(key)) {
				sheet.add(key, mergeAttacks(sheet.has(key) ? sheet.get(key) : null, incoming.get(key)));
			}
			//checks/saves/skills: un jugador normal no tiene ningún motivo legítimo para tocarlas (a
			//diferencia de "attacks", nada las auto-rellena), así que su intento se ignora en silencio —
			//la hoja del servidor se queda con lo que ya tenía.
		}

		SheetLoader.saveServer(sheet, uuid);
		SheetLoader.applyClassHitPoints(entity, sheet);
	}

	//Fusión segura de "attacks" para un jugador SIN permiso de operador: conserva cada entrada que YA
	//existía en el servidor tal cual estaba (ignora cualquier cambio que el cliente le haya hecho a su
	//expresión de tirada, así se cuele en el mismo paquete que el auto-poblado), y solo deja pasar
	//entradas nuevas (itemId que el servidor todavía no conocía) — exactamente lo que
	//CharacterSheetLoadProcedure.autoPopulateWeapons agrega solo, con la expresión por defecto de la
	//config, nunca una inventada a mano.
	private static JsonElement mergeAttacks(JsonElement serverSide, JsonElement clientSide) {
		if (!(clientSide instanceof JsonArray incomingArr)) return serverSide != null ? serverSide : new JsonArray();
		JsonArray serverArr = serverSide instanceof JsonArray arr ? arr : new JsonArray();

		Set<String> knownItemIds = new HashSet<>();
		JsonArray merged = new JsonArray();
		for (JsonElement el : serverArr) {
			merged.add(el);
			if (el.isJsonObject() && el.getAsJsonObject().has("itemId")) {
				knownItemIds.add(el.getAsJsonObject().get("itemId").getAsString());
			}
		}
		for (JsonElement el : incomingArr) {
			if (!el.isJsonObject() || !el.getAsJsonObject().has("itemId")) continue; //Sin itemId no es un arma auto-poblada real: se descarta.
			JsonObject clientForm = el.getAsJsonObject();
			String itemId = clientForm.get("itemId").getAsString();
			if (!knownItemIds.add(itemId)) continue;

			//La expresión de tirada NUNCA viene del cliente: se reconstruye aquí desde la config del
			//servidor, igual que autoPopulateWeapons. Un itemId que la config no reconoce como arma no
			//tiene una expresión de confianza que ofrecerle, así que se descarta entero.
			Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(itemId);
			if (weaponDefault == null) continue;
			merged.add(trustedAttackEntry(clientForm, itemId, weaponDefault));
		}
		return merged;
	}

	private static JsonObject trustedAttackEntry(JsonObject clientForm, String itemId, Config.WeaponDefault weaponDefault) {
		JsonObject rollForm = new JsonObject();
		rollForm.addProperty("name", clientForm.has("name") ? clientForm.get("name").getAsString() : itemId);
		rollForm.addProperty("itemId", itemId);

		JsonObject roll = new JsonObject();
		roll.addProperty("context", "Daño");
		roll.addProperty("expression", weaponDefault.dice() + " + $" + weaponDefault.ability());

		JsonArray rollGroup = new JsonArray();
		rollGroup.add(roll);
		JsonArray rollSet = new JsonArray();
		rollSet.add(rollGroup);
		rollForm.add("rolls", rollSet);
		return rollForm;
	}
}
