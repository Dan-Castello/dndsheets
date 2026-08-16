package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/**
 * <p>Recursos de <b>una vez por descanso</b> (Segundo Aliento, Expulsar Muertos Vivientes, Recuperación
 * Arcana): se gastan, y un descanso los devuelve.</p>
 *
 * <p>Vivían en un {@code Set<UUID>} por manager, indexado por <b>jugador</b>. Eso está mal por dos motivos
 * distintos, y los dos se notan jugando:</p>
 *
 * <ul>
 *   <li><b>Son del personaje, no de quien lo lleva.</b> Con dos personajes, gastar el Segundo Aliento con
 *       uno se lo gastaba al otro — la misma familia de fallo que el nivel y la vida compartidos.</li>
 *   <li><b>No sobrevivían a un reinicio.</b> El conjunto vive en memoria, así que reiniciar el servidor le
 *       devolvía a todo el mundo sus recursos gastados sin haber descansado.</li>
 * </ul>
 *
 * <p>En la hoja los dos problemas desaparecen a la vez, y de paso queda donde ya viven el Castigo armado y
 * el dado de Inspiración, que siempre estuvieron bien.</p>
 */
final class RestResource {

	/** Segundo Aliento del guerrero: una vez por descanso corto o largo. */
	static final String SECOND_WIND = "secondWindUsed";
	/** Canalizar Divinidad del clérigo: igual, corto o largo. */
	static final String CHANNEL_DIVINITY = "channelDivinityUsed";
	/** Recuperación Arcana del mago: solo la devuelve un descanso LARGO. */
	static final String ARCANE_RECOVERY = "arcaneRecoveryUsed";

	private RestResource() {
	}

	static boolean isSpent(JsonObject sheet, String key) {
		return sheet != null && sheet.has(key) && sheet.get(key).getAsBoolean();
	}

	/**
	 * <p>Lo gasta. Devuelve {@code false} si ya estaba gastado, que es la comprobación y el gasto en una sola
	 * llamada — igual que hacía {@code Set.add}, para que quien llama no tenga que acordarse de hacer las dos.</p>
	 */
	static boolean spend(ServerPlayer player, String key) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || isSpent(sheet, key)) return false;
		sheet.addProperty(key, true);
		SheetLoader.saveServer(sheet, player.getStringUUID());
		return true;
	}

	/** Lo devuelve. No hace nada si no estaba gastado, para no escribir la hoja en cada descanso de cada uno. */
	static void restore(ServerPlayer player, String key) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (!isSpent(sheet, key)) return;
		sheet.remove(key);
		SheetLoader.saveServer(sheet, player.getStringUUID());
	}
}
