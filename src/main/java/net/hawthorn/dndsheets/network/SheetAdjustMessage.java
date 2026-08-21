package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botones de SheetAdjustScreen que ajustan UN campo de la hoja de otro
//jugador (equivalente en GUI a /dndsheet gold|setlevel|setslots|advantage|damagetype|pact). Reemplaza
//SheetGoldMessage, SheetLevelMessage, SheetSlotsMessage, SheetAdvantageMessage,
//SheetDamageAffinityMessage y SheetPactMessage, que eran 6 clases casi idénticas (mismo targetUuid +
//withDmTarget) salvo el payload y a qué SheetCommand.applyX delegaban — mismo patrón que ya usa
//ScreenActionMessage para acciones sin payload. BREAKING CHANGE de protocolo: el id de red de estos 6
//mensajes cambia (ver DndsheetsMod.registerNetworkMessages) — un cliente y un servidor de versiones
//distintas del mod ya no son compatibles entre sí para estas acciones.
public class SheetAdjustMessage {
	//CONDITION se añade AL FINAL, no en orden alfabético: writeEnum/readEnum viajan por ordinal, así que
	//insertarla en medio le cambiaría el número a todas las de después, exactamente el mismo fallo
	//silencioso que el orden de registro de mensajes (ver DndsheetsMod.registerNetworkMessages).
	public enum Field { GOLD, LEVEL, SLOTS, ADVANTAGE, DAMAGE_AFFINITY, PACT, CONDITION }

	final String targetUuid;
	final Field field;
	//No todo Field usa las 4: GOLD usa strA+intA, LEVEL usa intA, SLOTS usa intA+intB, ADVANTAGE/PACT
	//usan strA, DAMAGE_AFFINITY usa strA+strB. Las que sobran viajan vacías/0 y se ignoran en el handler.
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

	public static SheetAdjustMessage level(String targetUuid, int nivel) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.LEVEL);
		m.intA = nivel;
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

	public static SheetAdjustMessage pact(String targetUuid, String pacto) {
		SheetAdjustMessage m = new SheetAdjustMessage(targetUuid, Field.PACT);
		m.strA = pacto;
		return m;
	}

	/** {@code apply} false quita la condición en vez de ponerla — un solo mensaje para los dos sentidos. */
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
			//withDmTarget no le da al llamador una referencia al DM (solo al target), así que el aviso de
			//confirmación se manda acá aparte — sin esto, pulsar "Aplicar" en SheetAdjustScreen (cualquier
			//fila: oro, espacios de conjuro, ventaja, tipo de daño, pacto, nivel) no daba NINGUNA señal de
			//que había pasado algo, así que un cambio que sí funcionaba parecía no hacer nada.
			ServerPlayer dm = context.getSender();
			DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
				switch (message.field) {
					case GOLD -> SheetCommand.applyGold(target, message.strA, message.intA);
					//Mismo clamp [1,20] que aplicaba SheetLevelMessage.handler antes de delegar.
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
