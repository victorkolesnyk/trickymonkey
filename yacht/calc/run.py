"""Ескізний розрахунок ВК-90: навантаження мас, гідростатика, остійність, креслення.

Запуск: python3 run.py  (з каталогу yacht/calc). Результати пишуться в ../docs і ../drawings.
"""
import csv
import os
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import ezdxf
import hull as H

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS, DRW = os.path.join(ROOT, "docs"), os.path.join(ROOT, "drawings")
os.makedirs(DOCS, exist_ok=True); os.makedirs(DRW, exist_ok=True)

DRAFT = 1.60            # повна осадка, м (проєктне рішення)
X_KEEL = 4.00           # поздовжнє положення кіля, м від транця
SAIL_AREA = 42.7        # м2, грот + стаксель (попередньо)

# ---- Навантаження мас: (стаття, маса кг, VCG м від ОЛ). Усі значення є припущеннями етапу 1.
LIGHT = [
    ("Обшивка корпусу (фанера, склотканина, епоксид)", 340, 0.65),
    ("Палуба, рубка, кокпіт", 190, 1.55),
    ("Перебірки, флори, стрингери, шпангоутні рами", 350, 0.75),
    ("Внутрішнє обладнання приміщень", 300, 0.75),
    ("Підкріплення під кіль, кільові флори", 100, 0.10),
    ("Плавник кіля (сталь)", 300, -0.45),
    ("Бульб кіля (свинець)", 1150, -1.00),
    ("Рангоут, такелаж, вітрила", 190, 6.00),
    ("Двигун із приводом, паливна система", 170, 0.35),
    ("Електрообладнання, акумулятори", 120, 0.60),
    ("Палубне обладнання, якірний пристрій", 200, 1.30),
    ("Суднові системи, камбуз, гальюн, цистерни", 120, 0.60),
    ("Стерновий пристрій", 60, 0.40),
    ("Шпаклювання, фарбування", 80, 0.70),
]
MARGIN = 0.08           # запас на масу статей, крім баласту
BALLAST = ("Плавник кіля (сталь)", "Бульб кіля (свинець)")

LOAD_MO = [("Екіпаж 2 особи", 150, 1.50), ("Обов'язкове спорядження", 60, 0.80)]
LOAD_LDC = [("Екіпаж 4 особи", 300, 1.50), ("Прісна вода", 150, 0.25), ("Паливо", 50, 0.30),
            ("Провізія, особисті речі", 250, 0.70), ("Рятувальний пліт", 35, 1.30)]


def summ(items):
    m = sum(i[1] for i in items)
    return m, sum(i[1] * i[2] for i in items) / m


nonb = [i for i in LIGHT if i[0] not in BALLAST]
margin = ("Запас маси 8 % (без баласту)", round(MARGIN * sum(i[1] for i in nonb)), 0.90)
LIGHT_ALL = LIGHT + [margin]
m_lc, kg_lc = summ(LIGHT_ALL)
m_mo, kg_mo = summ(LIGHT_ALL + LOAD_MO)
m_ldc, kg_ldc = summ(LIGHT_ALL + LOAD_LDC)
m_bal = sum(i[1] for i in LIGHT if i[0] in BALLAST)
m_dwl = round((m_mo + m_ldc) / 2, -1)
kg_dwl = (kg_mo + kg_ldc) / 2

APPS = [H.Appendage("бульб", 1150 / 11340.0, X_KEEL, -1.00),
        H.Appendage("плавник", 0.055, X_KEEL, -0.45),
        H.Appendage("стерно", 0.020, 0.60, 0.00)]

U = H.upright(m_dwl, APPS)
DWL = U["d"]
z_keel_bottom = DWL - DRAFT

ANG = np.arange(0, 181, 5)


def stab(mass, kg, apps=APPS):
    gz = H.gz_curve(mass, kg, apps, ANG)
    u = H.upright(mass, apps)
    gm = u["KB"] + u["BMt"] - kg
    i = int(np.argmax(gz))
    avs = None
    for k in range(i, len(ANG) - 1):
        if gz[k] > 0 >= gz[k + 1]:
            avs = ANG[k] + 5 * gz[k] / (gz[k] - gz[k + 1]); break
    pos = np.clip(gz, 0, None)
    kmax = int(np.searchsorted(ANG, avs)) if avs else len(ANG)
    xa = np.append(ANG[:kmax], avs) if avs else ANG
    ya = np.append(pos[:kmax], 0.0) if avs else pos
    area = np.trapezoid(ya, np.radians(xa))
    return dict(gz=gz, gm=gm, gzmax=gz[i], amax=ANG[i], avs=avs, area=area, u=u,
                gz30=float(np.interp(30, ANG, gz)), mass=mass, kg=kg)


