package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: formulario de AddTurnEffectScreen para el jugador elegido antes en
//PlayerPickerScreen (equivalente en GUI a /dndturns effect — ver AUDIT_UX.md, DM #4: antes de esto solo
//existía como comando tecleado a mano, sin GUI para duración).
public class TurnEffectApplyMessage {
	String targetUuid, name, dice;
	int turns;

	public TurnEffectApplyMessage(String targetUuid, String name, String dice, int turns) {
		this.targetUuid = targetUuid;
		this.name = name;
		this.dice = dice;
		this.turns = turns;
	}

	public TurnEffectApplyMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.name = buffer.readUtf();
		this.dice = buffer.readUtf();
		this.turns = buffer.readVarInt();
	}

	public static void buffer(TurnEffectApplyMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.name);
		buffer.writeUtf(message.dice);
		buffer.writeVarInt(message.turns);
	}

	public static void handler(TurnEffectApplyMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target == null) return;
			TurnManager.applyEffect(target, message.name, message.dice, Math.max(1, Math.min(20, message.turns)));
		});
		context.setPacketHandled(true);
	}
}
