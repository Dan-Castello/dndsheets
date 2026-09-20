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
 * <p>Giving a stat block to a creature that already exists, from within the game: right-clicking any
 * creature without a sheet with the DM Rod opens the picker, and choosing one there applies it.</p>
 *
 * <p>This is what makes an NPC built in another mod playable. An NPC mod (EasyNPC and friends) is far
 * better than this one at <em>building</em> a character — skin, pose, dialogue, patrol or follow-the-party
 * goals — and this mod is the one that knows 5e. With this there's no need to choose: it's built there and
 * told here what it is. The command version is {@code /dndmonsters bind}.</p>
 *
 * <p><b>A single class for both directions</b>, instead of two nearly identical messages (invariant 3):
 * the server sends it with an empty {@code monsterId}, meaning "open the picker for this creature," and
 * the client sends it back with the chosen id, meaning "apply it to it." The two fields needed are the
 * same in both directions, so splitting them would have meant copying the buffer twice.</p>
 *
 * <p>{@code ids} only travels server → client: the bestiary to fill the picker, already resolved here.
 * The monster registry only lives on the server, and a DM who is a separate client (invited over LAN)
 * would always see it empty if the picker tried to read it directly.</p>
 */
public class MonsterBindMessage {
	final int entityId;
	final String monsterId;
	final List<String> ids;

	/** Client → server: "apply this stat block to this entity." */
	public MonsterBindMessage(int entityId, String monsterId) {
		this(entityId, monsterId, List.of());
	}

	/** Server → client: "open the picker for this entity, with this bestiary." */
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
			//Server -> client. If it arrived the other way around (modified client), handleOnClient does
			//nothing on the server and the packet goes nowhere, which is the correct behavior.
			NetworkUtil.handleOnClient(context, () ->
				net.hawthorn.dndsheets.client.gui.MonsterBindListScreen.open(message.entityId, message.ids));
			return;
		}

		//Client -> server. Through the DM gate: the client can send this packet without the menu being
		//open, and without the permission check anyone could turn their neighbor's cow into a tarrasque.
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (!(dm.level() instanceof ServerLevel level)) return;
			Entity target = level.getEntity(message.entityId);
			//A player has their own sheet and their own rules: giving them a monster stat block would override them.
			if (target == null || target instanceof Player) return;

			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(message.monsterId);
			if (block == null) return;

			MonsterRegistry.applyStatBlock(target, block);
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.bound",
				target.getName().getString(), message.monsterId).withStyle(ChatFormatting.GREEN));
		});
	}
}