S_MO, S_LDC = stab(m_mo, kg_mo), stab(m_ldc, kg_ldc)
S_KG = stab(m_mo, kg_mo + 0.10)           # чутливість: центр мас вищий на 0,10 м

# Альтернатива: два скулові кілі, осадка близько 1,15 м (центр баласту вище на 0,50 м)
alt = [(n, m, (v + 0.50 if n in BALLAST else v)) for n, m, v in LIGHT] + [margin]
m_alt, kg_alt = summ(alt + LOAD_MO)
APPS_ALT = [H.Appendage("кілі", 1150 / 11340.0 + 0.055, X_KEEL, -0.40), APPS[2]]
S_ALT = stab(m_alt, kg_alt, APPS_ALT)


# ---- Лінії теоретичного креслення
STN = np.linspace(0, H.LH, 11)              # шпангоути 0..10, шпація 0,9 м, від транця
WLS = [0.20, round(DWL, 3), 0.80, 1.10]
BTK = [0.50, 1.00]
XF = np.linspace(0, H.LH, 721)


def hb_at(x, z):
    h = H.half_section(x); best = None
    for (ya, za), (yb, zb) in zip(h[:-1], h[1:]):
        if za == zb:
            continue
        if min(za, zb) <= z <= max(za, zb):
            y = ya + (z - za) / (zb - za) * (yb - ya)
            best = y if best is None else max(best, y)
    return best


def z_at(x, y):
    h = H.half_section(x)
    for (ya, za), (yb, zb) in zip(h[:-1], h[1:]):
        if yb > ya and ya <= y <= yb:
            return za + (y - ya) / (yb - ya) * (zb - za)
    return None


def polyline(fn):
    pts = [(x, fn(x)) for x in XF]
    segs, cur = [], []
    for x, v in pts:
        if v is None or v < 1e-4:
            if len(cur) > 1:
                segs.append(cur)
            cur = []
        else:
            cur.append((x, v))
    if len(cur) > 1:
        segs.append(cur)
    return segs


keel_line = [(x, H.zk(x)) for x in XF]
curve_prof = [[(x, c.at(x)[1]) for x in XF if x <= c.xend] for c in H.CURVES]
curve_plan = [[(x, c.at(x)[0]) for x in XF if x <= c.xend] for c in H.CURVES]
wl_plan = {z: polyline(lambda x, z=z: hb_at(x, z)) for z in WLS}
btk_prof = {y: polyline(lambda x, y=y: z_at(x, y)) for y in BTK}

# ---- Таблиця ординат
with open(os.path.join(DRW, "VK90-001_ordynaty.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Шпангоут", "x від транця, мм", "Кіль z", "Вилиця 1 y", "Вилиця 1 z",
                "Вилиця 2 y", "Вилиця 2 z", "Борт y", "Борт z"])
    for i, x in enumerate(STN):
        h = H.half_section(x)
        w.writerow([i, round(x * 1000)] + [round(h[0][1] * 1000)] +
                   [round(v * 1000) for p in h[1:] for v in p])

# ---- Креслення PDF
fig = plt.figure(figsize=(33.1, 23.4))   # A1
fig.suptitle("ВК-90. Теоретичне креслення (ескізний проєкт, етап 1). Розміри в метрах. "
             "Шпація 0,9 м, шпангоути рахуються від транця", fontsize=20)
ax1 = fig.add_axes([0.04, 0.60, 0.60, 0.32]); ax2 = fig.add_axes([0.04, 0.22, 0.60, 0.32])
ax3 = fig.add_axes([0.68, 0.50, 0.30, 0.42]); ax4 = fig.add_axes([0.68, 0.06, 0.30, 0.36])
ax5 = fig.add_axes([0.04, 0.04, 0.60, 0.13]); ax5.axis("off")

ax1.set_title("Бік", fontsize=16)
ax1.plot(*zip(*keel_line), "k", lw=1.6)
for cp in curve_prof:
    ax1.plot(*zip(*cp), "k", lw=1.2)
