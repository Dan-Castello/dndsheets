package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterActionChooseMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Menú que le abre al DM la Vara de DM al hacer clic derecho sobre un monstruo invocado por
 * {@code /dndmonsters spawn}: una lista de sus ataques y hechizos. Elegir uno resuelve el ataque/hechizo
 * contra el jugador más cercano al monstruo (ver {@link net.hawthorn.dndsheets.MonsterActionManager}).
 * Debajo de la lista, dos botones para editar los ataques de ESTA instancia sin comandos ni JSON — ver
 * {@link AddMonsterAttackScreen}/{@link ManageCustomAttacksScreen}.</p>
 */
public class MonsterActionScreen extends ListPickerScreen {
	private final int entityId;
	private final List<String> actionNames;
	private final List<String> customAttackNames;

	private MonsterActionScreen(int entityId, List<String> actionNames, List<String> customAttackNames) {
		super(Component.translatable("gui.dndsheets.monster_action.title"));
		this.entityId = entityId;
		this.actionNames = actionNames;
		this.customAttackNames = customAttackNames;
	}

	public static void open(int entityId, List<String> actionNames, List<String> customAttackNames) {
		Minecraft.getInstance().setScreen(new MonsterActionScreen(entityId, actionNames, customAttackNames));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < actionNames.size(); i++) {
			int actionIndex = i;
			//Elegir la acción abre el selector de jugador (mismo componente que ya usa el Panel de DM para
			//"a quién") antes de mandar el mensaje — antes esto siempre resolvía contra el jugador más
			//cercano al monstruo, sin dejar elegir a quién de verdad apuntar.
			addRow(Component.literal(actionNames.get(i)), b ->
				PlayerPickerScreen.open(Component.translatable("gui.dndsheets.monster_action.pick_target"), uuid ->
					DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterActionChooseMessage(entityId, actionIndex, uuid))
				)
			);
		}

		addRow(Component.translatable("gui.dndsheets.monster_action.add_attack"), b -> AddMonsterAttackScreen.open(entityId));
		addRow(Component.translatable("gui.dndsheets.monster_action.manage"), b -> ManageCustomAttacksScreen.open(entityId, customAttackNames));
		//Crear un monstruo reutilizable en JSON, sin escribirlo a mano: invocar un NPC genérico + darle
		//ataques con lo de arriba ya son 100% GUI — esto solo faltaba para guardar el resultado como
		//plantilla (ver MonsterTemplateSaveScreen/MonsterRegistry.toJson).
		addRow(Component.translatable("gui.dndsheets.monster_action.save_template"), b -> MonsterTemplateSaveScreen.open(entityId));
	}
}
