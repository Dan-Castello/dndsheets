package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.TurnHudState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Servidor -&gt; todos los clientes: estado actual de TurnManager, para el HUD del modo turnos (ver
 * {@code client.TurnHudState}/{@code client.TurnHudOverlay}). TurnManager lo manda de nuevo cada vez que
 * algo visible cambia; ningún cliente tiene que pedirlo.</p>
 *
 * <p>{@code roster} es la fila de iniciativa completa —a quién le toca, quién ya actuó, quién cayó,
 * qué condiciones lleva encima cada uno— información que en una mesa real cualquiera ve con solo mirar el
 * tablero. Antes el HUD solo sabía de quién era el turno actual; el resto del combate solo existía en el
 * chat, que es la causa más probable de "es demasiada información": todo en texto corrido, nada de un
 * vistazo.</p>
 */
public class TurnStateMessage {
	boolean active;
	int round;
	String currentName;
	int currentEntityId;
	boolean actionUsed;
	double originX, originY, originZ;
	List<RosterRow> roster;

	/**
	 * <p>Una fila del tablero. {@code conditions} son etiquetas ya resueltas a texto (ver
	 * {@code Condition#label}), no el enum: el cliente no tiene por qué saber de qué tipo de combatiente
	 * viene ni recalcular nada, solo mostrar lo que el servidor ya decidió que es cierto ahora.</p>
	 *
	 * <p>{@code currentHp}/{@code maxHp} valen 0/0 si el combatiente no se pudo leer (entidad descargada,
	 * fuera de las reglas): el cliente no pinta barra de vida cuando {@code maxHp} es 0. Van al FINAL del
	 * payload a propósito — invariante 2, los campos nuevos nunca se insertan en medio.</p>
	 */
	public record RosterRow(int entityId, String name, boolean isMonster, boolean defeated, boolean acted,
							 boolean reactionUsed, boolean bonusActionUsed, List<String> conditions,
							 int currentHp, int maxHp) {
	}

	public TurnStateMessage(boolean active, int round, String currentName, int currentEntityId, boolean actionUsed,
							 double originX, double originY, double originZ, List<RosterRow> roster) {
		this.active = active;
		this.round = round;
		this.currentName = currentName;
		this.currentEntityId = currentEntityId;
		this.actionUsed = actionUsed;
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.roster = roster;
	}

	public TurnStateMessage(FriendlyByteBuf buffer) {
		this.active = buffer.readBoolean();
		this.round = buffer.readVarInt();
		this.currentName = buffer.readUtf();
		this.currentEntityId = buffer.readVarInt();
		this.actionUsed = buffer.readBoolean();
		this.originX = buffer.readDouble();
		this.originY = buffer.readDouble();
		this.originZ = buffer.readDouble();
		this.roster = buffer.readList(buf -> new RosterRow(
			buf.readVarInt(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
			buf.readBoolean(), buf.readBoolean(), buf.readList(FriendlyByteBuf::readUtf),
			buf.readVarInt(), buf.readVarInt()));
	}

	public static void buffer(TurnStateMessage message, FriendlyByteBuf buffer) {
		buffer.writeBoolean(message.active);
		buffer.writeVarInt(message.round);
		buffer.writeUtf(message.currentName);
		buffer.writeVarInt(message.currentEntityId);
		buffer.writeBoolean(message.actionUsed);
		buffer.writeDouble(message.originX);
		buffer.writeDouble(message.originY);
		buffer.writeDouble(message.originZ);
		buffer.writeCollection(message.roster, (buf, row) -> {
			buf.writeVarInt(row.entityId());
			buf.writeUtf(row.name());
			buf.writeBoolean(row.isMonster());
			buf.writeBoolean(row.defeated());
			buf.writeBoolean(row.acted());
			buf.writeBoolean(row.reactionUsed());
			buf.writeBoolean(row.bonusActionUsed());
			buf.writeCollection(row.conditions(), FriendlyByteBuf::writeUtf);
			buf.writeVarInt(row.currentHp());
			buf.writeVarInt(row.maxHp());
		});
	}

	public static void handler(TurnStateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () ->
			TurnHudState.update(message.active, message.round, message.currentName, message.currentEntityId,
				message.actionUsed, message.originX, message.originY, message.originZ, message.roster));
	}
}
