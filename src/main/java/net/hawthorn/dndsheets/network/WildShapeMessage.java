package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DruidWildShapeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <p>Everything Wild Shape needs to cross the wire, in a single class with an enum instead of
 * three near-identical messages (invariant 3): opening the beast picker, choosing one, and telling
 * the rest of the clients what someone has turned into so they can render it.</p>
 *
 * <ul>
 *   <li>{@code OPEN_PICKER} — server → client. Opens the beast list, with the bestiary ({@code id}
 *       + name + HP + AC) filled in by the server: the in-memory registry only lives there, so a
 *       client that's a separate process (anyone other than whoever hosted the world) would always
 *       see it empty if the client tried to read it directly.</li>
 *   <li>{@code CHOOSE} — client → server. "Turn me into this one." It's the only one that arrives
 *       from the client, and that's why it's the only one that validates: the server checks that it
 *       exists and is a beast.</li>
 *   <li>{@code SHAPE} — server → <b>all</b> clients. "This player is now rendered as this entity",
 *       or with an empty {@code monsterId}, "they've reverted to their own." It goes to everyone, not
 *       just the player involved, because what changes is how OTHERS see them. It carries the
 *       {@code baseEntityId} ALREADY resolved by the server (not the monster's id): the monster
 *       registry only lives there, and a client that's a separate process (a LAN guest) couldn't
 *       resolve it on its own — see {@code WildShapeWatcher.baseEntityIdOf}.</li>
 * </ul>
 */
public class WildShapeMessage {

	//At the end, never in the middle: writeEnum travels by ordinal (see invariant 2 of PROJECT_CONTEXT.md).
	public enum Kind { OPEN_PICKER, CHOOSE, SHAPE }

	final Kind kind;
	final UUID target;
	final String monsterId;
	//Only carry data in OPEN_PICKER; CHOOSE and SHAPE travel with all four empty.
	final List<String> beastIds;
	final List<String> beastNames;
	final List<Integer> beastHps;
	final List<Integer> beastAcs;

	public WildShapeMessage(Kind kind, UUID target, String monsterId) {
		this(kind, target, monsterId, List.of(), List.of(), List.of(), List.of());
	}

	/** OPEN_PICKER with the bestiary already resolved on the server — see {@code WildShapeWatcher.openPicker}. */
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
			//Doesn't go through handleOnServerAsDm: transforming is up to the player themself, not the DM.
			//What IS validated is the beast, inside activate — the client can send any id.
			case CHOOSE -> NetworkUtil.handleOnServer(context, () -> {
				if (context.getSender() != null) {
					DruidWildShapeManager.activate(context.getSender(), message.monsterId);
				}
			});
		}
	}
}
