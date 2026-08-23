package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Darle un bloque de estadísticas a una criatura que ya existe, desde el juego: clic derecho con la
 * Vara de DM sobre cualquier criatura sin ficha abre el selector, y elegir en él se la pega.</p>
 *
 * <p>Es lo que hace jugable a un NPC construido en otro mod. Un mod de NPC (EasyNPC y compañía) es mucho
 * mejor que este para <em>construir</em> un personaje —piel, pose, diálogos, objetivos de patrulla o de
 * seguir al grupo— y este es el que sabe de 5e. Con esto no hay que elegir: se construye allí y se le
 * dice aquí qué es. La versión de comando es {@code /dndmonsters bind}.</p>
 *
 * <p><b>Una sola clase para los dos sentidos</b>, en vez de dos mensajes casi iguales (invariante 3): el
 * servidor la manda con {@code monsterId} vacío, que significa "abre el selector para esta criatura", y
 * el cliente la devuelve con el id elegido, que significa "pégaselo". Los dos campos que hacen falta son
 * los mismos en ambas direcciones, así que separarlas habría sido copiar el buffer dos veces.</p>
 *
 * <p>{@code ids} solo viaja en el sentido servidor → cliente: el bestiario para llenar el selector, ya
 * resuelto aquí. El registro de monstruos solo vive en el servidor, y un DM que sea un cliente aparte
 * (invitado por LAN) lo vería siempre vacío si el selector intentara leerlo directo.</p>
 */
public class MonsterBindMessage {
	final int entityId;
	final String monsterId;
	final List<String> ids;

	/** Cliente → servidor: "pégale este bloque a esta entidad". */
	public MonsterBindMessage(int entityId, String monsterId) {
		this(entityId, monsterId, List.of());
	}

	/** Servidor → cliente: "abre el selector para esta entidad, con este bestiario". */
	public MonsterBindMessage(int entityId, List<String> ids) {
		this(entityId, "", ids);
	}

	private MonsterBindMessage(int entityId, String monsterId, List<String> ids) {
		this.entityId = entityId;
		this.monsterId = monsterId;
		this.ids = ids;
	}

	public MonsterBindMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readInt();
		this.monsterId = buffer.readUtf();
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(MonsterBindMessage message, FriendlyByteBuf buffer) {
		buffer.writeInt(message.entityId);
		buffer.writeUtf(message.monsterId);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
	}

	public static void handler(MonsterBindMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();

		if (message.monsterId.isEmpty()) {
			//Servidor -> cliente. Si llegara al revés (cliente modificado), handleOnClient no hace nada en
			//el servidor y el paquete se queda en nada, que es el comportamiento correcto.
			NetworkUtil.handleOnClient(context, () ->
				net.hawthorn.dndsheets.client.gui.MonsterBindListScreen.open(message.entityId, message.ids));
			return;
		}

		//Cliente -> servidor. Por la puerta de DM: el cliente puede mandar este paquete sin tener el menú
		//abierto, y sin el permiso cualquiera convertiría en tarrasca a la vaca del vecino.
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (!(dm.level() instanceof ServerLevel level)) return;
			Entity target = level.getEntity(message.entityId);
			//Un jugador tiene su propia hoja y sus propias reglas: darle un bloque de monstruo las pisaría.
			if (target == null || target instanceof Player) return;

			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(message.monsterId);
			if (block == null) return;

			MonsterRegistry.applyStatBlock(target, block);
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.bound",
				target.getName().getString(), message.monsterId).withStyle(ChatFormatting.GREEN));
		});
	}
}
