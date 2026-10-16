"""Old results must equal the names the 1.2.4 hooks used; new results are compared with an
independent structural matcher (class/method similarity) that knows nothing about the spec."""
import os
SPEC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "symbols.json")
import json
import dx
import expect_old
import spec_eval

spec = json.load(open(SPEC, encoding="utf-8"))
bad = 0
for app, (ot, nt), exp in (("camera", ("old_cam", "new_cam"), expect_old.CAM), ("gallery", ("old_gal", "new_gal"), expect_old.GAL)):
    ro = spec_eval.Replay(ot, spec[app]); ro.run()
    rn = spec_eval.Replay(nt, spec[app]); rn.run()
    for sid, want in exp.items():
        o, n = ro.res.get(sid), rn.res.get(sid)
        if o is None or n is None:
            print("UNRESOLVED", sid); bad += 1; continue
        got = spec_eval.label(o)
        if got != want:
            print(f"OLD MISMATCH {sid}: {got} != {want}"); bad += 1; continue
        if "name" not in o:
            m = dx.match_class(dx.J(o["class"]), ot, nt, top=2)
            ok = bool(m) and m[0][1] == dx.J(n["class"])
            if not ok or (len(m) > 1 and m[0][0] == m[1][0]):
                print(f"NEW CLASS {'ok-tie' if ok else '??'} {sid}: spec={dx.J(n['class'])} matcher={m}")
            bad += not ok
        elif "proto" in o:
            m = dx.match_method(dx.J(o["class"]), dx.J(n["class"]), o["name"], o["proto"], ot, nt)[0][2]
            ok = m[0][1] == n["name"] or (len(m) > 1 and m[0][0] == m[1][0] and n["name"] in [x[1] for x in m if x[0] == m[0][0]])
            tie = len(m) > 1 and m[0][0] == m[1][0]
            if not ok or tie:
                print(f"NEW METHOD {'ok-tie' if ok else '??'} {sid}: spec={n['name']} matcher={m[:3]}")
            bad += not ok
        else:
            print(f"field {sid}: {got} -> {spec_eval.label(n)}")
print("crosscheck problems:", bad)
