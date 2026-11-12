"""Етап 2 ВК-90: розгортність поясів, центрування, вітрильне озброєння, загальне розташування.

Запуск: python3 stage2.py (з каталогу yacht/calc). Спершу виконує етап 1 (run.py).
"""
import os
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon, Ellipse
from scipy.optimize import brentq
import ezdxf
import hull as H
import run as R

f2 = R.f2
DOCS, DRW = R.DOCS, R.DRW
DWL, U = R.DWL, R.U
SHEET_L, SHEET_W, SCARF = 2.50, 1.25, 0.10   # аркуш фанери і довжина з'єднання на вус, м

# ======================= 1. Розгортність поясів =======================
def P(curve, x):
    if curve is None:
        return np.array([x, 0.0, H.zk(x)])
    y, z = curve.at(x)
    return np.array([x, y, z])


def T(curve, x, h=1e-4):
    return (P(curve, x + h) - P(curve, x - h)) / (2 * h)


PANELS = [("Днищевий пояс", None, H.C1), ("Скуловий пояс", H.C1, H.C2), ("Бортовий пояс", H.C2, H.SH)]


EXT = 2.5   # криві продовжено за транець, щоб знайти твірні, які виходять через зріз транця


def rulings(a, b, n=231, span=2.5):
    """Твірні розгортної поверхні між кривими a і b: для точки xa шукаємо xb,
    де дотичні до обох кривих і хорда лежать в одній площині."""
    xmax_a = (a.xend if a else b.xend) - 0.02
    xmax_b = b.xend - 0.02
    xa_s = np.linspace(-EXT, xmax_a, n)
    res, prev = [], None
    for xa in xa_s[::-1]:                     # від носа до корми, щоб гілка розв'язку була неперервною
        pa, ta = P(a, xa), T(a, xa)
        f = lambda xb: float(np.linalg.det(np.array([ta, T(b, xb), P(b, xb) - pa])))
        grid = np.linspace(max(-EXT, xa - span), min(xmax_b, xa + span), 201)
        vals = [f(g) for g in grid]
        roots = [brentq(f, grid[i], grid[i + 1]) for i in range(len(grid) - 1)
                 if vals[i] == 0 or vals[i] * vals[i + 1] < 0]
        if roots:
            ref = prev if prev is not None else xa
            xb = min(roots, key=lambda r: abs(r - ref)); prev = xb
            res.append((xa, xb, True))
        else:
            res.append((xa, min(xa, xmax_b), False))
    return res[::-1]


