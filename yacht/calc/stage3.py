"""Етап 3 ВК-90, роботи без стандартів ISO: остійність з рубкою і вільним диферентом,
навантаження на кріплення кіля і на ванти від перших принципів.

Запуск: python3 stage3.py (з каталогу yacht/calc). Спершу виконує етапи 1 і 2.
"""
import os
import numpy as np
from scipy.optimize import root
import hull as H
import run as R
import stage2 as S

f2 = R.f2
G = 9.81
XREF = 4.0
ROOF, H_AFT, H_FWD, HB = S.ROOF, S.ROOF_H_AFT, S.ROOF_H_FWD, S.ROOF_HB


def section_roof(x):
    h = H.half_section(x)
    if not (ROOF[0] <= x <= ROOF[1]):
        return H.full_section(x)
    ys, zs = h[-1]
    right = h + [(min(HB, ys - 0.25), zs), (min(HB, ys - 0.25), S.roof_z(x))]
    return np.array(right + [(-y, z) for (y, z) in reversed(right[1:])])


SEC = [section_roof(x) for x in H.XS]
H.SECTIONS = SEC                                  # для розрахунку без диферента


def state(phi, d0, t, apps):
    """Об'єм і центр величини при крені phi, зануренні d0 на x = XREF і нахилі ватерлінії t."""
    c = np.cos(phi)
    A = np.zeros(len(H.XS)); my = np.zeros(len(H.XS)); mz = np.zeros(len(H.XS))
    for i, sec in enumerate(SEC):
        p = H._clip(sec, phi, d0 + t * (H.XS[i] - XREF))
        if p is None:
            continue
        a, cy, cz = H._area_centroid(p)
        A[i], my[i], mz[i] = a, a * cy, a * cz
    V = np.trapezoid(A, H.XS); mx = np.trapezoid(A * H.XS, H.XS)
    my, mz = np.trapezoid(my, H.XS), np.trapezoid(mz, H.XS)
    for ap in apps:
        if ap.z * c - (d0 + t * (ap.x - XREF)) <= 0:
            V += ap.vol; mx += ap.vol * ap.x; mz += ap.vol * ap.z
    return V, mx / V, my / V, mz / V


def gz_free(mass, kg, xg, apps=R.APPS):
    v0 = mass / H.RHO
    out, trims = [], []
    guess = np.array([H.upright(mass, apps)["d"], 0.0])
    for a in R.ANG:
        phi = np.radians(a); s, c = np.sin(phi), np.cos(phi)

        def F(u):
            V, bx, by, bz = state(phi, u[0], u[1], apps)
            return [(V - v0) / v0, (bx - xg) + u[1] * ((bz * c - by * s) - kg * c)]

        sol = root(F, guess, method="hybr")
        if sol.success:
            guess = sol.x
            V, bx, by, bz = state(phi, *sol.x, apps)
            out.append(by * c + (bz - kg) * s); trims.append(np.degrees(np.arctan(sol.x[1])))
        else:
            out.append(np.nan); trims.append(np.nan)
    return np.array(out), np.array(trims)


def summary(gz):
    i = int(np.nanargmax(gz)); avs = None
    for k in range(i, len(R.ANG) - 1):
        if gz[k] > 0 >= gz[k + 1]:
            avs = R.ANG[k] + 5 * gz[k] / (gz[k] - gz[k + 1]); break
    return gz[i], R.ANG[i], avs


cond = {c["name"]: c for c in S.COND}
ROWS = []
for nm, mass, kg in [("Мінімальне навантаження", R.m_mo, R.kg_mo), ("Повне навантаження", R.m_ldc, R.kg_ldc)]:
    fixed = R.stab(mass, kg)                      # з рубкою, без диферента
    gz, tr = gz_free(mass, kg, cond[nm]["lcg"])
    gm, am, avs = summary(gz)
    ROWS.append(dict(name=nm, mass=mass, kg=kg, fixed=fixed, gz=gz, tr=tr, gzmax=gm, amax=am, avs=avs,
                     bad=int(np.isnan(gz).sum())))