ax1.plot([0, 0], [H.zk(0), H.SH.at(0)[1]], "k", lw=1.6)
ax1.axhline(DWL, color="b", lw=0.8); ax1.text(9.05, DWL, "КВЛ", color="b", fontsize=12)
for y, segs in btk_prof.items():
    for s in segs:
        ax1.plot(*zip(*s), "g--", lw=0.7)
for i, x in enumerate(STN):
    ax1.plot([x, x], [-1.3, 1.75], color="0.75", lw=0.5); ax1.text(x, 1.78, str(i), ha="center", fontsize=12)
# кіль і стерно (попередній контур)
kx = [X_KEEL - 0.55, X_KEEL + 0.55, X_KEEL + 0.35, X_KEEL - 0.30]
kz = [H.zk(X_KEEL - 0.55), H.zk(X_KEEL + 0.55), z_keel_bottom + 0.22, z_keel_bottom + 0.22]
ax1.fill(kx, kz, fc="0.85", ec="k", lw=1)
ax1.add_patch(matplotlib.patches.Ellipse((X_KEEL + 0.05, z_keel_bottom + 0.13), 1.7, 0.26, fc="0.6", ec="k"))
ax1.fill([0.35, 0.80, 0.70, 0.40], [H.zk(0.35), H.zk(0.80), DWL - 1.35, DWL - 1.35], fc="0.85", ec="k", lw=1)
ax1.set_aspect("equal"); ax1.set_xlim(-0.3, 9.5); ax1.set_ylim(-1.35, 1.95); ax1.grid(False)

ax2.set_title("Півширота", fontsize=16)
for cp in curve_plan:
    ax2.plot(*zip(*cp), "k", lw=1.2)