def analyse(name, a, b):
    rl = rulings(a, b)
    ok = np.array([r[2] for r in rl])
    xa_ = np.array([r[0] for r in rl])
    xb = np.array([r[1] for r in rl])
    ins = ok & (np.maximum(xa_, xb) >= 0)          # твірні, що проходять через корпус
    mono = bool(np.all(np.diff(xb[ins]) > -1e-3))
    # де твірної немає: на кінцях вона виходить за транець або форштевень, усередині це дефект
    idx = np.where(ok)[0]
    gap_aft = max(min(xa_[idx[0]], xb[idx[0]]), 0.0)
    gap_fwd = xa_[-1] - xa_[idx[-1]]
    inner_bad = int((~ok[idx[0]:idx[-1] + 1]).sum())
    gap_in = inner_bad * (xa_[1] - xa_[0])
    xb_f = np.interp(xa_, xa_[ok], xb[ok])
    xb_f[:idx[0]] = xb[idx[0]] - (xa_[idx[0]] - xa_[:idx[0]])
    xb_f[idx[-1]:] = np.linspace(xb[idx[-1]], b.xend - 0.02, len(xb) - idx[-1])
    xb_f = np.maximum.accumulate(xb_f)
    rl = [(xa_[i], xb_f[i], bool(ok[i])) for i in range(len(rl))]
    # відхилення розгортної поверхні від прямих шпангоутних відрізків
    bulge = 0.0
    for xa, xbv, good in rl:
        if not good or abs(xbv - xa) < 1e-6 or max(xa, xbv) < 0:
            continue
        pa, pb = P(a, xa), P(b, xbv)
        for t in np.linspace(0.1, 0.9, 9):
            p = pa + t * (pb - pa); X = p[0]
            if X > min((a.xend if a else b.xend), b.xend) - 0.03 or X < 0.02:
                continue
            qa, qb = P(a, X)[1:], P(b, X)[1:]
            d = qb - qa; L = np.hypot(*d)
            if L < 1e-6:
                continue
            w = p[1:] - qa
            bulge = max(bulge, abs(d[0] * w[1] - d[1] * w[0]) / L)
    # розгортання смугою трикутників уздовж твірних
    A3, B3 = [], []
    for xa, xbv, _ in rl:
        pa, pb = P(a, xa), P(b, xbv)
        if xa < 0 and xbv < 0:
            continue
        if xa < 0:
            pa = pa + (0 - pa[0]) / (pb[0] - pa[0]) * (pb - pa)
        if xbv < 0:
            pb = pb + (0 - pb[0]) / (pa[0] - pb[0]) * (pa - pb)
        A3.append(pa); B3.append(pb)
    a2 = [np.array([0.0, 0.0])]; b2 = [np.array([0.0, np.linalg.norm(B3[0] - A3[0])])]
    area = 0.0; mx = 0.0

    def place(p, q, dp, dq, side):
        d = np.linalg.norm(q - p)
        if d < 1e-9:
            return p.copy()
        ex = (q - p) / d; ey = np.array([-ex[1], ex[0]])
        x = (dp ** 2 - dq ** 2 + d ** 2) / (2 * d)
        y = np.sqrt(max(dp ** 2 - x ** 2, 0.0))
        return p + x * ex + side * y * ey

    for i in range(len(A3) - 1):
        # трикутник A_i, B_i, B_{i+1}; потім A_i, B_{i+1}, A_{i+1}
        nb = place(a2[i], b2[i], np.linalg.norm(B3[i + 1] - A3[i]), np.linalg.norm(B3[i + 1] - B3[i]), -1)
        na = place(a2[i], nb, np.linalg.norm(A3[i + 1] - A3[i]), np.linalg.norm(A3[i + 1] - B3[i + 1]), -1)
        for tri in ((A3[i], B3[i], B3[i + 1]), (A3[i], B3[i + 1], A3[i + 1])):
            s = 0.5 * np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0]))
            area += s; mx += s * (tri[0][0] + tri[1][0] + tri[2][0]) / 3
        a2.append(na); b2.append(nb)
    out = np.array(a2 + b2[::-1])
    ax = out[len(a2) - 1] - out[0]; ang = np.arctan2(ax[1], ax[0])
    rot = np.array([[np.cos(-ang), -np.sin(-ang)], [np.sin(-ang), np.cos(-ang)]])
    out = out @ rot.T; out -= out.min(axis=0)
    Lp, Wp = out[:, 0].max(), out[:, 1].max()
    wmax = max(np.linalg.norm(q - p) for p, q in zip(a2, b2))
    return dict(name=name, cover=100 * ok.mean(), mono=mono, bulge=bulge * 1000, area=area,
                lcg=mx / area, L=Lp, W=Wp, outline=out, wmax=wmax,
                gap_aft=gap_aft, gap_fwd=gap_fwd, gap_in=gap_in)


PAN = [analyse(*p) for p in PANELS]
shell_area = 2 * sum(p["area"] for p in PAN)
shell_lcg = sum(p["area"] * p["lcg"] for p in PAN) / sum(p["area"] for p in PAN)

# ======================= 2. Центрування =======================
X_KEEL = R.X_KEEL
LCG = {  # абсциса ЦМ від транця, м
    "Обшивка корпусу (фанера, склотканина, епоксид)": shell_lcg,
    "Палуба, рубка, кокпіт": 4.20, "Перебірки, флори, стрингери, шпангоутні рами": 4.40,
    "Внутрішнє обладнання приміщень": 4.80, "Підкріплення під кіль, кільові флори": X_KEEL,
    "Плавник кіля (сталь)": X_KEEL, "Бульб кіля (свинець)": X_KEEL + 0.05,
    "Рангоут, такелаж, вітрила": 5.00, "Двигун із приводом, паливна система": 2.55,
    "Електрообладнання, акумулятори": 3.10, "Палубне обладнання, якірний пристрій": 4.60,
    "Суднові системи, камбуз, гальюн, цистерни": 4.20, "Стерновий пристрій": 0.60,
    "Шпаклювання, фарбування": 4.30, "Запас маси 8 % (без баласту)": 4.20,
    "Екіпаж 2 особи": 1.60, "Обов'язкове спорядження": 3.50, "Екіпаж 4 особи": 1.60,
    "Прісна вода": 4.60, "Паливо": 1.90, "Провізія, особисті речі": 4.50, "Рятувальний пліт": 0.50,
}