base = {"Мінімальне навантаження": R.S_MO, "Повне навантаження": R.S_LDC}

L = ["# ВК-90. Остійність з рубкою і вільним диферентом, ревізія 1", "",
     "Файл згенеровано скриптом `calc/stage3.py`, вручну не редагувати.", "",
     "Рубка врахована як водонепроникний об'єм. Диферент на кожному куті крену знайдено з умови рівноваги. "
     "Кокпіт і кут заливання не враховано. Плоска палуба без рубки показана для порівняння. "
     "Базові значення без рубки перераховано для обводів ревізії 2.", "",
     "| Стан | Маса, кг | KG, м | Кут заходу: без рубки | з рубкою, без диферента | з рубкою, з диферентом | "
     "GZ max з диферентом, м | Кут GZ max | Диферент при 90°, градуси |", "|---|---|---|---|---|---|---|---|---|"]
for r in ROWS:
    L.append(f"| {r['name']} | {r['mass']:.0f} | {f2(r['kg'])} | {f2(base[r['name']]['avs'],0)}° | "
             f"{f2(r['fixed']['avs'],0)}° | {f2(r['avs'],0)}° | {f2(r['gzmax'])} | {r['amax']:.0f}° | "
             f"{f2(r['tr'][18],1)} |")
L += ["", "Додатний диферент означає занурення носа. Точок без збіжності розв'язку: "
      + ", ".join(str(r["bad"]) for r in ROWS) + ".", "",
      "Плечі GZ з рубкою і диферентом, м:", "", "| Кут | " + " | ".join(f"{a}°" for a in R.ANG[::2]) + " |",
      "|---|" + "---|" * len(R.ANG[::2])]
for r in ROWS:
    L.append(f"| {r['name']} | " + " | ".join(f2(abs(v) if abs(v) < 0.005 else v) for v in r["gz"][::2]) + " |")
