package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.procedures.RollAnnouncerProcedure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * <p>Client -&gt; server: pressed a die on the sheet. The server resolves the roll and announces it.</p>
 *
 * <p>No longer carries {@code x}/{@code y}/{@code z}. They served a single purpose — where the die sound
 * plays, in {@code RollAnnouncerProcedure.announce} — and came from fields on {@code CharacterSheetScreen}
 * that were copied from the menu <b>when the sheet was opened</b>. In other words, the sound came from
 * wherever you were when you opened the sheet, not from where you are when you roll: with the sheet open
 * and walking, the die would sound behind you. The server already has the player who sent the packet, so
 * the position is read from them, and it's always fresh.</p>
 */
public class SheetRollButtonMessage {
	int category, index, subIndex;
	boolean isPrivate;

	public SheetRollButtonMessage(int category, int index, int subIndex, boolean isPrivate) {
		this.category = category;
		this.index = index;
		this.subIndex = subIndex;
		this.isPrivate = isPrivate;
	}

	public SheetRollButtonMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readInt();
		this.index = buffer.readInt();
		this.subIndex = buffer.readInt();
		this.isPrivate = buffer.readBoolean();
	}

	public static void buffer(SheetRollButtonMessage message, FriendlyByteBuf buffer) {
		buffer.writeInt(message.category);
		buffer.writeInt(message.index);
		buffer.writeInt(message.subIndex);
		buffer.writeBoolean(message.isPrivate);
	}

	public static void handler(SheetRollButtonMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () ->
			handle(context.getSender(), message.category, message.index, message.subIndex, message.isPrivate));
	}

	public static void handle(Player entity, int category, int index, int subIndex, boolean isPrivate) {
		if (entity == null) return;
		Level world = entity.level();
		String uuid = entity.getStringUUID();
		// security measure to prevent arbitrary chunk generation
		Logger logger = LogManager.getLogger(DndsheetsMod.MODID);
		if (!world.hasChunkAt(entity.blockPosition())) {
			logger.log(org.apache.logging.log4j.Level.getLevel("info"), "Couldn't make a roll, the player's coordinates are somewhere without a chunk.");
			return;
		}
		if (SheetLoader.getServerSheet(uuid) == null) {
			logger.log(org.apache.logging.log4j.Level.getLevel("info"), "Couldn't make a roll, unable to find player's sheet on the server.");
			return;
		}
		try {
			RollAnnouncerProcedure.execute(world, entity.getX(), entity.getY(), entity.getZ(), uuid, category, index, subIndex, entity, isPrivate);
		}
		catch(Exception e) {
			logger.log(org.apache.logging.log4j.Level.getLevel("severe"), e);
		}

	}
}