def lcg(items):
    return sum(i[1] * LCG[i[0]] for i in items) / sum(i[1] for i in items)


COND = []
for nm, items, mass, kg in [("Порожнем", R.LIGHT_ALL, R.m_lc, R.kg_lc),
                            ("Мінімальне навантаження", R.LIGHT_ALL + R.LOAD_MO, R.m_mo, R.kg_mo),
                            ("Повне навантаження", R.LIGHT_ALL + R.LOAD_LDC, R.m_ldc, R.kg_ldc)]:
    u = H.upright(mass, R.APPS)
    g = lcg(items)
    gml = u["KB"] + u["BMl"] - kg
    trim = (g - u["LCB"]) / gml * u["LWL"]      # + означає диферент на ніс, м на довжині КВЛ
    COND.append(dict(name=nm, mass=mass, lcg=g, lcb=u["LCB"], trim=trim, d=u["d"]))

# ======================= 3. Вітрильне озброєння =======================
X_MAST = 5.20
ROOF = (2.60, 6.40)                       # рубка по довжині
ROOF_H_AFT, ROOF_H_FWD, ROOF_HB = 0.67, 0.50, 1.00
roof_z = lambda x: H.SH.at(x)[1] + ROOF_H_AFT + (ROOF_H_FWD - ROOF_H_AFT) * (x - ROOF[0]) / (ROOF[1] - ROOF[0])
P_, E_, I_, J_ = 11.20, 4.00, 10.60, 3.30
Z_DECK_MAST = H.SH.at(X_MAST)[1]
Z_ROOF = roof_z(X_MAST)
Z_BOOM = Z_ROOF + 0.80
Z_HEAD = Z_BOOM + P_ + 0.15
Z_HOUNDS = Z_DECK_MAST + I_
X_TACK = X_MAST + J_
main_tri = 0.5 * P_ * E_
ROACH = [(0.0, 0.0), (1.0, 0.0), (0.66, 0.40), (0.27, 0.75), (0.0, 1.0)]   # частки E і P, у межах ахтерштага
_rx = np.array([p[0] * E_ for p in ROACH]); _rz = np.array([p[1] * P_ for p in ROACH])
A_MAIN = 0.5 * abs(np.dot(_rx, np.roll(_rz, -1)) - np.dot(_rz, np.roll(_rx, -1)))
luff = 0.96 * np.hypot(I_, J_); LP = 1.08 * J_
A_JIB = 0.5 * luff * LP
A_FT = 0.5 * I_ * J_
ce_main = np.array([X_MAST - E_ / 3, Z_BOOM + P_ / 3])
ce_ft = np.array([(X_TACK + 2 * X_MAST) / 3, Z_DECK_MAST + I_ / 3])
CE = (main_tri * ce_main + A_FT * ce_ft) / (main_tri + A_FT)

# центр бічного опору: підводний профіль корпусу, кіль, бульб, стерно
xs = np.linspace(U["xa"], U["xf"], 400)
dep = np.array([max(DWL - H.zk(x), 0) for x in xs])
a_h = np.trapezoid(dep, xs); cx_h = np.trapezoid(dep * xs, xs) / a_h
cz_h = DWL - np.trapezoid(dep ** 2 / 2, xs) / a_h
zb = R.z_keel_bottom
fin_top, fin_bot, fin_h = 1.10, 0.65, (0.0 - (zb + 0.22))
a_f = 0.5 * (fin_top + fin_bot) * fin_h
a_b = 1.7 * 0.26 * 0.8
a_r = 0.5 * (0.45 + 0.30) * (H.zk(0.6) - (DWL - 1.35))
parts = [(a_h, cx_h, cz_h), (a_f, X_KEEL + 0.02, -fin_h * 0.45), (a_b, X_KEEL + 0.05, zb + 0.13),
         (a_r, 0.57, (H.zk(0.6) + DWL - 1.35) / 2)]
