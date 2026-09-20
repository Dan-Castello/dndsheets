package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.DeathSaveScreen;
import net.hawthorn.dndsheets.client.gui.RestChoiceScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Server -> client: opens/closes a modal screen with no payload of its own. Replaces DeathSaveOpenMessage,
//DeathSaveCloseMessage, and RestChoiceOpenMessage, which were 3 identical classes except for which screen
//they triggered.
public class ScreenActionMessage {
	//At the end, never in the middle: writeEnum travels by ordinal (invariant 2 in PROJECT_CONTEXT.md).
	public enum Action { DEATH_SAVE_OPEN, DEATH_SAVE_CLOSE, REST_CHOICE_OPEN, COMPENDIUM_OPEN, TURN_ACTION_OPEN, ABILITY_IMPROVEMENT_OPEN, NEW_CHARACTER_OPEN }

	final Action action;

	public ScreenActionMessage(Action action) {
		this.action = action;
	}

	public ScreenActionMessage(FriendlyByteBuf buffer) {
		this.action = buffer.readEnum(Action.class);
	}

	public static void buffer(ScreenActionMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.action);
	}

	public static void handler(ScreenActionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			switch (message.action) {
				case DEATH_SAVE_OPEN -> DeathSaveScreen.open();
				case DEATH_SAVE_CLOSE -> DeathSaveScreen.close();
				case REST_CHOICE_OPEN -> RestChoiceScreen.open();
				case COMPENDIUM_OPEN -> net.hawthorn.dndsheets.client.gui.CompendiumScreen.open();
				case TURN_ACTION_OPEN -> net.hawthorn.dndsheets.client.gui.TurnActionScreen.open();
				case ABILITY_IMPROVEMENT_OPEN -> net.hawthorn.dndsheets.client.gui.AbilityImprovementScreen.open();
				case NEW_CHARACTER_OPEN -> net.hawthorn.dndsheets.client.gui.NewCharacterScreen.open();
			}
		});
	}
}
