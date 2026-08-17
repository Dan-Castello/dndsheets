package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;

/**
 * <p>El inventario es del <b>personaje</b>, no del cuerpo que lo lleva. Cambiar de personaje guarda el suyo
 * y le pone el del que entra: el bastón del mago no viaja al guerrero.</p>
 *
 * <p>Es el último de la familia "valor del jugador haciendo de valor del personaje" (nivel, vida, recursos
 * por descanso), y el que da más miedo tocar, porque equivocarse aquí <b>borra objetos</b> y no hay
 * deshacer. De ahí el orden estricto de las operaciones y el criterio conservador de más abajo.</p>
 *
 * <p><b>Se guarda como SNBT dentro de la hoja</b> en vez de en un archivo aparte: la hoja ya se guarda
 * entera y sola en cada cambio, y un segundo archivo por personaje sería otra cosa que puede quedarse
 * desincronizada del primero. El {@code ListTag} se envuelve en un compuesto porque el parser de Minecraft
 * lee compuestos, no listas sueltas.</p>
 *
 * <p><b>Un personaje sin inventario guardado empieza vacío.</b> No es pérdida: lo que llevabas se acaba de
 * escribir en la hoja del personaje que te quitas, y vuelve entero al volver a ponértelo. La alternativa
 * —quedarte con lo puesto— duplicaría cada objeto en cada cambio.</p>
 */
final class CharacterInventory {

	private static final String FIELD = "inventory";
	private static final String ITEMS = "items";

	private CharacterInventory() {
	}

	/**
	 * <p>Cambia el inventario del cuerpo: guarda el del personaje que sale y pone el del que entra.</p>
	 *
	 * <p>El orden importa y no es negociable: <b>primero se guarda y se persiste</b> lo que hay, y solo
	 * después se vacía. Si algo falla a mitad, lo peor que puede pasar es que el jugador se quede con el
	 * inventario del personaje anterior — molesto y reversible. Vaciando antes, lo peor sería no tener
	 * nada en ningún sitio.</p>
	 */
	static void swap(ServerPlayer player, String outgoingId, JsonObject outgoing, JsonObject incoming) {
		if (outgoing == incoming) return; //Ponerse el que ya llevas puesto: no hay nada que mover.

		if (outgoing != null) {
			outgoing.addProperty(FIELD, serialize(player));
			//A disco YA, antes de vaciar nada. Es la línea que convierte un fallo en una molestia.
			SheetLoader.saveCharacterSheet(outgoingId, outgoing);
		}

		player.getInventory().clearContent();
		if (incoming != null && incoming.has(FIELD)) {
			restore(player, incoming.get(FIELD).getAsString());
			//Reportado jugando: el inventario no se veía cambiado hasta abrirlo a mano. Cambiar las ranuras en
			//el servidor NO repinta la barra rápida por sí solo — el menú del jugador manda al cliente lo que
			//ha cambiado desde su última foto, y una sustitución completa hecha fuera de una interacción con
			//el menú se queda sin anunciar. broadcastFullState fuerza el envío entero, que es lo que hace
			//falta cuando lo que cambió es "todo".
			player.inventoryMenu.broadcastFullState();
		} else if (outgoing != null) {
			//Se dice. Quedarse con las manos vacías sin explicación se lee como "el mod me ha borrado las
			//cosas", y lo que ha pasado es justo lo contrario: están guardadas con el otro personaje.
			player.sendSystemMessage(net.minecraft.network.chat.Component
				.translatable("chat.dndsheets.character.inventory_swapped").withStyle(ChatFormatting.GRAY));
			player.inventoryMenu.broadcastFullState(); //Vaciarlo también hay que anunciarlo, por lo mismo.
		}
	}

	private static String serialize(ServerPlayer player) {
		CompoundTag root = new CompoundTag();
		root.put(ITEMS, player.getInventory().save(new ListTag()));
		return root.toString();
	}

	private static void restore(ServerPlayer player, String snbt) {
		try {
			CompoundTag root = TagParser.parseTag(snbt);
			//load() NO vacía lo que hubiera: escribe encima las ranuras que trae. Por eso el vaciado va antes,
			//en swap, y no aquí — si no, las ranuras que el personaje nuevo no usa conservarían lo del viejo.
			player.getInventory().load(root.getList(ITEMS, 10));
		} catch (Exception e) {
			//Una hoja con el campo corrupto deja al jugador con las manos vacías, no sin poder jugar. Se
			//registra porque es lo único que permitiría recuperarlo a mano desde el archivo.
			DndsheetsMod.LOGGER.error("dndsheets: no pude restaurar el inventario del personaje de {}: {}",
				player.getName().getString(), e.getMessage());
		}
	}
}
