package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DruidWildShapeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <p>Todo lo que la Forma Salvaje necesita que cruce el cable, en una sola clase con un enum en vez de
 * tres mensajes casi iguales (invariante 3): abrir el selector de bestia, elegir una, y contarle al resto
 * de clientes en qué se ha convertido alguien para que puedan dibujarlo.</p>
 *
 * <ul>
 *   <li>{@code OPEN_PICKER} — servidor → cliente. Abre la lista de bestias, con el bestiario ({@code id}
 *       + nombre + PG + CA) puesto por el servidor: el registro en memoria solo vive ahí, así que un
 *       cliente que sea un proceso aparte (cualquiera que no sea quien abrió el mundo) lo ve siempre
 *       vacío si el cliente intenta leerlo directo.</li>
 *   <li>{@code CHOOSE} — cliente → servidor. "Conviérteme en esta". Es el único que llega del cliente, y
 *       por eso es el único que valida: el servidor comprueba que exista y sea una bestia.</li>
 *   <li>{@code SHAPE} — servidor → <b>todos</b> los clientes. "Este jugador se dibuja ahora como esta
 *       entidad", o con {@code monsterId} vacío, "ha vuelto a la suya". Va a todos y no solo al interesado
 *       porque lo que cambia es cómo lo VEN los demás. Lleva la {@code baseEntityId} YA resuelta por el
 *       servidor (no el id del monstruo): el registro de monstruos solo vive ahí, y un cliente que sea un
 *       proceso aparte (un invitado por LAN) no podría resolverlo por su cuenta — ver
 *       {@code WildShapeWatcher.baseEntityIdOf}.</li>
 * </ul>
 */
public class WildShapeMessage {

	//Al final, nunca en medio: writeEnum viaja por ordinal (ver la invariante 2 de PROJECT_CONTEXT.md).
	public enum Kind { OPEN_PICKER, CHOOSE, SHAPE }

	final Kind kind;
	final UUID target;
	final String monsterId;
	//Solo van con datos en OPEN_PICKER; CHOOSE y SHAPE viajan con las cuatro vacías.
	final List<String> beastIds;
	final List<String> beastNames;
	final List<Integer> beastHps;
	final List<Integer> beastAcs;

	public WildShapeMessage(Kind kind, UUID target, String monsterId) {
		this(kind, target, monsterId, List.of(), List.of(), List.of(), List.of());
	}

	/** OPEN_PICKER con el bestiario ya resuelto en el servidor — ver {@code WildShapeWatcher.openPicker}. */
	public WildShapeMessage(UUID target, List<String> beastIds, List<String> beastNames, List<Integer> beastHps, List<Integer> beastAcs) {
		this(Kind.OPEN_PICKER, target, "", beastIds, beastNames, beastHps, beastAcs);
	}

	private WildShapeMessage(Kind kind, UUID target, String monsterId, List<String> beastIds, List<String> beastNames, List<Integer> beastHps, List<Integer> beastAcs) {
		this.kind = kind;
		this.target = target;
		this.monsterId = monsterId;
		this.beastIds = beastIds;
		this.beastNames = beastNames;
		this.beastHps = beastHps;
		this.beastAcs = beastAcs;
	}

	public WildShapeMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.target = buffer.readUUID();
		this.monsterId = buffer.readUtf();
		this.beastIds = buffer.readList(FriendlyByteBuf::readUtf);
		this.beastNames = buffer.readList(FriendlyByteBuf::readUtf);
		this.beastHps = buffer.readList(FriendlyByteBuf::readVarInt);
		this.beastAcs = buffer.readList(FriendlyByteBuf::readVarInt);
	}

	public static void buffer(WildShapeMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeUUID(message.target);
		buffer.writeUtf(message.monsterId);
		buffer.writeCollection(message.beastIds, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.beastNames, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.beastHps, FriendlyByteBuf::writeVarInt);
		buffer.writeCollection(message.beastAcs, FriendlyByteBuf::writeVarInt);
	}

	public static void handler(WildShapeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();

		switch (message.kind) {
			case OPEN_PICKER -> NetworkUtil.handleOnClient(context, () ->
				net.hawthorn.dndsheets.client.gui.WildShapeListScreen.open(message.beastIds, message.beastNames, message.beastHps, message.beastAcs));
			case SHAPE -> NetworkUtil.handleOnClient(context, () ->
				net.hawthorn.dndsheets.client.WildShapeRenderer.setShape(message.target, message.monsterId));
			//No pasa por handleOnServerAsDm: transformarse es cosa del propio jugador, no del DM. Lo que sí
			//se valida es la bestia, dentro de activate — el cliente puede mandar cualquier id.
			case CHOOSE -> NetworkUtil.handleOnServer(context, () -> {
				if (context.getSender() != null) {
					DruidWildShapeManager.activate(context.getSender(), message.monsterId);
				}
			});
		}
	}
}