open(os.path.join(R.DOCS, "07_ostiinist_z_rubkoiu.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L[6:11]))

# ======================= Навантаження на кіль і ванти =======================
M_FIN, M_BULB = 300.0, 1150.0
Z_ROOT = H.zk(R.X_KEEL)
A_FIN, A_BULB = Z_ROOT - (-0.45), Z_ROOT - (-1.00)          # плечі ЦМ від кореня кіля
SPAN = Z_ROOT - R.z_keel_bottom
N_PAIR, D_BOLT, AS_BOLT, RP_BOLT = 5, 20, 245.0, 450.0       # 5 пар M20, A4-70: As мм2, межа плинності МПа
ROW_GAP, FL_W, PITCH = 0.22, 0.30, 0.22                      # відстань між рядами, ширина і крок болтів, м
SKIN_T, SKIN_W, FOIL_T, RE_STEEL = 0.008, 0.80, 0.11, 235.0  # обшивка плавника 8 мм, S235

# 1. Яхта лежить на борту (крен 90°): вага кіля згинає корінь упоперек
M1 = G * (M_FIN * A_FIN + M_BULB * A_BULB)
lever1 = ROW_GAP + (FL_W - ROW_GAP) / 2                      # від ряду болтів до протилежної кромки фланця
T1 = M1 / lever1 / N_PAIR
I_fin = 2 * SKIN_W * SKIN_T * (FOIL_T / 2 - SKIN_T / 2) ** 2
sig_fin1 = M1 / (I_fin / (FOIL_T / 2)) / 1e6

# 2. Удар кілем об ґрунт на ходу: зупинка маси яхти на заданому шляху
V_HIT = 6 * 0.5144


def grounding(stop):
    F = R.m_ldc * V_HIT ** 2 / (2 * stop)
    M = F * SPAN
    r = np.array([PITCH / 2 + PITCH * k for k in range(N_PAIR)])   # від кормової кромки фланця
    T = M * r.max() / (2 * (r ** 2).sum())
    return F, M, T


GR = {s: grounding(s) for s in (0.30, 0.15)}

# 3. Ванти: найбільший відновлювальний момент тримає навітряна ванта
RM_MAX = R.m_ldc * G * max(r["gzmax"] for r in ROWS)
B_CHAIN = H.SH.at(S.X_MAST - 0.3)[0] - 0.05
T_SHROUD = RM_MAX / B_CHAIN

# Розрахункові випадки ISO 12215-9:2012 за формулами (1), (3), (4), (8), (9), (11) з доповіді Lyons et al., 2024
M_K = M_FIN + M_BULB
F1 = G * M_K                                   # випадок 1: кіль при крені 90°
F3 = G * (R.m_ldc - M_K)                       # випадок 3: вертикальний удар знизу
F4 = 1.2 * G * (R.m_ldc - M_K)                 # випадок 4: поздовжній удар
r_b = np.array([PITCH / 2 + PITCH * k for k in range(N_PAIR)])
M4 = F4 * SPAN                                 # припущення: сила прикладена до низу кіля
T4 = M4 * r_b.max() / (2 * (r_b ** 2).sum())
LAM = (0.17, 0.375)                            # пропонована поперечна складова удару, ще не норма
F4Y = [l * F4 for l in LAM]
T4Y = [f * SPAN / lever1 / N_PAIR for f in F4Y]
F_PS = 0.7 * RP_BOLT * AS_BOLT                 # попередній затяг болта, формула (11)
T_ALL = max(T1, T4, max(T4Y))
kn = lambda v, n=1: f2(v / 1000, n)
row = lambda T: f"{kn(T)} | {f2(T/AS_BOLT,0)} | {f2(RP_BOLT/(T/AS_BOLT),1)} | {f2(100*T/F_PS,0)} %"

K = ["# ВК-90. Навантаження на кіль і ванти, ревізія 1", "",
     "Файл згенеровано скриптом `calc/stage3.py`, вручну не редагувати.", "",
     "Сили розрахункових випадків узято з формул ISO 12215-9:2012, наведених у доповіді Lyons, Bird, Hinterhoeller, "
     "Hoffmeister, Souppez «Regulatory Developments in Structural Keel Design: A Revised ISO 12215-9» "
     "(8th High Performance Yacht Design Conference, Окленд, 21-22.03.2024). Доповідь не містить допустимих напружень, "
     "коефіцієнтів запасу, точок прикладання сил і методики розрахунку флорів, тому це не розрахунок за стандартом, "
     "а перевірка болтів на його навантаження. Прийнята конструкція кріплення є припущенням.", "",
     "## Прийнята конструкція", "",
     f"Сталевий зварний плавник з обшивкою {SKIN_T*1000:.0f} мм (S235), товщина профілю {FOIL_T*1000:.0f} мм, "
     f"фланець завширшки {FL_W*1000:.0f} мм. Кріплення: {N_PAIR} пар болтів M{D_BOLT} класу A4-70 "
     f"у два ряди через {ROW_GAP*1000:.0f} мм, крок {PITCH*1000:.0f} мм. Маса кіля {M_K:.0f} кг, "
     f"маса яхти в повному навантаженні {R.m_ldc:.0f} кг, розмах кіля від кореня до низу {f2(SPAN)} м.", "",
     "## Сили розрахункових випадків", "", "| Випадок ISO 12215-9:2012 | Формула | Сила, кН |", "|---|---|---|",
     f"| 1. Фіксований кіль при крені 90° | F1 = g·mK | {kn(F1)} |",
     f"| 3. Вертикальний удар знизу | F3 = g·(mLDC − mK) | {kn(F3)} |",
     f"| 4. Поздовжній удар | F4 = 1,2·g·(mLDC − mK) | {kn(F4)} |",
     f"| 4, поперечна складова (пропозиція до нової редакції) | F4Y = λ·F4, λ від 0,17 до 0,375 | {kn(F4Y[0])} - {kn(F4Y[1])} |", "",
     "Випадки 2, 5 і 6 стосуються поворотних кілів і швертів і до ВК-90 не застосовуються.", "",
     "## Зусилля в болтах", "",
     f"Попередній затяг болта за формулою FPS = 0,7·σy·Sn: {kn(F_PS)} кН (площу шийки прийнято рівною "
     f"розрахунковій площі різьби {AS_BOLT:.0f} мм2).", "",
     "| Випадок | Момент у корені, кН·м | Зусилля на найнавантаженіший болт, кН | Напруження, МПа | "
     "Запас до межі плинності | Частка від попереднього затягу |", "|---|---|---|---|---|---|",
     f"| 1. Крен 90° | {kn(M1)} | {row(T1)} |",
     f"| 4. Поздовжній удар, сила на низу кіля | {kn(M4)} | {row(T4)} |",
     f"| 4, поперечна складова, λ = 0,17 | {kn(F4Y[0]*SPAN)} | {row(T4Y[0])} |",
     f"| 4, поперечна складова, λ = 0,375 | {kn(F4Y[1]*SPAN)} | {row(T4Y[1])} |", "",
     f"Найбільше зовнішнє зусилля на болт становить {f2(100*T_ALL/F_PS,0)} % від попереднього затягу, тож стик під цими "
     "навантаженнями не розкривається. Випадок 3 болтів не розтягує: сила "
     f"{kn(F3)} кН притискає фланець до днища і має бути сприйнята кільовими флорами.", "",
     f"Напруження в обшивці плавника біля кореня у випадку 1: {f2(sig_fin1,0)} МПа, запас до межі плинності {f2(RE_STEEL/sig_fin1,1)}.", "",
     "## Власна жорсткіша перевірка удару об ґрунт", "",
     "Сила з умови зупинки маси яхти з 6 вузлів на заданому шляху. Шлях зупинки є припущенням.", "",
     "| Шлях зупинки, м | Сила на низ кіля, кН | Відношення до F4 стандарту | Зусилля на передній болт, кН | Запас до межі плинності |",
     "|---|---|---|---|---|"]
for s_, (F, M, T) in GR.items():
    K.append(f"| {f2(s_)} | {kn(F,0)} | {f2(F/F4,1)} | {kn(T)} | {f2(RP_BOLT/(T/AS_BOLT),1)} |")
K += ["", "## Що лишається невиконаним", "",
      "Болти не є слабкою ланкою. Не перевірено: фанерне днище під фланцем, підкладні пластини, кільові флори та їх "
      "з'єднання з обшивкою, втома зварних швів плавника. Доповідь окремо попереджає, що нова редакція стандарту "
      "робить розрахунок на втому обов'язковим і що тонка обшивка під товстою підкладною пластиною є ризикованим рішенням. "
      "Для цих перевірок потрібен повний текст ISO 12215-9 і ISO 12215-5.", "",
      "## Ванти", "", "| Величина | Значення |", "|---|---|",
      f"| Найбільший відновлювальний момент (повне навантаження) | {f2(RM_MAX/1000,1)} кН·м |",
      f"| Напівширина по вант-путенсах | {f2(B_CHAIN)} м |",
      f"| Вертикальне зусилля на вант-путенс навітряного борту | {f2(T_SHROUD/1000,1)} кН |", "",
      "Це статична оцінка без коефіцієнта динамічності. Навантаження на рангоут нормує ISO 12215-10, тексту якого немає.", ""]
open(os.path.join(R.DOCS, "08_navantazhennia_kil_vanty.md"), "w", encoding="utf-8").write("\n".join(K) + "\n")
print("\n".join(K[12:40]))
