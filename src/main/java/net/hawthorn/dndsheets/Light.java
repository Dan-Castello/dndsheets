package net.hawthorn.dndsheets;

/**
 * <p>Los tres niveles de iluminación de 5e —luz brillante, penumbra y oscuridad— leídos del nivel de luz
 * real del bloque donde está la criatura. La oscuridad es "muy oscurecida" en el manual: quien está dentro
 * queda <b>efectivamente cegado</b>, y eso ya significa algo en este mod porque {@link Condition#CEGADO}
 * existe con todas sus consecuencias desde la Fase 0.</p>
 *
 * <p>Es la misma jugada que {@link Cover}, en la otra mitad del entorno. Foundry vende iluminación dinámica
 * y Roll20 capas de niebla <em>para simular</em> qué se ve desde dónde; aquí la antorcha ya está encendida y
 * la cueva ya está oscura, con los números que el propio Minecraft calcula tick a tick. Lo único que faltaba
 * era que la mesa los leyera.</p>
 *
 * <p><b>Dónde caen los cortes.</b> Minecraft da un nivel de luz de 0 a 15 ya mezclado (bloques y cielo, con
 * la hora del día aplicada), así que los umbrales se eligen contra ese número y no contra una escala propia:
 * de 8 en adelante es luz brillante, de 4 a 7 penumbra y por debajo oscuridad. Que la noche a cielo abierto
 * caiga en penumbra no es casualidad ni ajuste fino — es exactamente lo que dice el SRD de la luz de la
 * luna, y sale solo de usar el número de vanilla en vez de inventar uno.</p>
 *
 * <p><b>Lo que NO se modela a propósito:</b> la penumbra da desventaja en las pruebas de Percepción que
 * dependen de la vista, y aquí no hace nada mecánico. Es la mitad menos visible de la regla y la que más
 * código pediría (habría que interceptar cada prueba), así que se queda fuera hasta que alguien la eche de
 * menos en una mesa de verdad.</p>
 */
public enum Light {
	BRIGHT, DIM, DARK;

	/** Desde este nivel de luz de Minecraft hay luz brillante. */
	static final int BRIGHT_FROM = 8;
	/** Desde este nivel hay penumbra; por debajo, oscuridad. */
	static final int DIM_FROM = 4;

	public static Light fromLightLevel(int lightLevel) {
		if (lightLevel >= BRIGHT_FROM) return BRIGHT;
		if (lightLevel >= DIM_FROM) return DIM;
		return DARK;
	}

	/**
	 * <p>Lo que ve quien tiene visión en la oscuridad: la oscuridad le cuenta como penumbra, y la penumbra
	 * sigue siendo penumbra. Es literalmente la frase del SRD, y por eso está escrita como una
	 * transformación de un nivel a otro en vez de como un {@code if} suelto dentro de la regla que ciega —
	 * así el rasgo entra una sola vez y cualquier regla que se escriba mañana sobre {@link Light} ya lo
	 * respeta.</p>
	 *
	 * <p>El <em>alcance</em> (60 pies, 120 los enanos de las profundidades) no entra aquí porque esta regla
	 * mira dónde estás tú, no a qué distancia ves: de pie en una cueva a oscuras, tener alcance 60 o 120 da
	 * el mismo resultado. Los pies se guardan igualmente en la ficha, que es donde un jugador los lee.</p>
	 */
	public Light withDarkvision(boolean hasDarkvision) {
		return hasDarkvision && this == DARK ? DIM : this;
	}

	/** En oscuridad se está "muy oscurecido": ciego a efectos de reglas. */
	public boolean blinds() {
		return this == DARK;
	}
}
