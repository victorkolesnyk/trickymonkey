"""Параметрична модель корпусу вітрильного крейсера ВК-90 (гострі вилиці, фанера).

Система координат: x від транця (0) до форштевня (LH), y від ДП на правий борт,
z від основної лінії (найнижча точка корпусу без кіля). Одиниці: метри, кілограми.
"""
import numpy as np
from scipy.interpolate import CubicSpline
from scipy.optimize import brentq

RHO = 1025.0  # кг/м3, морська вода
LH = 9.00

# Кільова лінія (профіль днища в ДП і форштевень)
KEEL = np.array([
    (0.00, 0.455), (1.50, 0.225), (3.00, 0.045), (3.90, 0.000), (5.40, 0.060),
    (6.60, 0.175), (7.60, 0.330), (8.30, 0.540), (8.70, 0.900), (8.90, 1.250),
    (9.00, 1.620),
])

# Вилиці та лінія борту: (x, y, z); остання точка кожної вилиці лежить на форштевні
CHINE1 = np.array([
    (0.00, 0.970, 0.515), (1.50, 1.075, 0.355), (3.00, 1.125, 0.230),
    (4.20, 1.120, 0.200), (5.40, 1.000, 0.235), (6.60, 0.740, 0.320),
    (7.60, 0.400, 0.450), (8.42, 0.000, None),
])
CHINE2 = np.array([
    (0.00, 1.290, 0.700), (1.50, 1.390, 0.610), (3.00, 1.440, 0.555),
    (4.20, 1.440, 0.545), (5.40, 1.340, 0.575), (6.60, 1.080, 0.650),
    (7.60, 0.690, 0.760), (8.30, 0.300, 0.870), (8.74, 0.000, None),
], dtype=object)
SHEER = np.array([
    (0.00, 1.360, 1.480), (1.50, 1.500, 1.430), (3.00, 1.550, 1.400),
    (4.20, 1.550, 1.400), (5.40, 1.470, 1.425), (6.60, 1.240, 1.475),
    (7.60, 0.880, 1.530), (8.40, 0.440, 1.580), (9.00, 0.000, 1.620),
])

_keel = CubicSpline(KEEL[:, 0], KEEL[:, 1], bc_type="natural")


def zk(x):
    return float(_keel(x))


class Curve:
    def __init__(self, pts):
        pts = [list(p) for p in pts]
        self.xend = float(pts[-1][0])
        if pts[-1][2] is None:
            pts[-1][2] = zk(self.xend)
        a = np.array(pts, dtype=float)
        self.fy = CubicSpline(a[:, 0], a[:, 1], bc_type="natural")
        self.fz = CubicSpline(a[:, 0], a[:, 2], bc_type="natural")

    def at(self, x):
        if x >= self.xend:
            return 0.0, zk(x)
        return max(float(self.fy(x)), 0.0), float(self.fz(x))


C1, C2, SH = Curve(CHINE1), Curve(CHINE2), Curve(SHEER)
CURVES = [C1, C2, SH]
NAMES = ["Вилиця 1", "Вилиця 2", "Борт (палубна лінія)"]


def half_section(x):
    """Точки напівшпангоута від кіля до борту: [(y, z), ...]."""
    pts = [(0.0, zk(x))]
    for c in CURVES:
        pts.append(c.at(x))
    return pts


def full_section(x):
    """Замкнений багатокутник шпангоута з плоскою палубою на рівні борту."""
    h = half_section(x)
    right = h
    left = [(-y, z) for (y, z) in reversed(h[1:])]
    return np.array(right + left)  # кіль, ПрБ вгору, (палуба), ЛБ вниз


def _clip(poly, phi, d):
    """Частина багатокутника нижче ватерлінії при крені phi (рад) і зануренні d."""
    s, c = np.sin(phi), np.cos(phi)
    h = poly[:, 1] * c - poly[:, 0] * s - d
    out = []
    n = len(poly)
    for i in range(n):
        j = (i + 1) % n
        if h[i] <= 0:
            out.append(poly[i])
        if (h[i] <= 0) != (h[j] <= 0):
            t = h[i] / (h[i] - h[j])
            out.append(poly[i] + t * (poly[j] - poly[i]))
    return np.array(out) if len(out) >= 3 else None


def _area_centroid(p):
    x, y = p[:, 0], p[:, 1]
    x1, y1 = np.roll(x, -1), np.roll(y, -1)
    cr = x * y1 - x1 * y
    a = cr.sum() / 2.0
    if abs(a) < 1e-12:
        return 0.0, 0.0, 0.0
    cx = ((x + x1) * cr).sum() / (6 * a)
    cy = ((y + y1) * cr).sum() / (6 * a)
    return abs(a), cx, cy


