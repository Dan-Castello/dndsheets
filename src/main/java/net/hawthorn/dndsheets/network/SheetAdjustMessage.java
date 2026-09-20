package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client (the DM) -> server: buttons in SheetAdjustScreen that adjust ONE field of another player's sheet
//(GUI equivalent of /dndsheet gold|setlevel|setslots|advantage|damagetype|pact). Replaces
//SheetGoldMessage, SheetLevelMessage, SheetSlotsMessage, SheetAdvantageMessage,
//SheetDamageAffinityMessage, and SheetPactMessage, which were 6 nearly identical classes (same
//targetUuid + withDmTarget) except for the payload and which SheetCommand.applyX they delegated to — the
//same pattern already used by ScreenActionMessage for payload-less actions. Protocol BREAKING CHANGE: the
//network id of these 6 messages changes (see DndsheetsMod.registerNetworkMessages) — a client and server
//running different versions of the mod are no longer compatible with each other for these actions.
public class SheetAdjustMessage {
	//CONDITION is appended AT THE END, not in alphabetical order: writeEnum/readEnum travel by ordinal, so
	//inserting it in the middle would shift the number of every one after it — exactly the same silent
	//failure mode as message registration order (see DndsheetsMod.registerNetworkMessages).
	public enum Field { GOLD, LEVEL, SLOTS, ADVANTAGE, DAMAGE_AFFINITY, PACT, CONDITION }

	final String targetUuid;
	final Field field;
	//Not every Field uses all 4: GOLD uses strA+intA, LEVEL uses intA, SLOTS uses intA+intB, ADVANTAGE/PACT
	//use strA, DAMAGE_AFFINITY uses strA+strB. The leftover ones travel empty/0 and are ignored in the handler.
	String strA = "", strB = "";
	int intA, intB;

	private SheetAdjustMessage(String targetUuid, Field field) {
		this.targetUuid = targetUuid;
		this.field = field;
	}

	public static SheetAdjustMessage gold(String targetUuid, String mode, int amount) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.GOLD);
		m.strA = mode;
		m.intA = amount;
		return m;
	}

	public static SheetAdjustMessage level(String targetUuid, int level) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.LEVEL);
		m.intA = level;
		return m;
	}

	public static SheetAdjustMessage slots(String targetUuid, int max, int current) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.SLOTS);
		m.intA = max;
		m.intB = current;
		return m;
	}

	public static SheetAdjustMessage advantage(String targetUuid, String label) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.ADVANTAGE);
		m.strA = label;
		return m;
	}

	public static SheetAdjustMessage damageAffinity(String targetUuid, String damageType, String affinity) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.DAMAGE_AFFINITY);
		m.strA = damageType;
		m.strB = affinity;
		return m;
	}

	public static SheetAdjustMessage pact(String targetUuid, String pact) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.PACT);
		m.strA = pact;
		return m;
	}

	/** {@code apply} false removes the condition instead of applying it — a single message for both directions. */
	public static SheetAdjustMessage condition(String targetUuid, String conditionLabel, boolean apply) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.CONDITION);
		m.strA = conditionLabel;
		m.intA = apply ? 1 : 0;
		return m;
	}

	public SheetAdjustMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.field = buffer.readEnum(Field.class);
		this.strA = buffer.readUtf();
		this.strB = buffer.readUtf();
		this.intA = buffer.readVarInt();
		this.intB = buffer.readVarInt();
	}

	public static void buffer(SheetAdjustMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeEnum(message.field);
		buffer.writeUtf(message.strA);
		buffer.writeUtf(message.strB);
		buffer.writeVarInt(message.intA);
		buffer.writeVarInt(message.intB);
	}

	public static void handler(SheetAdjustMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			//withDmTarget doesn't give the caller a reference to the DM (only to the target), so the
			//confirmation notice is sent here separately — without this, pressing "Apply" in
			//SheetAdjustScreen (any row: gold, spell slots, advantage, damage type, pact, level) gave NO
			//sign that anything had happened, so a change that did work looked like it did nothing.
			ServerPlayer dm = context.getSender();
			DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
				switch (message.field) {
					case GOLD -> SheetCommand.applyGold(target, message.strA, message.intA);
					//Same [1,20] clamp that SheetLevelMessage.handler applied before delegating.
					case LEVEL -> SheetCommand.applyLevel(target, Math.max(1, Math.min(20, message.intA)));
					case SLOTS -> SheetCommand.applySlots(target, message.intA, message.intB);
					case ADVANTAGE -> SheetCommand.applyAdvantage(target, message.strA);
					case DAMAGE_AFFINITY -> SheetCommand.applyDamageAffinity(target, message.strA, message.strB);
					case PACT -> SheetCommand.applyPact(target, message.strA);
					case CONDITION -> SheetCommand.applyCondition(target, message.strA, message.intA != 0);
				}
				if (dm != null) {
					dm.sendSystemMessage(Component.translatable("chat.dndsheets.character.sheet_updated", target.getName().getString()));
				}
			});
		});
	}
}
