# Genera la textura de cuero de los paneles (GuiStyle.panel):
#   src/main/resources/assets/dndsheets/textures/screens/panel_leather.png  (64x64, tileable)
#
# Es ruido rosa generado por FFT inversa: al construirse en el dominio de frecuencias con
# frecuencias enteras, el tile empalma consigo mismo por los cuatro lados SIN costuras, que es lo
# que un blur normal sobre ruido aleatorio no da. Dos capas: veteado ancho (el "cuero") y grano
# fino (el poro). El color medio es el antiguo FILL_COLOR de GuiStyle (0x1A140E) para que el
# contraste del texto encima no cambie respecto a lo ya probado.
#
# Semilla fija: regenerar produce EXACTAMENTE el mismo PNG (mismo criterio que sync_lang_variants:
# la herramienta es la fuente, el binario es derivado).
import numpy as np
from PIL import Image
from pathlib import Path

SIZE = 64
BASE = np.array([26.0, 20.0, 14.0])  # 0x1A140E, el cuero de GuiStyle.
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/dndsheets/textures/screens/panel_leather.png"


def tileable_noise(rng, exponent):
    spectrum = rng.normal(size=(SIZE, SIZE)) + 1j * rng.normal(size=(SIZE, SIZE))
    fx = np.fft.fftfreq(SIZE)[:, None]
    fy = np.fft.fftfreq(SIZE)[None, :]
    freq = np.sqrt(fx ** 2 + fy ** 2)
    freq[0, 0] = 1.0
    spectrum *= freq ** -exponent
    spectrum[0, 0] = 0.0
    noise = np.real(np.fft.ifft2(spectrum))
    return (noise - noise.mean()) / (noise.std() + 1e-9)


def main():
    rng = np.random.default_rng(20260824)
    mottle = tileable_noise(rng, 1.6)   # veteado ancho
    grain = tileable_noise(rng, 0.4)    # poro fino
    value = 1.0 + 0.11 * mottle + 0.045 * grain
    # El veteado calienta un poco las zonas claras (más rojo/ámbar) y enfría las oscuras: cuero,
    # no piedra gris. Se aplica como leve sesgo por canal, no como segundo ruido.
    warm = 1.0 + 0.035 * mottle
    rgb = np.stack([
        BASE[0] * value * warm,
        BASE[1] * value,
        BASE[2] * value / warm,
    ], axis=-1)
    image = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f"escrito {OUT} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
