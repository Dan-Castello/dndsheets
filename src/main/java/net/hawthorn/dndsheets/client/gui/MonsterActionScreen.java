package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.network.MonsterActionChooseMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Menú que le abre al DM la Vara de DM al hacer clic derecho sobre un monstruo invocado por
 * {@code /dndmonsters spawn}: una lista de sus ataques y hechizos. Elegir uno resuelve el ataque/hechizo
 * contra el jugador más cercano al monstruo (ver {@link net.hawthorn.dndsheets.MonsterActionManager}).
 * Debajo de la lista, dos botones para editar los ataques de ESTA instancia sin comandos ni JSON — ver
 * {@link AddMonsterAttackScreen}/{@link ManageCustomAttacksScreen}.</p>
 */
public class MonsterActionScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private final int entityId;
	private final List<String> actionNames;
	private final List<String> customAttackNames;
	private ButtonListWidget list;

	private MonsterActionScreen(int entityId, List<String> actionNames, List<String> customAttackNames) {
		super(Component.literal("Acciones del monstruo"));
		this.entityId = entityId;
		this.actionNames = actionNames;
		this.customAttackNames = customAttackNames;
	}

	public static void open(int entityId, List<String> actionNames, List<String> customAttackNames) {
		Minecraft.getInstance().setScreen(new MonsterActionScreen(entityId, actionNames, customAttackNames));
	}

	@Override
	protected void init() {
		//Lista con scroll: un monstruo con muchos ataques/hechizos (más los dos botones de editar, que
		//viajan con la misma lista) empujaba filas fuera de pantalla con el cálculo viejo, sin forma de
		//alcanzarlas (ver AUDIT_UX.md).
		list = new ButtonListWidget((this.width - BUTTON_WIDTH) / 2, 30, BUTTON_WIDTH, this.height - 44, BUTTON_HEIGHT + SPACING);

		for (int i = 0; i < actionNames.size(); i++) {
			int actionIndex = i;
			Button button = Button.builder(Component.literal(actionNames.get(i)), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterActionChooseMessage(entityId, actionIndex));
				this.onClose();
			}).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
			this.addWidget(button);
			list.addRow(button);
		}

		Button addAttack = Button.builder(Component.literal("+ Añadir ataque"), b ->
			AddMonsterAttackScreen.open(entityId)
		).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		this.addWidget(addAttack);
		list.addRow(addAttack);

		Button manageAttacks = Button.builder(Component.literal("Gestionar ataques personalizados"), b ->
			ManageCustomAttacksScreen.open(entityId, customAttackNames)
		).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		this.addWidget(manageAttacks);
		list.addRow(manageAttacks);

		this.addRenderableWidget(list);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	//Ver PresetScreen.mouseScrolled: sin esto, el scroll solo funciona pasando el mouse por huecos sin
	//botón, se detiene en cuanto queda sobre una fila.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return list.mouseScrolled(mouseX, mouseY, delta) || super.mouseScrolled(mouseX, mouseY, delta);
	}
}
