"""Етап 3, крок 1: остійність ВК-90 з урахуванням об'єму рубки.

Запуск: python3 stage3_stability.py (з каталогу yacht/calc).
"""
import os
import numpy as np
import hull as H
import run as R

ROOF = (2.60, 6.40); H_AFT, H_FWD, HB = 0.67, 0.50, 1.00
base = {k: (R.S_MO if k == "mo" else R.S_LDC) for k in ("mo", "ldc")}


def section_roof(x):
    h = H.half_section(x)
    if not (ROOF[0] <= x <= ROOF[1]):
        return H.full_section(x)
    ys, zs = h[-1]
    zr = zs + H_AFT + (H_FWD - H_AFT) * (x - ROOF[0]) / (ROOF[1] - ROOF[0])
    hb = min(HB, ys - 0.25)
    right = h + [(hb, zs), (hb, zr)]
    left = [(-y, z) for (y, z) in reversed(right[1:])]
    return np.array(right + left)


H.SECTIONS = [section_roof(x) for x in H.XS]
S_MO, S_LDC = R.stab(R.m_mo, R.kg_mo), R.stab(R.m_ldc, R.kg_ldc)
S_KG = R.stab(R.m_mo, R.kg_mo + 0.10)
f2 = R.f2
L = ["# ВК-90. Остійність з урахуванням рубки, ревізія 0", "",
     "Файл згенеровано скриптом `calc/stage3_stability.py`, вручну не редагувати.", "",
     "Рубка врахована як водонепроникний об'єм від 2,60 до 6,40 м від транця, напівширина 1,00 м, "
     "висота 0,67 м у кормі та 0,50 м у носі. Кокпіт, диферент і кут заливання не враховано.", "",
     "| Стан | Маса, кг | KG, м | GZ max, м | Кут GZ max | Кут заходу без рубки | Кут заходу з рубкою | "
     "Площа під GZ з рубкою, м·рад |", "|---|---|---|---|---|---|---|---|"]
for nm, s, b in [("Мінімальне навантаження", S_MO, R.S_MO), ("Повне навантаження", S_LDC, R.S_LDC),
                 ("Чутливість: KG вище на 0,10 м", S_KG, R.S_KG)]:
    L.append(f"| {nm} | {s['mass']:.0f} | {f2(s['kg'])} | {f2(s['gzmax'])} | {s['amax']:.0f}° | "
             f"{f2(b['avs'],0)}° | {f2(s['avs'],0)}° | {f2(s['area'])} |")
L += ["", "Таблиця плечей GZ з рубкою, м:", "", "| Кут | " + " | ".join(f"{a}°" for a in R.ANG[::2]) + " |",
      "|---|" + "---|" * len(R.ANG[::2]),
      "| Мін. навантаження | " + " | ".join(f2(abs(v) if abs(v) < 0.005 else v) for v in S_MO["gz"][::2]) + " |",
      "| Повне навантаження | " + " | ".join(f2(abs(v) if abs(v) < 0.005 else v) for v in S_LDC["gz"][::2]) + " |", ""]
open(os.path.join(R.DOCS, "07_ostiinist_z_rubkoiu.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L[6:12]))
