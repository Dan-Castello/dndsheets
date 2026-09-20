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

//Client (the DM) -> server: chose whose sheet to adjust in PlayerPickerScreen, requests their current
//values (gold, spell slots) to open SheetAdjustScreen with real data instead of blank.
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
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
			ServerPlayer dm = context.getSender();
			JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
			if (sheet == null) return;
			SheetLoader.validateSheet(sheet);

			String name = SheetLoader.characterNameOf(sheet, target);
			int gold = sheet.has("gold") ? sheet.get("gold").getAsInt() : 0;
			int slotsMax = sheet.get("spellSlotsMax").getAsInt();
			int slotsCurrent = sheet.get("spellSlotsCurrent").getAsInt();
			//Player's real HP/AC (not the sheet's, which only reflects them) —
			//previously there was no way to check these mid-combat without asking the player themself
			//to open their sheet.
			int hp = Math.round(target.getHealth());
			int maxHp = Math.round(target.getMaxHealth());
			int ac = CombatManager.armorClassOf(target, sheet);

			//Active conditions, so the DM opens the screen already seeing which ones apply instead of
			//going in blind — see Combatant and client.gui.ConditionListScreen.
			StringBuilder conditions = new StringBuilder();
			net.hawthorn.dndsheets.Combatant combatant = net.hawthorn.dndsheets.Combatant.of(target);
			if (combatant != null) {
				for (net.hawthorn.dndsheets.Condition condition : combatant.conditions()) {
					if (conditions.length() > 0) conditions.append(',');
					conditions.append(condition.label());
				}
			}

			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new SheetSummaryMessage(message.targetUuid, name, gold, slotsMax, slotsCurrent, hp, maxHp, ac, conditions.toString()));
		}));
	}
}