ax2.plot([0, 0], [0, H.SH.at(0)[0]], "k", lw=1.6); ax2.axhline(0, color="k", lw=0.8)
for z, segs in wl_plan.items():
    for s in segs:
        ax2.plot(*zip(*s), "b", lw=0.8 if abs(z - DWL) > 1e-6 else 1.4)
        ax2.text(s[len(s) // 3][0], s[len(s) // 3][1] + 0.01, f"ВЛ {z:.2f}", color="b", fontsize=9)
for i, x in enumerate(STN):
    ax2.plot([x, x], [0, 1.65], color="0.75", lw=0.5)
ax2.set_aspect("equal"); ax2.set_xlim(-0.3, 9.5); ax2.set_ylim(-0.1, 1.75)

ax3.set_title("Корпус (корма ліворуч, ніс праворуч)", fontsize=16)
for i, x in enumerate(STN):
    h = H.half_section(x); sgn = -1 if i <= 4 else 1
    ax3.plot([sgn * p[0] for p in h], [p[1] for p in h], "k", lw=1.1)
    ax3.text(sgn * (h[-1][0] + 0.03), h[-1][1] + 0.02, str(i), fontsize=11, ha="center")
ax3.axhline(DWL, color="b", lw=0.8); ax3.axvline(0, color="k", lw=0.6)
ax3.set_aspect("equal"); ax3.set_xlim(-1.75, 1.75); ax3.set_ylim(-0.1, 1.8)

ax4.set_title("Діаграма статичної остійності (плоска палуба, без рубки)", fontsize=14)
ax4.plot(ANG, S_MO["gz"], "k", label=f"мінімальне навантаження, {m_mo:.0f} кг")
ax4.plot(ANG, S_LDC["gz"], "b", label=f"повне навантаження, {m_ldc:.0f} кг")
ax4.plot(ANG, S_ALT["gz"], "r--", label="альтернатива: два скулові кілі")
ax4.axhline(0, color="k", lw=0.6); ax4.set_xlabel("Кут крену, градуси"); ax4.set_ylabel("Плече GZ, м")
ax4.legend(fontsize=11); ax4.grid(True, lw=0.3)

ax5.text(0, 0.9, f"Довжина корпусу {H.LH:.2f} м   Найбільша ширина {2*max(H.SH.at(x)[0] for x in XF):.2f} м   "
         f"Довжина по КВЛ {U['LWL']:.2f} м   Ширина по КВЛ {U['BWL']:.2f} м   Осадка {DRAFT:.2f} м   "
         f"Водотоннажність на КВЛ {m_dwl:.0f} кг", fontsize=14)
ax5.text(0, 0.45, "Документ ВК90-001, ревізія 0. Статус: ескіз для погодження, не для виробництва. "
         "Зелений пунктир: батокси 0,5 і 1,0 м. Сині лінії: ватерлінії.", fontsize=13)
fig.savefig(os.path.join(DRW, "VK90-001_teoretychne_kreslennia.pdf"))
fig.savefig(os.path.join(DRW, "VK90-001_teoretychne_kreslennia.png"), dpi=60)

# ---- Креслення DXF, міліметри, 1:1
doc = ezdxf.new("R2010", setup=True); doc.units = ezdxf.units.MM
msp = doc.modelspace()
for name, col in [("KORPUS", 7), ("VL", 5), ("BATOKS", 3), ("SHPANGOUT", 8), ("KIL", 1), ("TEXT", 7)]:
    doc.layers.add(name, color=col)
mm = lambda pts, dx=0, dy=0, sx=1: [(sx * p[0] * 1000 + dx, p[1] * 1000 + dy) for p in pts]
PY, PB = -3000, 11500                       # зсув півшироти вниз, корпусу праворуч
msp.add_lwpolyline(mm(keel_line), dxfattribs={"layer": "KORPUS"})
for cp in curve_prof:
    msp.add_lwpolyline(mm(cp), dxfattribs={"layer": "KORPUS"})
msp.add_line((0, H.zk(0) * 1000), (0, H.SH.at(0)[1] * 1000), dxfattribs={"layer": "KORPUS"})
msp.add_line((-300, DWL * 1000), (9300, DWL * 1000), dxfattribs={"layer": "VL"})
for segs in btk_prof.values():
    for s in segs:
        msp.add_lwpolyline(mm(s), dxfattribs={"layer": "BATOKS"})
msp.add_lwpolyline(mm(list(zip(kx, kz))), close=True, dxfattribs={"layer": "KIL"})
for i, x in enumerate(STN):
    msp.add_line((x * 1000, -1300), (x * 1000, 1750), dxfattribs={"layer": "SHPANGOUT"})
    msp.add_line((x * 1000, PY), (x * 1000, PY + 1650), dxfattribs={"layer": "SHPANGOUT"})
    msp.add_text(str(i), height=80, dxfattribs={"layer": "TEXT"}).set_placement((x * 1000, 1800))
    h = H.half_section(x); sgn = -1 if i <= 4 else 1
    msp.add_lwpolyline(mm(h, PB, 0, sgn), dxfattribs={"layer": "KORPUS"})
for cp in curve_plan:
    msp.add_lwpolyline(mm(cp, 0, PY), dxfattribs={"layer": "KORPUS"})
msp.add_line((0, PY), (9000, PY), dxfattribs={"layer": "KORPUS"})
for segs in wl_plan.values():
    for s in segs:
        msp.add_lwpolyline(mm(s, 0, PY), dxfattribs={"layer": "VL"})
msp.add_line((PB - 1700, DWL * 1000), (PB + 1700, DWL * 1000), dxfattribs={"layer": "VL"})
msp.add_line((PB, -100), (PB, 1800), dxfattribs={"layer": "SHPANGOUT"})
msp.add_text("VK90-001 rev.0  Teoretychne kreslennia. Eskiz, ne dlia vyrobnytstva. mm, 1:1",
             height=120, dxfattribs={"layer": "TEXT"}).set_placement((0, PY - 500))
doc.saveas(os.path.join(DRW, "VK90-001_teoretychne_kreslennia.dxf"))

# ---- Звіт
f2 = lambda v, n=2: f"{v:.{n}f}".replace(".", ",")
bmax = 2 * max(H.SH.at(x)[0] for x in XF)
vol = m_dwl / H.RHO
L = []
L += ["# ВК-90. Ескізний розрахунок, ревізія 0", "",
      "Файл згенеровано скриптом `calc/run.py`. Усі числа походять з моделі `calc/hull.py`, "
      "вручну не редагувати.", "",
      "## Головні розміри", "", "| Параметр | Значення |", "|---|---|",
      f"| Довжина корпусу | {f2(H.LH)} м |", f"| Найбільша ширина | {f2(bmax)} м |",
      f"| Довжина по КВЛ | {f2(U['LWL'])} м |", f"| Ширина по КВЛ | {f2(U['BWL'])} м |",
      f"| Осадка корпусом | {f2(U['Tc'])} м |", f"| Осадка повна | {f2(DRAFT)} м |",
      f"| Висота борту на міделі над КВЛ | {f2(H.SH.at(4.2)[1] - DWL)} м |",
      f"| Водотоннажність порожнем | {m_lc:.0f} кг |",
      f"| Водотоннажність на КВЛ (розрахункова) | {m_dwl:.0f} кг |",
      f"| Водотоннажність у повному навантаженні | {m_ldc:.0f} кг |",
      f"| Баласт | {m_bal:.0f} кг, {100*m_bal/m_dwl:.0f} % від розрахункової |",
      f"| Площа вітрил (грот і стаксель, попередньо) | {f2(SAIL_AREA, 0)} м2 |", "",
      "## Гідростатика на КВЛ", "", "| Параметр | Значення |", "|---|---|",
      f"| Об'ємна водотоннажність | {f2(vol)} м3 |",
      f"| Абсциса центра величини від транця | {f2(U['LCB'])} м "
      f"({f2(100*((U['xa']+U['xf'])/2-U['LCB'])/U['LWL'],1)} % LWL у корму від середини КВЛ) |",
      f"| Апліката центра величини KB | {f2(U['KB'])} м |",
      f"| Поперечний метацентричний радіус BM | {f2(U['BMt'])} м |",
      f"| Площа ватерлінії | {f2(U['Awp'])} м2 |",
      f"| Абсциса центра площі ватерлінії | {f2(U['LCF'])} м |",
      f"| Змочена поверхня корпусу без виступних частин | {f2(U['S'],1)} м2 |",
      f"| Призматичний коефіцієнт | {f2(U['Cp'],3)} |",
      f"| Коефіцієнт загальної повноти (корпус) | {f2(U['Cb'],3)} |",
      f"| Коефіцієнт повноти ватерлінії | {f2(U['Cwp'],3)} |",
      f"| Маса на 1 см осадки | {f2(U['TPC'],0)} кг |", "",
      "## Безрозмірні показники", "", "| Показник | Значення | Що це означає |", "|---|---|---|",
      f"| Відносна водотоннажність D/L | {f2((m_dwl/1016.05)/((U['LWL']/0.3048/100)**3),0)} | "
      "помірно важкий крейсер |",
      f"| Енергоозброєність SA/D | {f2(SAIL_AREA/vol**(2/3),1)} | крейсерський рівень |",
      f"| Відношення LWL/BWL | {f2(U['LWL']/U['BWL'])} | широкий корпус, великий житловий об'єм |", "",
      "## Навантаження мас порожнем", "", "| Стаття | Маса, кг | Апліката ЦМ від ОЛ, м |", "|---|---|---|"]
for n, m, v in LIGHT_ALL:
    L.append(f"| {n} | {m:.0f} | {f2(v)} |")
L += [f"| Разом порожнем | {m_lc:.0f} | {f2(kg_lc)} |", "",
      "Маси статей є припущеннями першого наближення, а не результатом зважування чи "
      "специфікації. Уточнення відбувається на етапах 2 і 3.", "",
      "## Остійність", "",
      "| Стан | Маса, кг | KG, м | GM, м | GZ при 30°, м | GZ max, м | Кут GZ max | "
      "Кут заходу діаграми | Площа під GZ, м·рад |", "|---|---|---|---|---|---|---|---|---|"]
for nm, s in [("Мінімальне навантаження", S_MO), ("Повне навантаження", S_LDC),
              ("Чутливість: KG вище на 0,10 м", S_KG), ("Альтернатива: два скулові кілі, осадка 1,15 м", S_ALT)]:
    L.append(f"| {nm} | {s['mass']:.0f} | {f2(s['kg'])} | {f2(s['gm'])} | {f2(s['gz30'])} | "
             f"{f2(s['gzmax'])} | {s['amax']:.0f}° | {f2(s['avs'],0)}° | {f2(s['area'])} |")
L += ["", "Таблиця плечей GZ, м:", "", "| Кут | " + " | ".join(f"{a}°" for a in ANG[::2]) + " |",
      "|---|" + "---|" * len(ANG[::2]),
      "| Мін. навантаження | " + " | ".join(f2(v) for v in S_MO["gz"][::2]) + " |",
      "| Повне навантаження | " + " | ".join(f2(v) for v in S_LDC["gz"][::2]) + " |", ""]
open(os.path.join(DOCS, "03_eskiznyi_rozrakhunok.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L))
