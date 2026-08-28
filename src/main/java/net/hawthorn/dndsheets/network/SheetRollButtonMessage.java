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
 * <p>Cliente -&gt; servidor: pulsó un dado de la ficha. El servidor resuelve la tirada y la anuncia.</p>
 *
 * <p>Ya no lleva {@code x}/{@code y}/{@code z}. Servían para una sola cosa —dónde suena el dado, en
 * {@code RollAnnouncerProcedure.announce}— y llegaban desde unos campos de {@code CharacterSheetScreen}
 * que se copiaban del menú <b>al abrir la ficha</b>. O sea que el sonido salía de donde estabas cuando
 * abriste la hoja, no de donde estás al tirar: con la ficha abierta y caminando, el dado sonaba a tu
 * espalda. El servidor ya tiene al jugador que manda el paquete, así que la posición se lee de él y
 * además está fresca.</p>
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
