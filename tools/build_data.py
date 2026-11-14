#!/usr/bin/env python3
"""Arma data/porticos.json: pórticos de OpenStreetMap (Overpass) con su tarifa ya asignada.
La app descarga ese archivo; así nadie tiene que cruzar datos a mano.
Uso: python3 tools/build_data.py   (necesita acceso a Overpass)"""
import json, math, os, re, sys, time, unicodedata, urllib.parse, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_tarifas

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
QUERY = ('[out:json][timeout:90];node["highway"="toll_gantry"](-34.3,-71.7,-32.8,-70.2)->.g;'
         '.g out body;way(bn.g)["highway"];out geom;')
HOSTS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
         "https://overpass.private.coffee/api/interpreter"]
RUMBO_TOL = 100.0
RAD = math.pi / 180


def bearing(a, b):
    y = math.sin((b["lon"] - a["lon"]) * RAD) * math.cos(b["lat"] * RAD)
    x = (math.cos(a["lat"] * RAD) * math.sin(b["lat"] * RAD)
         - math.sin(a["lat"] * RAD) * math.cos(b["lat"] * RAD) * math.cos((b["lon"] - a["lon"]) * RAD))
    return (math.atan2(y, x) / RAD + 360) % 360


def ang_diff(a, b):
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


def fetch():
    body = ("data=" + urllib.parse.quote(QUERY)).encode()
    for attempt in range(3):
        for h in HOSTS:
            try:
                req = urllib.request.Request(h, data=body, headers={"User-Agent": "TAG-TRACKER-data/1.0",
                                                                    "Content-Type": "application/x-www-form-urlencoded"})
                with urllib.request.urlopen(req, timeout=150) as r:
                    d = json.load(r)
                if d.get("elements"):
                    print("Overpass OK:", h, len(d["elements"]), "elementos")
                    return d
            except Exception as e:
                print("fallo", h, e)
        time.sleep(20)
    sys.exit("No se pudo consultar Overpass")


def parse(d):
    """Igual que parseOverpass de la app, guardando además todas las etiquetas del nodo."""
    nodes = {}
    for e in d["elements"]:
        if e["type"] == "node":
            tg = e.get("tags", {})
            nodes[e["id"]] = {"id": e["id"], "lat": e["lat"], "lon": e["lon"], "name": tg.get("name") or tg.get("ref") or "",
                              "op": tg.get("operator", ""), "road": "", "brg": None, "tags": tg, "wayref": ""}
    for w in d["elements"]:
        if w["type"] != "way" or "nodes" not in w or "geometry" not in w:
            continue
        tg = w.get("tags", {})
        geom = w["geometry"]
        for i, nid in enumerate(w["nodes"]):
            g = nodes.get(nid)
            if not g:
                continue
            if not g["road"]:
                g["road"] = tg.get("name") or tg.get("ref") or ""
                g["wayref"] = tg.get("ref", "")
            if g["brg"] is None and tg.get("oneway") != "no" and len(geom) > 1:
                brg = bearing(geom[max(0, i - 1)], geom[min(len(geom) - 1, i + 1)])
                if tg.get("oneway") == "-1":
                    brg = (brg + 180) % 360
                g["brg"] = int(math.floor(brg + 0.5)) % 360
    return list(nodes.values())


def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s or "") if unicodedata.category(c) != "Mn").upper()


def ref_keys(name):
    toks = [t for t in re.split(r"[^A-Z0-9.]+", fold(name)) if t]
    keys = {"".join(toks)} | set(toks)
    for i in range(len(toks) - 1):
        keys.add(toks[i] + toks[i + 1])
    # "P01" equivale a "P1"
    return keys | {re.sub(r"^([A-Z]+)0+(?=\d)", r"\1", k) for k in keys}


def ref_matches(ref, g):
    r = re.sub(r"[^A-Z0-9.]", "", fold(ref))
    if not r:
        return False
    keys = set()
    for k in ("name", "ref", "official_name", "alt_name", "short_name", "loc_name", "description"):
        if g["tags"].get(k):
            keys |= ref_keys(g["tags"][k])
    if r in keys:
        return True
    return any(c.isalpha() for c in r) and "." in r and r.replace(".", "") in {k.replace(".", "") for k in keys}


def match(entry, g):
    if g["id"] in entry.get("ids", []):
        return True
    if entry.get("nombre") and fold(entry["nombre"]) not in fold(g["tags"].get("name", "")):
        return False
    op = fold(entry.get("operador", ""))
    vias = [fold(v) for v in entry.get("via", [])]
    place = (not op and not vias) or (op and op in fold(g["op"])) or any(v in fold(g["road"]) or v in fold(g["wayref"]) for v in vias)
    if not place:
        return False
    if entry.get("ref") and not ref_matches(entry["ref"], g):
        return False
    r = entry.get("rumbo")
    return r is None or g["brg"] is None or ang_diff(g["brg"], r) <= RUMBO_TOL


def main():
    gantries = parse(fetch())
    assigned, unmatched = {}, []
    for e in gen_tarifas.out:
        ids = [g["id"] for g in gantries if match(e, g)]
        if not ids:
            unmatched.append(e)
        for i in ids:
            assigned[i] = e
    out = []
    for g in sorted(gantries, key=lambda x: x["id"]):
        o = {k: g[k] for k in ("id", "lat", "lon", "name", "op", "road", "brg")}
        e = assigned.get(g["id"])
        if e:
            o["t"] = {k: e[k] for k in ("base", "punta", "saturacion", "horarios") if k in e}
        out.append(o)
    os.makedirs(os.path.join(ROOT, "data"), exist_ok=True)
    doc = {"version": 1, "generado": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "vigencia": "2026",
           "fuente_porticos": "OpenStreetMap (ODbL)", "fuente_tarifas": gen_tarifas.FUENTE, "porticos": out}
    with open(os.path.join(ROOT, "data", "porticos.json"), "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    # Reporte para revisar el cruce
    with open(os.path.join(ROOT, "data", "reporte.txt"), "w", encoding="utf-8") as f:
        f.write(f"{len(gantries)} porticos, {len(assigned)} con tarifa, {len(unmatched)} entradas de tarifa sin portico\n\n")
        f.write("ENTRADAS SIN PORTICO\n")
        for e in unmatched:
            f.write(f"  {e['operador']} ref={e.get('ref','')} nombre={e.get('nombre','')} rumbo={e.get('rumbo')}\n")
        f.write("\nPORTICOS (id | con tarifa | rumbo | via | wayref | operador | etiquetas)\n")
        for g in sorted(gantries, key=lambda x: (x["road"], x["name"], x["id"])):
            tags = {k: v for k, v in g["tags"].items() if k != "highway"}
            e = assigned.get(g["id"])
            mark = (f"{e['operador']}:{e.get('ref', '') or e.get('nombre', '')}:{e.get('rumbo')}:{e['base']}") if e else "-"
            f.write(f"  {g['id']} | {mark} | {g['brg']} | {g['road']} | {g['wayref']} | {g['op']} | {g['lat']:.5f},{g['lon']:.5f} | {json.dumps(tags, ensure_ascii=False)}\n")
    print(open(os.path.join(ROOT, "data", "reporte.txt"), encoding="utf-8").read()[:3000])


if __name__ == "__main__":
    main()
