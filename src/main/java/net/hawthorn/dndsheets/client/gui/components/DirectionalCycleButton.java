package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

//Cyclic button that advances with left click and goes back with right click. Before this, every cyclic
//button in the mod (turn effect die, attack/damage ability, damage type, advantage, pact...) only
//advanced: overshooting an option meant cycling through the ENTIRE list again instead of stepping back.
//Inherits from TomeButton and not Button so it renders like the rest of the mod: as a bare Button it
//came out stone-gray on the leather panel, which is exactly what the redesign was meant to remove.
//Doesn't inherit Button's onPress (private in the base class) — it keeps its own callbacks and overrides
//mouseClicked entirely, since AbstractWidget#onClick doesn't receive which mouse button was used.
public class DirectionalCycleButton extends TomeButton {
	private final Runnable onNext;
	private final Runnable onPrevious;

	public DirectionalCycleButton(int x, int y, int width, int height, Component message, Runnable onNext, Runnable onPrevious) {
		super(x, y, width, height, message, b -> {});
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