A_LAT = sum(p[0] for p in parts)
CLR = np.array([sum(p[0] * p[1] for p in parts) / A_LAT, sum(p[0] * p[2] for p in parts) / A_LAT])
LEAD = (CE[0] - CLR[0]) / U["LWL"] * 100
SA = A_MAIN + A_JIB
vol = R.m_dwl / H.RHO
gm_dwl = U["KB"] + U["BMt"] - R.kg_dwl
ARM = CE[1] - CLR[1]
DELL = np.degrees(47.88 * SA * ARM / (R.m_dwl * 9.81 * gm_dwl))   # тиск вітру 47,88 Па (1 фунт на кв. фут)

# ======================= 4. Загальне розташування =======================
BH = {"Таранна перебірка": 8.30, "Перебірка носової каюти": 6.25, "Головна перебірка (під щоглою)": 5.20,
      "Перебірка рубки (вхід)": 2.60, "Перебірка ахтерпіка": 0.40}
Z_SOLE = 0.15
HEADROOM_AFT = roof_z(3.0) - 0.05 - Z_SOLE
HEADROOM_FWD = roof_z(5.0) - 0.05 - Z_SOLE
hb = lambda x, z: (R.hb_at(x, z) or 0.0)
sole_w = 2 * hb(4.2, Z_SOLE)


def inner(x0, x1, z, y_in, n=25, off=0.03):
    """Контур приміщення в плані: від внутрішньої межі y_in до борту на висоті z."""
    xx = np.linspace(x0, x1, n)
    return [(x, y_in) for x in xx] + [(x, max(hb(x, z) - off, y_in)) for x in xx[::-1]]


ROOMS = [  # (назва, x0, x1, висота z для ширини, внутрішня межа y, борт: +1 правий, -1 лівий)
    ("Диван-койка ПрБ", 3.30, 5.20, 0.60, 0.42, 1), ("Диван-койка ЛБ", 3.30, 5.20, 0.60, 0.42, -1),
    ("Камбуз", 2.60, 3.30, 0.95, 0.40, -1), ("Штурм. стіл", 2.60, 3.30, 0.95, 0.40, 1),
    ("Гальюн", 5.20, 6.25, 0.60, 0.25, -1), ("Шафа", 5.20, 6.25, 0.60, 0.35, 1),
    ("Рундук кокпіта", 0.40, 2.60, 1.20, 0.60, 1), ("Рундук кокпіта, паливо", 0.40, 2.60, 1.20, 0.60, -1),
]
vberth = [(6.25, -hb(6.25, 0.85) + 0.03)] + [(x, -max(hb(x, 0.85) - 0.03, 0)) for x in np.linspace(6.25, 8.30, 20)] + \
         [(x, max(hb(x, 0.85) - 0.03, 0)) for x in np.linspace(8.30, 6.25, 20)]
VB_W_HEAD, VB_W_FOOT = 2 * (hb(6.25, 0.85) - 0.03), 2 * max(hb(8.30, 0.85) - 0.03, 0)
SETTEE_W = hb(4.2, 0.60) - 0.03 - 0.42

sheer_plan = [(x, H.SH.at(x)[0]) for x in R.XF]
keel_line = R.keel_line


def hull_profile(ax):
    ax.plot(*zip(*keel_line), "k", lw=1.5)
    ax.plot(*zip(*R.curve_prof[2]), "k", lw=1.5)
    ax.plot([0, 0], [H.zk(0), H.SH.at(0)[1]], "k", lw=1.5)
    ax.axhline(DWL, color="b", lw=0.7)
    kx = [X_KEEL - 0.55, X_KEEL + 0.55, X_KEEL + 0.35, X_KEEL - 0.30]
    kz = [H.zk(X_KEEL - 0.55), H.zk(X_KEEL + 0.55), zb + 0.22, zb + 0.22]
    ax.add_patch(Polygon(list(zip(kx, kz)), fc="0.85", ec="k"))
    ax.add_patch(Ellipse((X_KEEL + 0.05, zb + 0.13), 1.7, 0.26, fc="0.6", ec="k"))
    ax.add_patch(Polygon([(0.35, H.zk(0.35)), (0.80, H.zk(0.80)), (0.70, DWL - 1.35), (0.40, DWL - 1.35)], fc="0.85", ec="k"))
    rx = np.linspace(*ROOF, 30)
    ax.plot([ROOF[0]] + list(rx) + [ROOF[1]], [H.SH.at(ROOF[0])[1]] + [roof_z(x) for x in rx] + [H.SH.at(ROOF[1])[1]], "k", lw=1.2)


