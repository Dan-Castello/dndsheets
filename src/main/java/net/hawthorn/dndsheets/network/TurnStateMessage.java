package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.TurnHudState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> todos los clientes: estado actual de TurnManager, para el HUD del modo turnos (ver
//client.TurnHudState / client.TurnHudOverlay). TurnManager lo manda de nuevo cada vez que algo visible
//cambia; ningún cliente tiene que pedirlo.
public class TurnStateMessage {
	boolean active;
	int round;
	String currentName;
	int currentEntityId;
	boolean actionUsed;
	double originX, originY, originZ;

	public TurnStateMessage(boolean active, int round, String currentName, int currentEntityId, boolean actionUsed, double originX, double originY, double originZ) {
		this.active = active;
		this.round = round;
		this.currentName = currentName;
		this.currentEntityId = currentEntityId;
		this.actionUsed = actionUsed;
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
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
	}

	public static void handler(TurnStateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
			TurnHudState.update(message.active, message.round, message.currentName, message.currentEntityId,
				message.actionUsed, message.originX, message.originY, message.originZ)));
		context.setPacketHandled(true);
	}
}
