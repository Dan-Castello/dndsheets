package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.TurnHudState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Server -&gt; all clients: TurnManager's current state, for the turn-mode HUD (see
 * {@code client.TurnHudState}/{@code client.TurnHudOverlay}). TurnManager sends it again every time
 * something visible changes; no client has to request it.</p>
 *
 * <p>{@code roster} is the full initiative row — whose turn it is, who has already acted, who is
 * down, which conditions each one has — information that at a real table anyone can see just by
 * looking at the board. Previously the HUD only knew whose turn it currently was; the rest of the
 * combat only existed in chat, which is the most likely cause of "that's too much information": all
 * of it in running text, nothing at a glance.</p>
 */
public class TurnStateMessage {
	boolean active;
	int round;
	int currentEntityId;
	boolean actionUsed;
	double originX, originY, originZ;
	List<RosterRow> roster;

	/**
	 * <p>One row of the board. {@code conditions} are labels already resolved to text (see
	 * {@code Condition#label}), not the enum: the client has no need to know what type of combatant
	 * it came from or recompute anything, just display what the server has already decided is true
	 * now.</p>
	 *
	 * <p>{@code currentHp}/{@code maxHp} are 0/0 if the combatant couldn't be read (entity unloaded,
	 * outside the rules): the client doesn't draw an HP bar when {@code maxHp} is 0. They go at the
	 * END of the payload on purpose — invariant 2, new fields are never inserted in the middle.</p>
	 */
	public record RosterRow(int entityId, String name, boolean isMonster, boolean defeated, boolean acted,
							 boolean reactionUsed, boolean bonusActionUsed, List<String> conditions,
							 int currentHp, int maxHp) {
	}

	public TurnStateMessage(boolean active, int round, int currentEntityId, boolean actionUsed,
							 double originX, double originY, double originZ, List<RosterRow> roster) {
		this.active = active;
		this.round = round;
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
			TurnHudState.update(message.active, message.round, message.currentEntityId,
				message.actionUsed, message.originX, message.originY, message.originZ, message.roster));
	}
}