# ---- ВК90-002 Загальне розташування
fig, (a1, a2) = plt.subplots(2, 1, figsize=(23.4, 16.5))
fig.suptitle("ВК-90. Загальне розташування, ревізія 0 (ескіз, не для виробництва). Розміри в метрах від транця", fontsize=17)
hull_profile(a1)
for nm, x in BH.items():
    a1.plot([x, x], [H.zk(x) + 0.02, H.SH.at(x)[1]], "r", lw=1.2)
a1.plot([2.60, 6.25], [Z_SOLE, Z_SOLE], "g", lw=1.2); a1.text(4.3, Z_SOLE + 0.04, "пайол", color="g", fontsize=10)
a1.plot([0.40, 2.60], [0.95, 0.95], "g", lw=1.2); a1.text(1.2, 0.99, "дно кокпіта", color="g", fontsize=10)
a1.plot([3.30, 5.20], [0.60, 0.60], "0.4", lw=1); a1.plot([6.25, 8.30], [0.85, 0.85], "0.4", lw=1)
a1.add_patch(Polygon([(2.15, 0.30), (3.00, 0.30), (3.00, 0.90), (2.15, 0.90)], fc="0.9", ec="k"))
a1.text(2.57, 0.55, "дизель", ha="center", fontsize=10)
a1.plot([2.35, 2.35], [0.30, DWL - 0.75], "k", lw=3); a1.text(2.4, -0.25, "привід", fontsize=9)
a1.plot([X_MAST, X_MAST], [Z_ROOF, Z_ROOF + 1.2], "k", lw=4); a1.text(X_MAST + 0.08, Z_ROOF + 1.0, "щогла", fontsize=10)
for t, x, z in [("кокпіт", 1.5, 1.25), ("камбуз / штурманський стіл", 2.95, 1.25), ("кают-компанія", 4.25, 1.0),
                ("гальюн / шафа", 5.72, 1.0), ("носова каюта", 7.1, 1.15), ("якірний ящик", 8.55, 1.45), ("ахтерпік", 0.2, 1.15)]:
    a1.text(x, z, t, ha="center", fontsize=10)
a1.set_aspect("equal"); a1.set_xlim(-0.4, 9.4); a1.set_ylim(-1.3, 3.4); a1.set_title("Бік")

for sgn in (1, -1):
    a2.plot([p[0] for p in sheer_plan], [sgn * p[1] for p in sheer_plan], "k", lw=1.5)
a2.plot([0, 0], [-H.SH.at(0)[0], H.SH.at(0)[0]], "k", lw=1.5)
a2.add_patch(Polygon([(ROOF[0], -ROOF_HB), (ROOF[1] - 0.5, -ROOF_HB), (ROOF[1], -0.55), (ROOF[1], 0.55),
                      (ROOF[1] - 0.5, ROOF_HB), (ROOF[0], ROOF_HB)], fill=False, ec="k", ls="--"))
a2.add_patch(Polygon([(0.40, -0.60), (2.60, -0.60), (2.60, 0.60), (0.40, 0.60)], fill=False, ec="g", lw=1.2))
for nm, x in BH.items():
    w = H.SH.at(x)[0]; a2.plot([x, x], [-w, w], "r", lw=1.2)
for nm, x0, x1, z, yin, sgn in ROOMS:
    pts = [(x, sgn * y) for x, y in inner(x0, x1, z, yin)]
    a2.add_patch(Polygon(pts, fc="0.93", ec="k", lw=0.8))
    cx = (x0 + x1) / 2
    a2.text(cx, sgn * (yin + hb(cx, z)) / 2, nm.replace(", ", ",\n"), ha="center", va="center", fontsize=9)
