"""Genera el logo de VillazPlay como vector drawables.

La geometria se CALCULA (no se escribe a mano) para que la V del icono, la del
banner y las letras del logotipo salgan con las mismas proporciones.
"""
import os

import pathlib
# Ruta relativa al propio script: se ejecuta con
#   python3 android-standalone/scripts/logo.py
RES = str(pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res")

# Granate, a juego con el acento de la app (#C62B45). El degradado va de un
# granate vivo a uno muy oscuro: da profundidad sin ensuciar el blanco de la V.
# El ICONO va sobre degradado granate: es la version que se veia mejor en el
# launcher, porque un icono oscuro se pierde entre los demas y contra fondos
# oscuros. El BANNER de la tele va sobre casi negro, porque ahi el logotipo
# necesita el contraste (VIZ en blanco, PLAY en granate) y sobre granate el
# granate no se leeria.
ICONO_CLARO = "#D93650"
ICONO_OSCURO = "#5A0A18"
BANNER_CLARO = "#2B1116"    # casi negro con un punto de granate
BANNER_OSCURO = "#0A0507"
BLANCO = "#FFFFFF"
ROSA = "#F2A7B3"            # segundo brazo de la V en el icono
ROJO = "#E50914"            # segundo brazo de la V en el banner (sobre oscuro)
GRANATE = "#C41E3A"         # el "PLAY" del logotipo

def poly(points):
    """Poligono cerrado -> pathData."""
    d = "M%s,%s" % fmt2(points[0])
    for p in points[1:]:
        d += " L%s,%s" % fmt2(p)
    return d + " Z"

def fmt(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s if s else "0"

def fmt2(p):
    return (fmt(p[0]), fmt(p[1]))

def rect(x, y, w, h):
    return poly([(x, y), (x + w, y), (x + w, y + h), (x, y + h)])

# ---------------------------------------------------------------- la marca: V
def v_mark(cx, top, height, width, thick):
    """Dos brazos de una V con el corte superior HORIZONTAL (look geometrico).

    Devuelve (brazo_izq, brazo_der). El brazo derecho va en otro tono: es lo que
    hace que la V se lea tambien como el vertice de un play.
    """
    apex = (cx, top + height)
    left_out = (cx - width / 2.0, top)
    right_out = (cx + width / 2.0, top)
    # Caida vertical equivalente a desplazarse `thick` en horizontal por la misma
    # pendiente: asi el vertice interior queda donde toca y el grosor es uniforme.
    slope = height / (width / 2.0)
    drop = thick * slope
    inner = (cx, top + height - drop)
    izq = poly([left_out, apex, inner, (left_out[0] + thick, top)])
    der = poly([right_out, apex, inner, (right_out[0] - thick, top)])
    return izq, der

# ------------------------------------------------------- letras geometricas
def glyph(ch, x, y, w, h, s):
    """Letra de trazo recto. Solo hacen falta las de VILLAZPLAY."""
    cx = x + w / 2.0
    P = []
    if ch == "V":
        slope = h / (w / 2.0)
        drop = s * slope
        apex = (cx, y + h)
        inner = (cx, y + h - drop)
        P.append(poly([(x, y), apex, inner, (x + s, y)]))
        P.append(poly([(x + w, y), apex, inner, (x + w - s, y)]))
    elif ch == "I":
        P.append(rect(cx - s / 2.0, y, s, h))
    elif ch == "L":
        P.append(rect(x, y, s, h))
        P.append(rect(x, y + h - s, w * 0.85, s))
    elif ch == "A":
        P.append(poly([(x, y + h), (x + s, y + h), (cx + s / 2.0, y), (cx - s / 2.0, y)]))
        P.append(poly([(x + w, y + h), (x + w - s, y + h), (cx - s / 2.0, y), (cx + s / 2.0, y)]))
        P.append(rect(x + w * 0.22, y + h * 0.60, w * 0.56, s * 0.85))
    elif ch == "Z":
        P.append(rect(x, y, w, s))
        P.append(rect(x, y + h - s, w, s))
        P.append(poly([(x + w - s, y + s), (x + w, y + s),
                       (x + s, y + h - s), (x, y + h - s)]))
    elif ch == "C":
        # C "cuadrada", del mismo estilo geometrico que la P: barra vertical con
        # los remates arriba y abajo. Sin curvas, para que case con la V.
        P.append(rect(x, y, s, h))
        P.append(rect(x, y, w * 0.9, s))
        P.append(rect(x, y + h - s, w * 0.9, s))
    elif ch == "P":
        bowl = h * 0.58
        P.append(rect(x, y, s, h))
        P.append(rect(x, y, w * 0.82, s))
        P.append(rect(x, y + bowl - s, w * 0.82, s))
        P.append(rect(x + w * 0.82 - s, y, s, bowl))
    elif ch == "Y":
        mid = y + h * 0.52
        P.append(poly([(x, y), (x + s, y), (cx + s / 2.0, mid), (cx - s / 2.0, mid)]))
        P.append(poly([(x + w, y), (x + w - s, y), (cx - s / 2.0, mid), (cx + s / 2.0, mid)]))
        P.append(rect(cx - s / 2.0, mid, s, y + h - mid))
    else:
        raise ValueError("glifo no definido: " + ch)
    return P

def wordmark(text, x, y, cap, gap):
    """Logotipo en mayusculas. Devuelve (paths, ancho_total)."""
    w = cap * 0.65
    s = cap * 0.17
    out, cur = [], x
    for ch in text:
        out += glyph(ch, cur, y, w, cap, s)
        cur += w + gap
    return out, cur - gap - x

def gradient(x1, y1, x2, y2, c1, c2):
    return f'''        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="{fmt(x1)}" android:startY="{fmt(y1)}"
                android:endX="{fmt(x2)}" android:endY="{fmt(y2)}"
                android:startColor="{c1}" android:endColor="{c2}" />
        </aapt:attr>'''

def vector(w, h, vw, vh, body, aapt=False):
    ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    if aapt:
        ns += '\n    xmlns:aapt="http://schemas.android.com/aapt"'
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generado por scripts/logo.py: la geometria esta calculada, no dibujada a
     mano, para que la V del icono y la del banner coincidan. -->
<vector {ns}
    android:width="{w}dp"
    android:height="{h}dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
{body}
</vector>
'''

def path(d, color=None, grad=None):
    if grad:
        return f'    <path android:pathData="{d}">\n{grad}\n    </path>'
    return f'    <path\n        android:fillColor="{color}"\n        android:pathData="{d}" />'

# =====================================================================
#  1) Fondo del icono adaptativo (108x108, degradado en diagonal)
# =====================================================================
fondo = path(rect(0, 0, 108, 108), grad=gradient(0, 0, 108, 108, ICONO_CLARO, ICONO_OSCURO))
open(f"{RES}/drawable/ic_launcher_background.xml", "w").write(
    vector(108, 108, 108, 108, fondo, aapt=True))

# =====================================================================
#  2) Primer plano: la V en dos tonos, dentro de la zona segura
#     (el icono adaptativo recorta hasta un circulo de 66 de diametro
#     centrado en 54,54; todo lo dibujado queda dentro)
# =====================================================================
izq, der = v_mark(cx=54, top=34, height=42, width=48, thick=13)
fg = path(izq, BLANCO) + "\n" + path(der, ROSA)
open(f"{RES}/drawable/ic_launcher_foreground.xml", "w").write(
    vector(108, 108, 108, 108, fg))

# Version de un solo tono para los iconos tematicos de Android 13+
mono = path(izq, BLANCO) + "\n" + path(der, BLANCO)
open(f"{RES}/drawable/ic_launcher_mono.xml", "w").write(
    vector(108, 108, 108, 108, mono))

# =====================================================================
#  3) Icono heredado (API 24-25, sin iconos adaptativos): fondo + V juntos
# =====================================================================
legacy = path(rect(0, 0, 108, 108), grad=gradient(0, 0, 108, 108, ICONO_CLARO, ICONO_OSCURO)) + "\n" \
    + path(izq, BLANCO) + "\n" + path(der, ROSA)
open(f"{RES}/drawable/ic_launcher.xml", "w").write(
    vector(108, 108, 108, 108, legacy, aapt=True))

# =====================================================================
#  4) Banner de Android TV (320x180): marca + logotipo
# =====================================================================
BW, BH = 320.0, 180.0
MARGEN = 26.0                  # aire a los lados (las teles recortan bordes)
HUECO = 20.0                   # separacion entre la marca y el logotipo

mh = 52.0                      # alto de la V en el banner
mw = mh * (48.0 / 42.0)        # mismas proporciones que en el icono
my = (BH - mh) / 2.0

# El tamano de las letras se DESPEJA del hueco disponible, en vez de fijarlo a
# ojo, y despues el conjunto (marca + logotipo) se CENTRA: si no, al cambiar el
# numero de letras el bloque queda descolocado o se sale del banner.
TEXTO = "VIZPLAY"
CORTE = 3          # VIZ | PLAY: las tres primeras en blanco
def ancho_texto(cap):
    return cap * (len(TEXTO) * 0.65 + (len(TEXTO) - 1) * 0.22)

libre = BW - 2 * MARGEN - mw - HUECO
cap = min(libre / (len(TEXTO) * 0.65 + (len(TEXTO) - 1) * 0.22), 26.0)
total = mw + HUECO + ancho_texto(cap)
x0 = (BW - total) / 2.0                    # centrado horizontal del conjunto

mx = x0 + mw / 2.0
bizq, bder = v_mark(cx=mx, top=my, height=mh, width=mw, thick=13 * (mh / 42.0))

wx = x0 + mw + HUECO
wy = (BH - cap) / 2.0
letras, ancho = wordmark(TEXTO, x=wx, y=wy, cap=cap, gap=cap * 0.22)

cuerpo = [path(rect(0, 0, BW, BH), grad=gradient(0, 0, BW, BH, BANNER_CLARO, BANNER_OSCURO))]
cuerpo.append(path(bizq, BLANCO))
cuerpo.append(path(bder, ROJO))
# Cada letra se pinta segun a que mitad del logotipo pertenece. Hay que saber
# cuantos trazos ocupa cada glifo, porque una A son tres y una I uno solo.
i = 0
for k, ch in enumerate(TEXTO):
    trazos = len(glyph(ch, 0, 0, 10, 10, 2))
    color = BLANCO if k < CORTE else GRANATE
    for d in letras[i:i + trazos]:
        cuerpo.append(path(d, color))
    i += trazos
open(f"{RES}/drawable/tv_banner.xml", "w").write(
    vector(320, 180, BW, BH, "\n".join(cuerpo), aapt=True))

print("logotipo: ancho %.1f, termina en x=%.1f (margen derecho %.1f)"
      % (ancho, wx + ancho, BW - (wx + ancho)))
print("marca: x %.1f..%.1f, y %.1f..%.1f" % (x0, x0 + mw, my, my + mh))

# =====================================================================
#  5) Icono adaptativo
# =====================================================================
os.makedirs(f"{RES}/mipmap-anydpi-v26", exist_ok=True)
open(f"{RES}/mipmap-anydpi-v26/ic_launcher.xml", "w").write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <!-- Android 13+: icono tematico que se tinta con el fondo del sistema -->
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
''')
# Respaldo sin calificador: mipmap-anydpi-v26 solo casa con API>=26, y sin esto
# Android 7 y 8.0 se quedarian sin icono.
os.makedirs(f"{RES}/mipmap", exist_ok=True)
import shutil
shutil.copyfile(f"{RES}/drawable/ic_launcher.xml", f"{RES}/mipmap/ic_launcher.xml")
print("escritos:", sorted(os.listdir(f"{RES}/drawable")))
