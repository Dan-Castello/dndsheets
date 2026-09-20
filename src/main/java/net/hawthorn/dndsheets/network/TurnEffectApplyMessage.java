package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client (the DM) -> server: AddTurnEffectScreen form for the player chosen earlier in
//PlayerPickerScreen (GUI equivalent of /dndturns effect): before this it only
//existed as a hand-typed command, with no GUI for duration.
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
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid, target ->
			TurnManager.applyEffect(target, message.name, message.dice, Math.max(1, Math.min(20, message.turns)))
		));
	}
}