a2.add_patch(Polygon(vberth, fc="0.93", ec="k", lw=0.8)); a2.text(7.0, 0, "двоспальна койка", ha="center", fontsize=10)
a2.add_patch(Polygon([(3.75, -0.28), (4.85, -0.28), (4.85, 0.28), (3.75, 0.28)], fc="1", ec="k")); a2.text(4.3, 0, "стіл", ha="center", va="center", fontsize=9)
a2.add_patch(Polygon([(2.15, -0.30), (3.00, -0.30), (3.00, 0.30), (2.15, 0.30)], fill=False, ec="k", ls=":")); a2.text(2.57, 0, "трап,\nдизель", ha="center", va="center", fontsize=9)
a2.plot(X_MAST, 0, "ko", ms=10); a2.text(1.5, 0, "кокпіт", ha="center", fontsize=10)
a2.set_aspect("equal"); a2.set_xlim(-0.4, 9.4); a2.set_ylim(-1.8, 1.8); a2.set_title("План")
fig.savefig(os.path.join(DRW, "VK90-002_zahalne_roztashuvannia.pdf"))
fig.savefig(os.path.join(DRW, "VK90-002_zahalne_roztashuvannia.png"), dpi=60)

# ---- ВК90-003 Вітрильне озброєння
fig, ax = plt.subplots(figsize=(16.5, 23.4))
ax.set_title("ВК-90. Вітрильне озброєння, ревізія 0 (ескіз, не для виробництва)", fontsize=17)
hull_profile(ax)
ax.plot([X_MAST, X_MAST], [Z_ROOF, Z_HEAD], "k", lw=4)
ax.plot([X_MAST, X_MAST - E_ - 0.1], [Z_BOOM, Z_BOOM], "k", lw=3)
roach = [(X_MAST - p[0] * E_, Z_BOOM + p[1] * P_) for p in ROACH]
ax.add_patch(Polygon(roach, fc="#dde8f5", ec="b"))
xh = X_TACK - 0.96 * J_; zh = Z_DECK_MAST + 0.25 + 0.96 * (I_ - 0.25)
jib = [(X_TACK, H.SH.at(min(X_TACK, 9))[1] + 0.25), (xh, zh), (X_MAST - 0.25, Z_DECK_MAST + 0.75)]
ax.add_patch(Polygon(jib, fc="#e8f1dd", ec="g"))
ax.plot([X_TACK, X_MAST], [H.SH.at(min(X_TACK, 9))[1], Z_HOUNDS], "k", lw=1)
ax.plot([0, X_MAST], [H.SH.at(0)[1], Z_HEAD], "k", lw=1)
ax.plot(*CE, "r+", ms=22, mew=2); ax.text(CE[0] + 0.15, CE[1], "ЦВ (центр вітрильності)", color="r", fontsize=12)
ax.plot(*CLR, "rx", ms=16, mew=2); ax.text(CLR[0] + 0.15, CLR[1] - 0.3, "ЦБО (центр бічного опору)", color="r", fontsize=12)
ax.text(-0.3, 15.2, f"P = {f2(P_)} м   E = {f2(E_)} м   I = {f2(I_)} м   J = {f2(J_)} м\n"
        f"Грот {f2(A_MAIN,1)} м2   Стаксель 108 % {f2(A_JIB,1)} м2   Разом {f2(SA,1)} м2\n"
        f"Топ щогли над КВЛ {f2(Z_HEAD-DWL,1)} м   Випередження ЦВ {f2(LEAD,1)} % довжини по КВЛ", fontsize=13)
ax.set_aspect("equal"); ax.set_xlim(-0.6, 9.6); ax.set_ylim(-1.4, 16.6)
fig.savefig(os.path.join(DRW, "VK90-003_vitrylne_ozbroiennia.pdf"))
fig.savefig(os.path.join(DRW, "VK90-003_vitrylne_ozbroiennia.png"), dpi=50)

# ---- DXF: розташування з озброєнням і попередні розгортки
mm = lambda pts, dx=0, dy=0: [(p[0] * 1000 + dx, p[1] * 1000 + dy) for p in pts]
doc = ezdxf.new("R2010", setup=True); doc.units = ezdxf.units.MM; msp = doc.modelspace()
for n, c in [("KORPUS", 7), ("PEREBIRKY", 1), ("PRYMISHCHENNIA", 8), ("RANHOUT", 5), ("VITRYLA", 3), ("TEXT", 7)]:
    doc.layers.add(n, color=c)
