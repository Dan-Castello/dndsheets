package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.SheetAdjustScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: valores actuales de la hoja del objetivo elegido en PlayerPickerScreen, para abrir
//SheetAdjustScreen con datos reales (oro y espacios de conjuro solo viven en la hoja del servidor).
public class SheetSummaryMessage {
	String targetUuid, targetName;
	int gold, slotsMax, slotsCurrent, hp, maxHp, ac;

	public SheetSummaryMessage(String targetUuid, String targetName, int gold, int slotsMax, int slotsCurrent, int hp, int maxHp, int ac) {
		this.targetUuid = targetUuid;
		this.targetName = targetName;
		this.gold = gold;
		this.slotsMax = slotsMax;
		this.slotsCurrent = slotsCurrent;
		this.hp = hp;
		this.maxHp = maxHp;
		this.ac = ac;
	}

	public SheetSummaryMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.targetName = buffer.readUtf();
		this.gold = buffer.readVarInt();
		this.slotsMax = buffer.readVarInt();
		this.slotsCurrent = buffer.readVarInt();
		this.hp = buffer.readVarInt();
		this.maxHp = buffer.readVarInt();
		this.ac = buffer.readVarInt();
	}

	public static void buffer(SheetSummaryMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.targetName);
		buffer.writeVarInt(message.gold);
		buffer.writeVarInt(message.slotsMax);
		buffer.writeVarInt(message.slotsCurrent);
		buffer.writeVarInt(message.hp);
		buffer.writeVarInt(message.maxHp);
		buffer.writeVarInt(message.ac);
	}

	public static void handler(SheetSummaryMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () ->
			SheetAdjustScreen.open(message.targetUuid, message.targetName, message.gold, message.slotsMax, message.slotsCurrent, message.hp, message.maxHp, message.ac));
	}
}
