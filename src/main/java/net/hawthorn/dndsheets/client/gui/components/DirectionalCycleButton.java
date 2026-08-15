package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

//Botón cíclico que avanza con clic izquierdo y retrocede con clic derecho. Antes de esto, todo botón
//cíclico del mod (dado de efecto de turno, hab. de ataque/daño, tipo de daño, ventaja, pacto...) solo
//avanzaba: pasarse una opción obligaba a recorrer TODA la lista de vuelta en vez de retroceder un paso.
//No hereda el onPress de Button (privado en la clase base) — guarda sus propios callbacks y sobrescribe
//mouseClicked entero, ya que AbstractWidget#onClick no recibe qué botón del mouse se usó.
public class DirectionalCycleButton extends Button {
	private final Runnable onNext;
	private final Runnable onPrevious;

	public DirectionalCycleButton(int x, int y, int width, int height, Component message, Runnable onNext, Runnable onPrevious) {
		super(x, y, width, height, message, b -> {}, DEFAULT_NARRATION);
		this.onNext = onNext;
		this.onPrevious = onPrevious;
	}

	@Override
	protected boolean isValidClickButton(int button) {
		return button == 0 || button == 1;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!this.active || !this.visible || !this.isValidClickButton(button) || !this.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		this.playDownSound(Minecraft.getInstance().getSoundManager());
		if (button == 1) onPrevious.run(); else onNext.run();
		return true;
	}
}