msp.add_lwpolyline(mm(keel_line), dxfattribs={"layer": "KORPUS"})
msp.add_lwpolyline(mm(R.curve_prof[2]), dxfattribs={"layer": "KORPUS"})
msp.add_line((0, H.zk(0) * 1000), (0, H.SH.at(0)[1] * 1000), dxfattribs={"layer": "KORPUS"})
rx = np.linspace(*ROOF, 30)
msp.add_lwpolyline(mm([(ROOF[0], H.SH.at(ROOF[0])[1])] + [(x, roof_z(x)) for x in rx] + [(ROOF[1], H.SH.at(ROOF[1])[1])]), dxfattribs={"layer": "KORPUS"})
msp.add_line((-300, DWL * 1000), (9300, DWL * 1000), dxfattribs={"layer": "KORPUS"})
for x in BH.values():
    msp.add_line((x * 1000, H.zk(x) * 1000), (x * 1000, H.SH.at(x)[1] * 1000), dxfattribs={"layer": "PEREBIRKY"})
    w = H.SH.at(x)[0] * 1000
    msp.add_line((x * 1000, -4000 - w), (x * 1000, -4000 + w), dxfattribs={"layer": "PEREBIRKY"})
msp.add_line((X_MAST * 1000, Z_ROOF * 1000), (X_MAST * 1000, Z_HEAD * 1000), dxfattribs={"layer": "RANHOUT"})
msp.add_line((X_MAST * 1000, Z_BOOM * 1000), ((X_MAST - E_) * 1000, Z_BOOM * 1000), dxfattribs={"layer": "RANHOUT"})
msp.add_lwpolyline(mm(roach), close=True, dxfattribs={"layer": "VITRYLA"})
msp.add_lwpolyline(mm(jib), close=True, dxfattribs={"layer": "VITRYLA"})
for sgn in (1, -1):
    msp.add_lwpolyline(mm([(p[0], sgn * p[1]) for p in sheer_plan], 0, -4000), dxfattribs={"layer": "KORPUS"})
for nm, x0, x1, z, yin, sgn in ROOMS:
    msp.add_lwpolyline(mm([(x, sgn * y) for x, y in inner(x0, x1, z, yin)], 0, -4000), close=True, dxfattribs={"layer": "PRYMISHCHENNIA"})
msp.add_lwpolyline(mm(vberth, 0, -4000), close=True, dxfattribs={"layer": "PRYMISHCHENNIA"})
msp.add_text("VK90-002/003 rev.0  Zahalne roztashuvannia, vitrylne ozbroiennia. Eskiz. mm, 1:1", height=120,
             dxfattribs={"layer": "TEXT"}).set_placement((0, -6500))
doc.saveas(os.path.join(DRW, "VK90-002_003_roztashuvannia_ozbroiennia.dxf"))

doc = ezdxf.new("R2010", setup=True); doc.units = ezdxf.units.MM; msp = doc.modelspace()
doc.layers.add("POIAS", color=7); doc.layers.add("TEXT", color=1)
oy = 0
for p in PAN:
    msp.add_lwpolyline(mm(p["outline"], 0, oy), close=True, dxfattribs={"layer": "POIAS"})
    msp.add_text(f"poias {PAN.index(p)+1}: POPEREDNIA rozghortka, NE DLIA ROZKROIU", height=60,
                 dxfattribs={"layer": "TEXT"}).set_placement((200, oy - 150))
    oy += (p["W"] + 0.5) * 1000
doc.saveas(os.path.join(DRW, "VK90-020_rozghortky_poperedni.dxf"))

# ======================= Звіт =======================
L = ["# ВК-90. Розрахунок етапу 2, ревізія 0", "",
     "Файл згенеровано скриптом `calc/stage2.py`, вручну не редагувати.", "",
     "## Розгортність поясів обшивки", "",
     "Для кожного поясу шукаються твірні розгортної поверхні між двома граничними кривими. "
     "Покриття показує частку довжини поясу, де твірну знайдено. Стрілка прогину показує, наскільки "
     "розгортна поверхня відходить від прямих шпангоутних відрізків теоретичного креслення.", "",
     "| Пояс | Без твірних у кормі, м | Без твірних у носі, м | Без твірних усередині, м | Твірні не перетинаються | "
     "Стрілка прогину, мм | Площа одного поясу, м2 | Габарит розгортки, м | Найбільша ширина поясу, м |",
     "|---|---|---|---|---|---|---|---|---|"]
