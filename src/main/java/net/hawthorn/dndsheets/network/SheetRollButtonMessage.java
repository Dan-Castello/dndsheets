
package net.hawthorn.dndsheets.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.procedures.RollAnnouncerProcedure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

public class SheetRollButtonMessage {
	int category, index, subIndex, x, y, z;
	boolean isPrivate;

	public SheetRollButtonMessage(int category, int index, int subIndex, int x, int y, int z, boolean isPrivate) {
		this.category = category;
		this.index = index;
		this.subIndex = subIndex;
		this.x = x;
		this.y = y;
		this.z = z;
		this.isPrivate = isPrivate;
	}

	public SheetRollButtonMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readInt();
		this.index = buffer.readInt();
		this.subIndex = buffer.readInt();
		this.x = buffer.readInt();
		this.y = buffer.readInt();
		this.z = buffer.readInt();
		this.isPrivate = buffer.readBoolean();
	}

	public static void buffer(SheetRollButtonMessage message, FriendlyByteBuf buffer) {
		buffer.writeInt(message.category);
		buffer.writeInt(message.index);
		buffer.writeInt(message.subIndex);
		buffer.writeInt(message.x);
		buffer.writeInt(message.y);
		buffer.writeInt(message.z);
		buffer.writeBoolean(message.isPrivate);
	}

	public static void handler(SheetRollButtonMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () ->
			handle(context.getSender(), message.category, message.index, message.subIndex, message.x, message.y, message.z, message.isPrivate));
	}

	public static void handle(Player entity, int category, int index, int subIndex, int x, int y, int z, boolean isPrivate) {
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
			RollAnnouncerProcedure.execute(world, x, y, z, uuid, category, index, subIndex, entity, isPrivate);
		}
		catch(Exception e) {
			logger.log(org.apache.logging.log4j.Level.getLevel("severe"), e);
		}

	}
}
