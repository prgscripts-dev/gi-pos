import sqlite3, os
db = os.path.join(os.environ['TEMP'], 'g3.db')
con = sqlite3.connect(db); con.row_factory = sqlite3.Row; cur = con.cursor()
print("=== printer profiles ===")
try:
    for r in cur.execute("SELECT name,connection_type,paper_width,role,is_default,active FROM printer_profiles"):
        print(f"  {r['name']} type={r['connection_type']} paper_width={r['paper_width']}mm role={r['role']} default={r['is_default']} active={r['active']}")
except Exception as e:
    print("  (no printer_profiles or error)", e)
con.close()

# --- Simulate the Writer layout for the totals block, both widths ---
def trim(v,w): return v if len(v)<=w else v[:w]
def padL(v,w): t=trim(v,w); return " "*(max(0,w-len(t)))+t
def padR(v,w): t=trim(v,w); return t+" "*(max(0,w-len(t)))

def totalsRow(cols, leftLabel, label, rate, amount):
    amountW = 9 if cols<=32 else 10
    rateW = 5
    labelW = 13 if cols<=32 else 16
    group = padR(trim(label,labelW),labelW)+padL(trim(rate,rateW),rateW)+padL(trim(amount,amountW),amountW)
    groupW = len(group)
    ll = trim(leftLabel or "", max(0,cols-groupW-1))
    leftPad = max(0, cols-groupW-len(ll))
    return ll + " "*leftPad + group

def billItem(cols,name,qty,price,amount):
    qtyW=4; priceW=7 if cols<=32 else 10; amountW=8 if cols<=32 else 11; nameW=cols-qtyW-priceW-amountW
    # simple one-line wrap
    lines=[]; s=name
    while len(s)>nameW:
        cut=s.rfind(' ',0,nameW); 
        if cut<1: cut=nameW
        lines.append(s[:cut].strip()); s=s[cut:].strip()
    lines.append(s)
    out=[]
    for i,l in enumerate(lines):
        q=qty if i==0 else ""; p=price if i==0 else ""; a=amount if i==0 else ""
        out.append(padR(l,nameW)+padL(q,qtyW)+padL(p,priceW)+padL(a,amountW))
    return "\n".join(out)

for cols in (32,48):
    print(f"\n================= {cols} cols ({'58mm' if cols==32 else '80mm'}) =================")
    print("-"*cols)
    print(padR("Item",cols-(4+(7 if cols<=32 else 10)+(8 if cols<=32 else 11)))+padL("Qty.",4)+padL("Price",7 if cols<=32 else 10)+padL("Amount",8 if cols<=32 else 11))
    print("-"*cols)
    print(billItem(cols,"Shawaya Mandhi (Quarter)","1","230","230.00"))
    print(billItem(cols,"Kanthari Al Faham Mandhi (Half)","1","460","460.00"))
    print(billItem(cols,"Pepsi 750ml","1","40","40.00"))
    print("-"*cols)
    print(totalsRow(cols,"Total Qty: 3","Sub Total","","695.25"))
    print(totalsRow(cols,None,"CGST@2.5","2.5%","17.38"))
    print(totalsRow(cols,None,"SGST@2.5","2.5%","17.38"))
    print(totalsRow(cols,None,"Round off","","-0.01"))
    print(totalsRow(cols,None,"Grand Total","","Rs.730.00"))
    print("-"*cols)