for p in PAN:
    L.append(f"| {p['name']} | {f2(p['gap_aft'])} | {f2(p['gap_fwd'])} | {f2(p['gap_in'])} | "
             f"{'так' if p['mono'] else 'ні'} | {f2(p['bulge'],0)} | {f2(p['area'])} | "
             f"{f2(p['L'])} x {f2(p['W'])} | {f2(p['wmax'])} |")
L += ["", f"Площа обшивки корпусу без транця: {f2(shell_area,1)} м2, центр площі на {f2(shell_lcg)} м від транця. "
      f"Маса обшивки за прийнятих у навантаженні 340 кг відповідає {f2(340/shell_area,1)} кг/м2.", "",
      "## Поздовжнє центрування", "",
      "| Стан | Маса, кг | ЦМ від транця, м | ЦВ від транця, м | Диферент на довжині КВЛ, см |", "|---|---|---|---|---|"]
for c in COND:
    L.append(f"| {c['name']} | {c['mass']:.0f} | {f2(c['lcg'])} | {f2(c['lcb'])} | "
             f"{f2(abs(c['trim'])*100,1)} {'на ніс' if c['trim']>0 else 'на корму'} |")
L += ["", f"Кіль стоїть на {f2(X_KEEL)} м від транця, щогла на {f2(X_MAST)} м, дизель на 2,55 м. "
      "Диферент оцінено через поздовжню метацентричну висоту, без перерахунку гідростатики з диферентом.", "",
      "## Вітрильне озброєння", "", "| Параметр | Значення |", "|---|---|",
      f"| P (передня шкаторина грота) | {f2(P_)} м |", f"| E (нижня шкаторина грота) | {f2(E_)} м |",
      f"| I (висота переднього трикутника) | {f2(I_)} м |", f"| J (основа переднього трикутника) | {f2(J_)} м |",
      f"| Грот із серпом ({f2(100*(A_MAIN/main_tri-1),0)} % понад трикутник) | {f2(A_MAIN,1)} м2 |", f"| Стаксель 108 % | {f2(A_JIB,1)} м2 |",
      f"| Разом | {f2(SA,1)} м2 |", f"| SA/D на КВЛ | {f2(SA/vol**(2/3),1)} |",
      f"| Топ щогли над КВЛ | {f2(Z_HEAD-DWL,1)} м |", f"| Довжина щогли від даху рубки | {f2(Z_HEAD-Z_ROOF,1)} м |",
      f"| Центр вітрильності (грот і передній трикутник) | {f2(CE[0])} м від транця, {f2(CE[1]-DWL)} м над КВЛ |",
      f"| Центр бічного опору (корпус, кіль, бульб, стерно) | {f2(CLR[0])} м від транця, {f2(DWL-CLR[1])} м під КВЛ |",
      f"| Площа бічного опору | {f2(A_LAT)} м2 |",
      f"| Випередження центра вітрильності | {f2(LEAD,1)} % довжини по КВЛ |",
      f"| Кут Делленбау (крен від тиску 47,9 Па на всю площу) | {f2(DELL,1)}° |", "",
      "## Загальне розташування", "", "| Параметр | Значення |", "|---|---|"]
for nm, x in sorted(BH.items(), key=lambda kv: kv[1]):
    L.append(f"| {nm} | {f2(x)} м від транця |")
L += [f"| Висота в кают-компанії біля входу | {f2(HEADROOM_AFT)} м |",
      f"| Висота в кают-компанії біля головної перебірки | {f2(HEADROOM_FWD)} м |",
      f"| Ширина пайола на міделі | {f2(sole_w)} м |",
      f"| Диван-койки кают-компанії | {f2(5.20-3.30)} x {f2(SETTEE_W)} м |",
      f"| Носова двоспальна койка | довжина {f2(8.30-6.25)} м, ширина {f2(VB_W_HEAD)} м в головах, {f2(VB_W_FOOT)} м у ногах |",
      "| Кокпіт | довжина 2,20 м, ширина дна 1,20 м |", ""]
open(os.path.join(DOCS, "04_rozrakhunok_etapu_2.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L))
