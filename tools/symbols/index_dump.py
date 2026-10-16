"""Parse dexdump -d text into a compact pickle index: classes -> members, strings, calls, field refs."""
import sys, re, pickle, glob, os
root, out = sys.argv[1], sys.argv[2]
classes = {}
rx_cls = re.compile(r"^  Class descriptor  : '(.*)'")
rx_sup = re.compile(r"^  Superclass        : '(.*)'")
rx_item = re.compile(r"^      name          : '(.*)'")
rx_type = re.compile(r"^      type          : '(.*)'")
rx_acc = re.compile(r"^      access        : (0x[0-9a-f]+)")
rx_str = re.compile(r'const-string(?:/jumbo)? v\d+, "(.*)" // string@')
rx_inv = re.compile(r"invoke-(\w+)(?:/range)? \{[^}]*\}, (L[^;]+;)\.([^:]+):(\S+) // method@")
rx_fld = re.compile(r"([is](?:get|put)[\w-]*) [^L]*(L[^;]+;)\.([^:]+):(\S+) // field@")
rx_num = re.compile(r"const(?:/4|/16|/high16)? v\d+, #int (-?\d+)")
for path in sorted(glob.glob(os.path.join(root, "*.txt"))):
    cur = None; section = None; member = None; pend = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("  Class descriptor"):
                m = rx_cls.match(line); cur = {"super": None, "ifaces": [], "sfields": [], "ifields": [], "methods": [], "dex": os.path.basename(path)}
                classes[m.group(1)] = cur; section = None; member = None; continue
            if cur is None: continue
            if line.startswith("  Superclass"):
                cur["super"] = rx_sup.match(line).group(1); continue
            if line.startswith("  Interfaces"): section = "ifaces"; continue
            if line.startswith("  Static fields"): section = "sfields"; continue
            if line.startswith("  Instance fields"): section = "ifields"; continue
            if line.startswith("  Direct methods") or line.startswith("  Virtual methods"): section = "methods"; continue
            if line.startswith("  source_file_idx"): section = None; continue
            if section == "ifaces" and line.startswith("    #"):
                cur["ifaces"].append(line.split("'")[1]); continue
            if section in ("sfields", "ifields", "methods"):
                if line.startswith("    #"):
                    member = {"name": None}; cur[section].append(member)
                    if section == "methods": member.update(strings=[], calls=[], fields=[], ints=[], direct=None)
                    continue
                if member is None: continue
                if member["name"] is None:
                    m = rx_item.match(line)
                    if m: member["name"] = m.group(1); continue
                if "type" not in member:
                    m = rx_type.match(line)
                    if m: member["type"] = m.group(1); continue
                if "access" not in member:
                    m = rx_acc.match(line)
                    if m: member["access"] = int(m.group(1), 16); continue
                if section == "methods" and "|" in line:
                    m = rx_str.search(line)
                    if m: member["strings"].append(m.group(1)); continue
                    m = rx_inv.search(line)
                    if m: member["calls"].append((m.group(2), m.group(3), m.group(4))); continue
                    m = rx_fld.search(line)
                    if m: member["fields"].append((m.group(1), m.group(2), m.group(3), m.group(4))); continue
                    m = rx_num.search(line)
                    if m: member["ints"].append(int(m.group(1)))
pickle.dump(classes, open(out, "wb"), protocol=4)
print(out, len(classes))