XS = np.linspace(0.0, LH, 361)
SECTIONS = [full_section(x) for x in XS]


def immersed(phi, d):
    """Об'єм, центр величини (x, y, z) голого корпусу при крені phi і зануренні d."""
    A = np.zeros(len(XS)); my = np.zeros(len(XS)); mz = np.zeros(len(XS))
    for i, sec in enumerate(SECTIONS):
        p = _clip(sec, phi, d)
        if p is None:
            continue
        a, cy, cz = _area_centroid(p)
        A[i], my[i], mz[i] = a, a * cy, a * cz
    V = np.trapezoid(A, XS)
    if V <= 0:
        return 0.0, 0.0, 0.0, 0.0, A
    return (V, np.trapezoid(A * XS, XS) / V, np.trapezoid(my, XS) / V,
            np.trapezoid(mz, XS) / V, A)


class Appendage:
    """Виступна частина (кіль, бульб, стерно) як зосереджений об'єм."""
    def __init__(self, name, vol, x, z):
        self.name, self.vol, self.x, self.z = name, vol, x, z


def equilibrium(mass, phi, apps):
    """Занурення d при заданій масі й крені; повертає d, V, B(x, y, z)."""
    s, c = np.sin(phi), np.cos(phi)
    vtot = mass / RHO

    def parts(d):
        V, bx, by, bz, _ = immersed(phi, d)
        va = mx = mz = 0.0
        for a in apps:
            if a.z * c - d <= 0:  # центр виступної частини під водою
                va += a.vol; mx += a.vol * a.x; mz += a.vol * a.z
        return V, bx, by, bz, va, mx, mz

    def f(d):
        V, *_, va, _, _ = parts(d)
        return V + va - vtot

    d = brentq(f, -4.0, 4.0, xtol=1e-6)
    V, bx, by, bz, va, mx, mz = parts(d)
    vt = V + va
    return d, vt, ((V * bx + mx) / vt, V * by / vt, (V * bz + mz) / vt)


def gz_curve(mass, kg, apps, angles_deg):
    out = []
    for a in angles_deg:
        phi = np.radians(a)
        d, vt, (bx, by, bz) = equilibrium(mass, phi, apps)
        out.append(by * np.cos(phi) + (bz - kg) * np.sin(phi))
    return np.array(out)


def upright(mass, apps):
    """Гідростатика на рівному кілі без крену."""
    d, vt, (bx, by, bz) = equilibrium(mass, 0.0, apps)
    V, hx, hy, hz, A = immersed(0.0, d)
    # ватерлінія: напівширини на рівні d
    hb = np.zeros(len(XS)); girth = np.zeros(len(XS))
    for i, x in enumerate(XS):
        h = half_section(x)
        for (ya, za), (yb, zb) in zip(h[:-1], h[1:]):
            if za <= d and zb <= d:
                girth[i] += np.hypot(yb - ya, zb - za)
            elif (za <= d) != (zb <= d):
                t = (d - za) / (zb - za)
                yw = ya + t * (yb - ya)
                if za <= d:
                    girth[i] += np.hypot(yw - ya, d - za)
                    hb[i] = max(hb[i], yw)
                else:
                    girth[i] += np.hypot(yb - yw, zb - d)
    wet = XS[hb > 1e-6]
    xa, xf = wet.min(), wet.max()
    lwl = xf - xa
    bwl = 2 * hb.max()
    awp = 2 * np.trapezoid(hb, XS)
    lcf = 2 * np.trapezoid(hb * XS, XS) / awp
    it = 2.0 / 3.0 * np.trapezoid(hb ** 3, XS)
    il = 2 * np.trapezoid(hb * (XS - lcf) ** 2, XS)
    amax = A.max()
    tc = d - min(zk(x) for x in XS)
    return dict(d=d, V=V, Vtot=vt, LCB=bx, KB=bz, KB_hull=hz, LWL=lwl, xa=xa, xf=xf,
                BWL=bwl, Awp=awp, LCF=lcf, BMt=it / vt, BMl=il / vt, Tc=tc,
                Cp=V / (amax * lwl), Cb=V / (lwl * bwl * tc), Cm=amax / (bwl * tc),
                Cwp=awp / (lwl * bwl), S=2 * np.trapezoid(girth, XS),
                TPC=awp * RHO / 100.0, Amax=amax, hb=hb)
