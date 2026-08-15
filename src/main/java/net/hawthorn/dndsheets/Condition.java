package net.hawthorn.dndsheets;

import java.util.Locale;

/**
 * <p>Las 14 condiciones de 5e. Hasta ahora el mod solo sabía expresar "efecto con nombre libre que hace
 * X dados de daño durante N turnos" ({@link TurnManager.StatusEffect}) — un temporizador de sangrado, no
 * una condición: nada del motor leía ese nombre para cambiar una tirada, así que "derribado" o
 * "paralizado" eran indistinguibles de "fuego" salvo por el texto del chat.</p>
 *
 * <p>Cada regla se escribe como un {@code switch} en el método que la aplica, no como un constructor de
 * siete booleanos posicionales: así cada línea de abajo se lee como la frase del manual que codifica, y
 * añadir una regla nueva no obliga a tocar las 14 constantes.</p>
 *
 * <p>Lo que NO se modela aquí a propósito: "hechizado" (no puede atacar a quien lo hechizó) y
 * "asustado" (solo mientras vea la fuente) dependen de <em>quién</em> es la fuente, no solo de tener la
 * condición. Se registran y se muestran, y asustado aplica su desventaja de ataque sin comprobar línea
 * de visión — la aproximación conservadora, y la que un DM puede corregir a mano.</p>
 */
public enum Condition {
	CEGADO, HECHIZADO, ENSORDECIDO, ASUSTADO, AGARRADO, INCAPACITADO, INVISIBLE,
	PARALIZADO, PETRIFICADO, ENVENENADO, DERRIBADO, APRESADO, ATURDIDO, INCONSCIENTE;

	/** Este combatiente ataca con desventaja. */
	public boolean selfAttackDisadvantage() {
		return switch (this) {
			case CEGADO, ASUSTADO, ENVENENADO, DERRIBADO, APRESADO -> true;
			default -> false;
		};
	}

	/** Este combatiente ataca con ventaja. */
	public boolean selfAttackAdvantage() {
		return this == INVISIBLE;
	}

	/**
	 * Quien le ataca lo hace con ventaja. Derribado queda fuera a propósito: solo da ventaja en cuerpo a
	 * cuerpo y da <em>desventaja</em> a distancia, así que lo resuelve {@link Combatant#advantageAgainst}
	 * con la distancia real en la mano.
	 */
	public boolean attackersAdvantage() {
		return switch (this) {
			case CEGADO, PARALIZADO, PETRIFICADO, APRESADO, ATURDIDO, INCONSCIENTE -> true;
			default -> false;
		};
	}

	/** Quien le ataca lo hace con desventaja. */
	public boolean attackersDisadvantage() {
		return this == INVISIBLE;
	}

	/** No puede realizar acciones ni reacciones. */
	public boolean preventsActions() {
		return switch (this) {
			case INCAPACITADO, PARALIZADO, PETRIFICADO, ATURDIDO, INCONSCIENTE -> true;
			default -> false;
		};
	}

	/** Velocidad 0: no puede moverse (ver MovementAnchorTracker). */
	public boolean preventsMovement() {
		return switch (this) {
			case AGARRADO, PARALIZADO, PETRIFICADO, APRESADO, INCONSCIENTE -> true;
			default -> false;
		};
	}

	/** Todo impacto en cuerpo a cuerpo (a 5 pies) contra él es crítico automático. */
	public boolean autoCritInMelee() {
		return this == PARALIZADO || this == INCONSCIENTE;
	}

	/** Falla automáticamente las salvaciones de Fuerza y Destreza. */
	public boolean autoFailsStrDexSaves() {
		return switch (this) {
			case PARALIZADO, PETRIFICADO, ATURDIDO, INCONSCIENTE -> true;
			default -> false;
		};
	}

	/** Resistencia a todo el daño (solo petrificado, en 5e). */
	public boolean resistsAllDamage() {
		return this == PETRIFICADO;
	}

	/** Nombre en minúsculas usado en JSON, comandos y chat. */
	public String label() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** {@code null} si el texto no nombra ninguna condición — un efecto libre ("fuego", "sangrado"). */
	public static Condition fromLabel(String label) {
		if (label == null) return null;
		for (Condition condition : values()) {
			if (condition.label().equalsIgnoreCase(label)) return condition;
		}
		return null;
	}
}
