package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DruidWildShapeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * <p>Todo lo que la Forma Salvaje necesita que cruce el cable, en una sola clase con un enum en vez de
 * tres mensajes casi iguales (invariante 3): abrir el selector de bestia, elegir una, y contarle al resto
 * de clientes en qué se ha convertido alguien para que puedan dibujarlo.</p>
 *
 * <ul>
 *   <li>{@code OPEN_PICKER} — servidor → cliente. Abre la lista de bestias. Los campos van vacíos: la
 *       lista se arma en el cliente desde su propio registro (ver {@code WildShapeListScreen}).</li>
 *   <li>{@code CHOOSE} — cliente → servidor. "Conviérteme en esta". Es el único que llega del cliente, y
 *       por eso es el único que valida: el servidor comprueba que exista y sea una bestia.</li>
 *   <li>{@code SHAPE} — servidor → <b>todos</b> los clientes. "Este jugador es ahora esta bestia", o con
 *       el id vacío, "ha vuelto a la suya". Va a todos y no solo al interesado porque lo que cambia es
 *       cómo lo VEN los demás.</li>
 * </ul>
 */
public class WildShapeMessage {

	//Al final, nunca en medio: writeEnum viaja por ordinal (ver la invariante 2 de PROJECT_CONTEXT.md).
	public enum Kind { OPEN_PICKER, CHOOSE, SHAPE }

	final Kind kind;
	final UUID target;
	final String monsterId;

	public WildShapeMessage(Kind kind, UUID target, String monsterId) {
		this.kind = kind;
		this.target = target;
		this.monsterId = monsterId;
	}

	public WildShapeMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.target = buffer.readUUID();
		this.monsterId = buffer.readUtf();
	}

	public static void buffer(WildShapeMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeUUID(message.target);
		buffer.writeUtf(message.monsterId);
	}

	public static void handler(WildShapeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();

		switch (message.kind) {
			case OPEN_PICKER -> NetworkUtil.handleOnClient(context, () ->
				net.hawthorn.dndsheets.client.gui.WildShapeListScreen.open());
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
