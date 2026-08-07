package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.CombatManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió a quién ajustar la hoja en PlayerPickerScreen, pide sus valores
//actuales (oro, espacios de conjuro) para abrir SheetAdjustScreen con datos reales en vez de en blanco.
public class SheetSummaryRequestMessage {
	String targetUuid;

	public SheetSummaryRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public SheetSummaryRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(SheetSummaryRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(SheetSummaryRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
			ServerPlayer dm = context.getSender();
			JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
			if (sheet == null) return;
			SheetLoader.validateSheet(sheet);

			String name = SheetLoader.characterNameOf(sheet, target);
			int gold = sheet.has("gold") ? sheet.get("gold").getAsInt() : 0;
			int slotsMax = sheet.get("spellSlotsMax").getAsInt();
			int slotsCurrent = sheet.get("spellSlotsCurrent").getAsInt();
			//PG/CA reales del jugador (no de la hoja, que solo los refleja) — ver AUDIT_UX.md, DM #1: antes
			//de esto no había forma de consultarlos en pleno combate sin pedirle al propio jugador que
			//abriera su hoja.
			int hp = Math.round(target.getHealth());
			int maxHp = Math.round(target.getMaxHealth());
			int ac = CombatManager.armorClassOf(target, sheet);

			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new SheetSummaryMessage(message.targetUuid, name, gold, slotsMax, slotsCurrent, hp, maxHp, ac));
		}));
		context.setPacketHandled(true);
	}
}
